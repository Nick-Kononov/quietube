package dev.quietube.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePositionTest {
    @Test fun `unfinished video resumes but completed video restarts`() {
        assertEquals(30_000L, resumablePosition(30_000, 120_000))
        assertEquals(0L, resumablePosition(118_000, 120_000))
        assertEquals(0L, resumablePosition(-1, 120_000))
        assertEquals(30_000L, resumablePosition(30_000, -1))
    }
}
