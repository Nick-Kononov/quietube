@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

internal class Sample(val timeUs: Long, val keyframe: Boolean, val data: ByteArray)

/**
 * Reads the samples of a single-track YouTube adaptive stream (MP4 or WebM) with Media3's extractors, starting
 * from the seek point at or before a position. Blocking; run on a background thread.
 */
internal class TrackReader(private val sources: DataSource.Factory, private val uri: Uri) : ExtractorOutput, TrackOutput {
    var format: Format? = null; private set
    var durationUs = C.TIME_UNSET; private set
    private var seekMap: SeekMap? = null
    private var hasTrack = false
    private val pending = ArrayDeque<Sample>()
    private var bytes = ByteArray(256 * 1024)
    private var size = 0
    private var extractor: Extractor? = null
    private var source: DataSource? = null
    private var input: ExtractorInput? = null
    private val position = PositionHolder()
    private var ended = false

    fun open(startUs: Long) {
        openAt(0)
        val current = input!!
        extractor = DefaultExtractorsFactory().createExtractors(uri, emptyMap()).firstOrNull { candidate ->
            try { candidate.sniff(current) } catch (_: EOFException) { false } finally { current.resetPeekPosition() }
        }?.also { it.init(this) } ?: error("Unsupported stream")
        while ((format == null || seekMap == null) && !ended) step()
        val map = seekMap
        if (startUs > 0 && map != null && map.isSeekable) {
            val point = map.getSeekPoints(startUs).first
            pending.clear(); size = 0
            openAt(point.position)
            extractor!!.seek(point.position, point.timeUs)
        }
    }

    /** Next sample in decode order, or null at the end of the stream. */
    fun next(): Sample? {
        while (pending.isEmpty() && !ended) step()
        return pending.removeFirstOrNull()
    }

    fun close() { runCatching { source?.close() }; extractor?.release() }

    private fun step() {
        when (extractor!!.read(input!!, position)) {
            Extractor.RESULT_SEEK -> openAt(position.position)
            Extractor.RESULT_END_OF_INPUT -> ended = true
        }
    }

    private fun openAt(offset: Long) {
        runCatching { source?.close() }
        val next = sources.createDataSource()
        val length = next.open(DataSpec.Builder().setUri(uri).setPosition(offset).build())
        source = next
        input = DefaultExtractorInput(next, offset, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else offset + length)
    }

    // ExtractorOutput: YouTube adaptive streams carry one track; anything else is discarded.
    override fun track(id: Int, type: Int): TrackOutput = if (hasTrack) DiscardingTrackOutput() else { hasTrack = true; this }
    override fun endTracks() {}
    override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap; durationUs = seekMap.durationUs }

    // TrackOutput: bytes accumulate until sampleMetadata marks where a sample ends.
    override fun format(format: Format) { this.format = format }
    override fun durationUs(durationUs: Long) { if (durationUs != C.TIME_UNSET) this.durationUs = durationUs }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        ensure(length)
        val read = input.read(bytes, size, length)
        if (read == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return read; throw EOFException() }
        size += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        ensure(length)
        data.readBytes(bytes, size, length)
        size += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val end = this.size - offset
        val start = end - size
        if (start >= 0) pending.add(Sample(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0, bytes.copyOfRange(start, end)))
        // Bytes after this sample already belong to the next one.
        System.arraycopy(bytes, end, bytes, 0, offset)
        this.size = offset
    }

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
}
