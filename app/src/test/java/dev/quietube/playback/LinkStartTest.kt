package dev.quietube.playback

import org.junit.Assert.*
import org.junit.Test

class LinkStartTest {
    @Test fun explicitTimeOverridesResumeAndCompletionRule() {
        assertEquals(0L, playbackStart(0, 50000, 100, false))
        assertEquals(98000L, playbackStart(98000, 50000, 100, false))
        assertEquals(100000L, playbackStart(500000, 50000, 100, false))
        assertEquals(0L, playbackStart(-1, 50000, 100, false))
    }
    @Test fun regularResumeAndLiveBehaviorArePreserved() {
        assertEquals(50000L, playbackStart(null, 50000, 100, false))
        assertEquals(0L, playbackStart(null, 98000, 100, false))
        assertEquals(0L, playbackStart(50000, 50000, 100, true))
        assertEquals(50000L, playbackStart(50000, 0, -1, false))
        assertEquals(50000L, playbackStart(50000, 0, Long.MAX_VALUE, false))
    }
}
