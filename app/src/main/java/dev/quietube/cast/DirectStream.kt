@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.cast

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Serves one video to one TV as a Matroska file that is remuxed while it is sent: YouTube's separate video and
 * audio tracks are read and interleaved as they download. Only [tv] may connect, at an unguessable path. The file
 * covers the whole video and starts at its beginning: TVs count their clock and seek targets from the first
 * timestamp they receive, so the caster seeks to the phone's position once the TV is playing.
 *
 * TV players seek files by byte offset, so the stream presents itself as an ordinary, seekable file of fixed size.
 * The header declares the segment size, the duration and an index (Cues) with a point every
 * [MatroskaWriter.CUE_INTERVAL_MS]. Byte offsets in the cluster area map linearly to time, both for players that
 * use the index and for those (like Hisense's) that estimate an offset from size and duration; a request for any
 * offset is answered with clusters starting at the keyframe for that time. The cluster area is sized from
 * YouTube's exact track sizes ([sourceBytes]) with headroom, and every response is padded to its declared length.
 */
internal class DirectStream(private val sources: DataSource.Factory, private val video: Uri, private val audio: Uri,
    private val contentDurationUs: Long, private val sourceBytes: Long,
    private val tv: InetAddress, local: InetAddress) {
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(local, 0)) }
    private val path = "/${UUID.randomUUID()}.mkv"
    private val clients = mutableSetOf<Socket>()
    @Volatile private var closed = false
    @Volatile private var layout: MatroskaWriter.Layout? = null
    val url = "http://${local.hostAddress}:${server.localPort}$path"
    /** Whether the TV has been sent video; until then its reported state can belong to what it played before. */
    @Volatile var sending = false; private set
    /** Content time of the stream's 0:00 (the video's first frame, usually 0), once probed. */
    val originUs get() = layout?.originUs ?: -1L

    fun start() = thread(name = "tv-stream-accept", isDaemon = true) {
        while (!closed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            if (socket.inetAddress != tv) { socket.close(); continue }
            synchronized(clients) { clients += socket }
            thread(name = "tv-stream-client", isDaemon = true) {
                // Usually the TV closing a connection; logged without URLs to diagnose source failures.
                try { serve(socket) } catch (e: Exception) { android.util.Log.w(TAG, "Stream ended: ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
                finally { synchronized(clients) { clients -= socket }; runCatching { socket.close() } }
            }
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
    }

    /** Header, index and size, from the tracks' formats and the first keyframe; identical for every request. */
    private fun layout(): MatroskaWriter.Layout = layout ?: synchronized(this) {
        layout ?: run {
            val videoReader = TrackReader(sources, video)
            val audioReader = TrackReader(sources, audio)
            try {
                videoReader.open(0); audioReader.open(0)
                val origin = videoReader.next()?.timeUs ?: error("No video")
                MatroskaWriter.layout(videoReader.format!!, audioReader.format!!, origin, (contentDurationUs - origin).coerceAtLeast(1),
                    clusterBytes = (sourceBytes * 1.03).toLong() + 512 * 1024)
            } finally { videoReader.close(); audioReader.close() }
        }.also { layout = it }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val request = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val line = request.readLine() ?: return
        val headers = generateSequence { request.readLine()?.takeIf { it.isNotEmpty() } }.mapNotNull { header ->
            header.indexOf(':').takeIf { it > 0 }?.let { header.substring(0, it).trim().lowercase() to header.substring(it + 1).trim() }
        }.toMap()
        val (method, target) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        android.util.Log.i(TAG, "TV request: $method range=${headers["range"]} timeseek=${headers["timeseekrange.dlna.org"]}")
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        if (target != path || method !in setOf("GET", "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val file = layout()
        val size = file.size
        val timeSeek = headers["timeseekrange.dlna.org"]?.let(::parseNpt)?.coerceIn(0, file.durationUs)
        val byte = Regex("bytes=(\\d+)-").find(headers["range"].orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0, size - 1)
        val from = byte ?: timeSeek?.let(file::offsetOf) ?: 0L
        android.util.Log.i(TAG, "Serving from ${if (from < file.header.size) "the start" else "${file.timeOf(from) / 1_000_000}s"}")
        val status = if (byte != null) "206 Partial Content" else "200 OK"
        val range = if (byte != null) "Content-Range: bytes $from-${size - 1}/$size\r\n" else ""
        val seekHeader = timeSeek?.let { "TimeSeekRange.dlna.org: npt=${npt(it)}-${npt(file.durationUs)}/${npt(file.durationUs)}\r\n" }.orEmpty()
        out.write(("HTTP/1.1 $status\r\nContent-Type: $MIME\r\nContent-Length: ${size - from}\r\n$range$seekHeader" +
            "Accept-Ranges: bytes\r\nConnection: close\r\ntransferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: $FEATURES\r\n\r\n").toByteArray())
        out.flush()
        if (method == "HEAD") return
        socket.soTimeout = 0
        val limited = Limited(out, size - from)
        try {
            if (from < file.header.size) {
                limited.write(file.header, from.toInt(), file.header.size - from.toInt())
                remux(limited, 0)
            } else remux(limited, file.timeOf(from))
            limited.pad()
        } catch (_: Limited.Full) { /* the declared length is reached; the response is complete */ }
        out.flush()
    }

    /** Writes clusters from the keyframe at or before stream time [fromUs]; timestamps are stream time. */
    private fun remux(out: OutputStream, fromUs: Long) {
        val file = layout()
        val videoReader = TrackReader(sources, video)
        val audioReader = TrackReader(sources, audio)
        try {
            videoReader.open(file.originUs + fromUs)
            audioReader.open(file.originUs + fromUs)
            val avc = videoReader.format?.sampleMimeType == MimeTypes.VIDEO_H264
            // The seek lands on a keyframe; audio joins from there.
            val first = generateSequence { videoReader.next() }.firstOrNull { it.timeUs >= file.originUs } ?: return
            var nextVideo: Sample? = first
            var nextAudio = generateSequence { audioReader.next() }.firstOrNull { it.timeUs >= first.timeUs }
            val writer = MatroskaWriter(out)
            sending = true
            while (!closed && (nextVideo != null || nextAudio != null)) {
                val takeVideo = nextVideo != null && (nextAudio == null || nextVideo.timeUs <= nextAudio.timeUs)
                if (takeVideo) {
                    val video = nextVideo!!
                    writer.write(1, (video.timeUs - file.originUs) / 1000, video.keyframe,
                        if (avc) MatroskaWriter.lengthPrefixed(video.data) else video.data)
                    nextVideo = videoReader.next()
                } else {
                    val sample = nextAudio!!
                    writer.write(2, (sample.timeUs - file.originUs) / 1000, true, sample.data)
                    nextAudio = audioReader.next()
                }
            }
            writer.flush()
        } finally {
            videoReader.close(); audioReader.close()
        }
    }

    /** Caps a response at its declared length and pads it to exactly that length with an EBML Void element. */
    private class Limited(private val out: OutputStream, private var remaining: Long) : OutputStream() {
        class Full : IOException()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val n = minOf(len.toLong(), remaining).toInt()
            out.write(b, off, n); remaining -= n
            if (n < len) throw Full()
        }
        override fun flush() = out.flush()
        fun pad() {
            if (remaining <= 0) return
            if (remaining == 1L) { out.write(0); remaining = 0; return }
            // Void (0xEC) with an 8-byte size covers any length; short pads use a 1-byte size.
            val header = if (remaining - 2 < 127) byteArrayOf(0xEC.toByte(), (0x80 or (remaining - 2).toInt()).toByte())
                else byteArrayOf(0xEC.toByte(), 0x01) + ByteArray(7) { i -> ((remaining - 9) ushr (8 * (6 - i))).toByte() }
            out.write(header); remaining -= header.size
            val zeros = ByteArray(64 * 1024)
            while (remaining > 0) { val n = minOf(remaining, zeros.size.toLong()).toInt(); out.write(zeros, 0, n); remaining -= n }
        }
    }

    /** Stream duration, once probed. */
    fun streamDurationUs() = layout?.durationUs ?: contentDurationUs

    companion object {
        const val MIME = "video/x-matroska"
        private const val TAG = "YouPoopTv"
        /** DLNA.ORG_OP=11: byte and time seek; FLAGS: streaming transfer mode. */
        const val FEATURES = "DLNA.ORG_OP=11;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        /** DLNA normal play time: "npt=123.4-" or "npt=0:02:03.4-" (start only), in microseconds. */
        fun parseNpt(value: String): Long? {
            val start = value.substringAfter("npt=", "").substringBefore('-').trim().ifEmpty { return null }
            val seconds = start.split(':').map { it.toDoubleOrNull() ?: return null }.fold(0.0) { total, part -> total * 60 + part }
            return (seconds * 1_000_000).toLong()
        }

        fun npt(us: Long) = String.format(java.util.Locale.US, "%.3f", us / 1_000_000.0)

        /** H:MM:SS.mmm, as DIDL durations and AVTransport seek targets expect. */
        fun clock(us: Long): String {
            val ms = us / 1000
            return String.format(java.util.Locale.US, "%d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
    }
}

/** UPnP AVTransport control for a TV's DLNA media renderer. */
internal object Dlna {
    fun play(controlUrl: String, mediaUrl: String, title: String, durationUs: Long) {
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${DirectStream.MIME}:${DirectStream.FEATURES}" duration="${DirectStream.clock(durationUs)}">${xml(mediaUrl)}</res></item></DIDL-Lite>"""
        action(controlUrl, "Stop", "")  // A renderer that is already playing may refuse a new URI otherwise.
        action(controlUrl, "SetAVTransportURI", "<CurrentURI>${xml(mediaUrl)}</CurrentURI><CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>", required = true)
        action(controlUrl, "Play", "<Speed>1</Speed>", required = true)
    }

    fun stop(controlUrl: String) { action(controlUrl, "Stop", "") }

    /** Current position in the stream and whether the TV is playing (not paused, stopped or loading). */
    fun status(controlUrl: String): Pair<Long, Boolean>? {
        val time = Regex("<RelTime>([0-9:.]+)</RelTime>").find(action(controlUrl, "GetPositionInfo", "") ?: return null)?.groupValues?.get(1)
        val state = Regex("<CurrentTransportState>(\\w+)</CurrentTransportState>").find(action(controlUrl, "GetTransportInfo", "") ?: return null)?.groupValues?.get(1)
        val seconds = time?.split(':')?.map { it.toDoubleOrNull() ?: return null }?.fold(0.0) { total, part -> total * 60 + part } ?: return null
        return (seconds * 1000).toLong() to (state == "PLAYING")
    }

    /** Seek within the current stream; the TV re-requests it at the indexed offset. */
    fun seek(controlUrl: String, streamUs: Long) =
        action(controlUrl, "Seek", "<Unit>REL_TIME</Unit><Target>${DirectStream.clock(streamUs).substringBeforeLast('.')}</Target>", required = true)

    private fun action(controlUrl: String, name: String, arguments: String, required: Boolean = false): String? {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$name xmlns:u="$SERVICE"><InstanceID>0</InstanceID>$arguments</u:$name></s:Body></s:Envelope>"""
        val response = runCatching {
            LanHttp.request("POST", controlUrl, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$SERVICE#$name\""), body)
        }
        if (required) check(response.getOrNull()?.code == 200) { "The TV refused $name (${response.getOrNull()?.code})" }
        return response.getOrNull()?.takeIf { it.code == 200 }?.body
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
}
