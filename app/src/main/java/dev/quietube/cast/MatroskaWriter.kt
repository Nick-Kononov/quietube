@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.cast

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Writes Matroska clusters as samples arrive, so a file can be produced while it is being sent; [layout] builds
 * the header that comes before them. Video samples are written in decode order with their presentation
 * timestamps, which Matroska players expect.
 */
internal class MatroskaWriter(private val out: OutputStream) {
    private var cluster = ByteArrayOutputStream()
    private var clusterMs = -1L
    private var lastVideoKeyMs = -1L

    /** [timeMs] is relative to the stream start. */
    fun write(track: Int, timeMs: Long, keyframe: Boolean, data: ByteArray) {
        val video = track == 1
        val relative = timeMs - clusterMs
        // Clusters start on video keyframes (so a player can begin at any cluster) and stay within int16 offsets.
        if (clusterMs < 0 || (video && keyframe && timeMs - lastVideoKeyMs >= 1_000) || relative !in -30_000..30_000 || cluster.size() > 8 shl 20) {
            flush()
            clusterMs = timeMs
            cluster.write(uint(0xE7, timeMs.coerceAtLeast(0)))
        }
        if (video && keyframe) lastVideoKeyMs = timeMs
        val offset = (timeMs - clusterMs).toInt()
        val header = byteArrayOf((0x80 or track).toByte(), (offset shr 8).toByte(), offset.toByte(), if (keyframe) 0x80.toByte() else 0)
        cluster.write(id(0xA3)); cluster.write(size(header.size.toLong() + data.size)); cluster.write(header); cluster.write(data)
    }

    fun flush() {
        if (cluster.size() == 0) return
        out.write(id(0x1F43B675)); out.write(size(cluster.size().toLong())); cluster.writeTo(out)
        out.flush()
        cluster = ByteArrayOutputStream()
    }

    /**
     * A file of fixed [size]: [header] (EBML header, segment, seek head, info, tracks and cues) followed by the
     * cluster area, whose byte offsets map linearly to stream time so any offset can be produced on request.
     */
    class Layout(val header: ByteArray, val originUs: Long, val durationUs: Long, val clustersStart: Long, val size: Long) {
        fun offsetOf(timeUs: Long): Long =
            (clustersStart + ((size - clustersStart) * (timeUs.toDouble() / durationUs)).toLong()).coerceIn(clustersStart, size - 1)
        fun timeOf(offset: Long): Long =
            ((offset - clustersStart).toDouble() / (size - clustersStart) * durationUs).toLong().coerceIn(0, durationUs)
    }

