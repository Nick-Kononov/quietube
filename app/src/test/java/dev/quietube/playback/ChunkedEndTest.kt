package dev.quietube.playback

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class ChunkedEndTest {
    private val unset = C.LENGTH_UNSET.toLong()
    @Test fun `bounded request ends at its own length`() = assertEquals(1_500L, chunkedEnd(500, 1_000, 99_999))
    @Test fun `open-ended request ends at advertised content length`() = assertEquals(40_000L, chunkedEnd(1_000, unset, 40_000))
    @Test fun `unknown size passes through`() = assertNull(chunkedEnd(0, unset, null))
    @Test fun `position at or past content length passes through`() = assertNull(chunkedEnd(40_000, unset, 40_000))
}
