package dev.quietube.data

import org.junit.Assert.*
import org.junit.Test

class WatchProgressTest {
    private val video = Video("url", "Title", "Channel", null, 100, 1, null, resumePositionMs = 50000)
    @Test fun localProgressTakesPrecedenceIncludingRestartAtZero() {
        assertEquals(30000L, withWatchProgress(video, 30000).resumePositionMs)
        assertEquals(0L, withWatchProgress(video, 0).resumePositionMs)
    }
    @Test fun missingLocalProgressKeepsAccountProgressAndMetadata() {
        assertEquals(video, withWatchProgress(video, null))
        assertEquals(video.copy(resumePositionMs = 1234), withWatchProgress(video, 1234))
    }
}
