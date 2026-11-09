@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.cast

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class MatroskaLayoutTest {
    private val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build()
    private val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setSampleRate(48_000).setChannelCount(2)
        .setInitializationData(listOf(ByteArray(19))).build()
    private val layout = MatroskaWriter.layout(video, audio, originUs = 0, durationUs = 600_000_000, clusterBytes = 100_000_000)

    @Test fun headerFillsEverythingBeforeTheClusterAreaAndDeclaresTheSegmentSize() {
        assertEquals(layout.clustersStart, layout.header.size.toLong())
        assertEquals(layout.clustersStart + 100_000_000, layout.size)
        val segment = layout.header.indexOfSequence(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        val declared = (1..7).fold(0L) { value, i -> value shl 8 or (layout.header[segment + 4 + i].toLong() and 0xFF) }
        assertEquals(0x01.toByte(), layout.header[segment + 4])
        assertEquals(layout.size - (segment + 12), declared)
    }

    @Test fun offsetsAndTimesMapLinearlyAcrossTheClusterArea() {
        assertEquals(layout.clustersStart, layout.offsetOf(0))
        assertTrue(kotlin.math.abs(layout.timeOf(layout.offsetOf(300_000_000)) - 300_000_000) < 10_000)
        assertTrue(layout.offsetOf(600_000_000) < layout.size)
        assertEquals(0L, layout.timeOf(0))
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray) =
        (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }
}