    companion object {
        const val CUE_INTERVAL_MS = 2_000L

        fun layout(video: Format, audio: Format, originUs: Long, durationUs: Long, clusterBytes: Long): Layout {
            val ebml = element(0x1A45DFA3, uint(0x4286, 1) + uint(0x42F7, 1) + uint(0x42F2, 4) + uint(0x42F3, 8) +
                string(0x4282, "matroska") + uint(0x4287, 4) + uint(0x4285, 2))
            val segmentDataStart = ebml.size + 4 + 8L
            val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000) + string(0x4D80, "YouPoop") + string(0x5741, "YouPoop") +
                float(0x4489, durationUs / 1000.0))
            val tracks = element(0x1654AE6B, track(1, video) + track(2, audio))
            val durationMs = durationUs / 1000
            // Long videos get sparser cues so the index stays small.
            val interval = maxOf(CUE_INTERVAL_MS, durationMs / 4_000)
            val times = (0..durationMs step interval).toList()
            // Positions have fixed widths, so sizes are known before the positions are.
            fun seekHead(info: Long, tracks: Long, cues: Long) = element(0x114D9B74,
                listOf(0x1549A966 to info, 0x1654AE6B to tracks, 0x1C53BB6B to cues).fold(ByteArray(0)) { all, (id, position) ->
                    all + element(0x4DBB, element(0x53AB, id(id)) + uintFixed(0x53AC, position))
                })
            fun cues(position: (Long) -> Long) = element(0x1C53BB6B, ByteArrayOutputStream().apply {
                times.forEach { ms -> write(element(0xBB, uintFixed(0xB3, ms) + element(0xB7, uint(0xF7, 1) + uintFixed(0xF1, position(ms))))) }
            }.toByteArray())
            val seekHeadSize = seekHead(0, 0, 0).size
            val cuesSize = cues { 0 }.size
            val clustersStart = segmentDataStart + seekHeadSize + info.size + tracks.size + cuesSize
            val size = clustersStart + clusterBytes
            val layout = Layout(ByteArray(0), originUs, durationUs, clustersStart, size)
            val infoAt = seekHeadSize.toLong()
            val header = ebml + id(0x18538067) + sizeFixed(size - segmentDataStart) +
                seekHead(infoAt, infoAt + info.size, infoAt + info.size + tracks.size) + info + tracks +
                cues { ms -> layout.offsetOf(ms * 1000) - segmentDataStart }
            check(header.size.toLong() == clustersStart) { "Header size mismatch" }
            return Layout(header, originUs, durationUs, clustersStart, size)
        }

        private fun track(number: Int, format: Format): ByteArray {
            val mime = format.sampleMimeType
            val codec = when (mime) {
                MimeTypes.VIDEO_H264 -> "V_MPEG4/ISO/AVC"
                MimeTypes.VIDEO_VP9 -> "V_VP9"
                MimeTypes.AUDIO_AAC -> "A_AAC"
                MimeTypes.AUDIO_OPUS -> "A_OPUS"
                else -> error("Unsupported codec $mime")
            }
            val private = when (mime) {
                MimeTypes.VIDEO_H264 -> avcConfiguration(format.initializationData)
                MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS -> format.initializationData.firstOrNull()
                else -> null
            }
            val type = if (MimeTypes.isVideo(mime)) 1 else 2
            val details = if (type == 1) element(0xE0, uint(0xB0, format.width.toLong()) + uint(0xBA, format.height.toLong()))
                else element(0xE1, float(0xB5, format.sampleRate.toDouble()) + uint(0x9F, format.channelCount.toLong()))
            return element(0xAE, uint(0xD7, number.toLong()) + uint(0x73C5, number.toLong()) + uint(0x83, type.toLong()) +
                uint(0x9C, 0) + string(0x86, codec) + (private?.let { element(0x63A2, it) } ?: ByteArray(0)) + details)
        }

        /** Media3 hands H.264 parameter sets and samples in Annex B form; Matroska wants an avcC record. */
        fun avcConfiguration(initialization: List<ByteArray>): ByteArray {
            val units = initialization.flatMap(::nalUnits)
            val sps = units.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }
            val pps = units.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 }
            require(sps.isNotEmpty() && pps.isNotEmpty()) { "Missing H.264 parameter sets" }
            val out = ByteArrayOutputStream()
            out.write(1); out.write(sps[0][1].toInt()); out.write(sps[0][2].toInt()); out.write(sps[0][3].toInt())
            out.write(0xFF) // 4-byte NAL lengths
            out.write(0xE0 or sps.size); sps.forEach { out.write(it.size shr 8); out.write(it.size); out.write(it) }
            out.write(pps.size); pps.forEach { out.write(it.size shr 8); out.write(it.size); out.write(it) }
            return out.toByteArray()
        }

        /** Converts an Annex B access unit (start codes) to 4-byte length-prefixed NAL units. */
        fun lengthPrefixed(annexB: ByteArray): ByteArray {
            val units = nalUnits(annexB)
            if (units.isEmpty()) return annexB
            val out = ByteArrayOutputStream(annexB.size + 8)
            units.forEach { unit -> out.write(unit.size ushr 24); out.write(unit.size ushr 16); out.write(unit.size ushr 8); out.write(unit.size); out.write(unit) }
            return out.toByteArray()
        }

        /** Splits on 00 00 01 start codes; emulation prevention guarantees they never occur inside a unit. */
        fun nalUnits(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 2 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
            }
            if (starts.isEmpty()) return emptyList()
            return starts.mapIndexed { index, start ->
                var end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
                while (end > start && data[end - 1].toInt() == 0) end-- // trailing zero of a 4-byte start code
                data.copyOfRange(start, end)
            }.filter { it.isNotEmpty() }
        }

        private fun id(value: Int): ByteArray = when {
            value >= 0x1000000 -> byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            value >= 0x10000 -> byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            value >= 0x100 -> byteArrayOf((value ushr 8).toByte(), value.toByte())
            else -> byteArrayOf(value.toByte())
        }

        /** EBML variable-length size, using the shortest form. */
        fun size(value: Long): ByteArray {
            var length = 1
            while (length < 8 && value >= (1L shl (7 * length)) - 1) length++
            return ByteArray(length) { index -> (value ushr (8 * (length - 1 - index))).toByte() }.also {
                it[0] = (it[0].toInt() or (0x80 ushr (length - 1))).toByte()
            }
        }

        private fun element(id: Int, payload: ByteArray) = id(id) + size(payload.size.toLong()) + payload
        private fun uint(id: Int, value: Long): ByteArray {
            var length = 1
            while (length < 8 && value ushr (8 * length) != 0L) length++
            return element(id, ByteArray(length) { index -> (value ushr (8 * (length - 1 - index))).toByte() })
        }
        private fun uintFixed(id: Int, value: Long) = element(id, ByteArray(8) { index -> (value ushr (8 * (7 - index))).toByte() })
        private fun sizeFixed(value: Long) = byteArrayOf(0x01) + ByteArray(7) { index -> (value ushr (8 * (6 - index))).toByte() }
        private fun float(id: Int, value: Double): ByteArray {
            val bits = value.toRawBits()
            return element(id, ByteArray(8) { index -> (bits ushr (8 * (7 - index))).toByte() })
        }
        private fun string(id: Int, value: String) = element(id, value.toByteArray(Charsets.UTF_8))
    }
}
