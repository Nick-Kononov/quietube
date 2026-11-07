@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * googlevideo throttles open-ended byte ranges to roughly real-time speed (measured 5.6 Mbps,
 * below a 6.9 Mbps 1080p60 stream) but serves bounded ranges at full speed (325 Mbps with 10 MB
 * ranges). Like yt-dlp and NewPipe, read progressive YouTube streams as consecutive bounded
 * ranges; the extractor above sees one continuous stream.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = CHUNK_BYTES,
    private val contentLength: (Uri) -> Long? = ::youtubeContentLength,
) : DataSource {
    private var spec: DataSpec? = null
    private var chunking = false
    private var position = 0L
    private var end = 0L
    private var chunkEnd = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val end = chunkedEnd(dataSpec.position, dataSpec.length, contentLength(dataSpec.uri))
        // Small streams (often audio) need a bounded range too: the throttle applies to any open-ended request.
        if (end == null) {
            chunking = false
            return upstream.open(dataSpec)
        }
        spec = dataSpec; chunking = true
        position = dataSpec.position; this.end = end
        openChunk()
        return end - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunking) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (position < end) {
            val read = upstream.read(buffer, offset, minOf(length.toLong(), end - position).toInt())
            if (read != C.RESULT_END_OF_INPUT) { position += read; return read }
            upstream.close()
            // A short chunk means the content ended before the advertised size; stop there.
            if (position < chunkEnd) end = position else openChunk()
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun openChunk() {
        val current = spec!!
        val length = minOf(chunkBytes, end - position)
        chunkEnd = position + length
        upstream.open(current.subrange(position - current.position, length))
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { spec = null; chunking = false; upstream.close() }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    companion object {
        const val CHUNK_BYTES = 10L * 1024 * 1024
    }
}

/** Absolute end of a chunked read, or null when the size is unknown and the request passes through. */
internal fun chunkedEnd(position: Long, length: Long, contentLength: Long?): Long? = when {
    length != C.LENGTH_UNSET.toLong() -> position + length
    contentLength != null && contentLength > position -> contentLength
    else -> null
}

/** Progressive YouTube media URLs advertise their size as `clen`; manifests and live segments do not. */
internal fun youtubeContentLength(uri: Uri): Long? =
    if (uri.scheme == "https" && uri.host?.endsWith(".googlevideo.com") == true && uri.path == "/videoplayback")
        uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
    else null
