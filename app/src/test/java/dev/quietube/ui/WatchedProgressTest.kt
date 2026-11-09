package dev.quietube.ui

import dev.quietube.data.Video
import org.junit.Assert.*
import org.junit.Test

class WatchedProgressTest {
    @Test fun hidesUnknownLiveAndBarelyStarted() {
        assertNull(watchedFraction(null, 100))
        assertNull(watchedFraction(10000, 0))
        assertNull(watchedFraction(10000, -1))
        assertNull(watchedFraction(10000, 100, true))
        assertNull(watchedFraction(-1000, 100))
        assertNull(watchedFraction(1999, 100))
    }
    @Test fun thresholdsAndClamping() {
        assertEquals(.02f, watchedFraction(2000, 100)!!, .00001f)
        assertEquals(.5f, watchedFraction(50000, 100)!!, .00001f)
        assertEquals(.94999f, watchedFraction(94999, 100)!!, .00001f)
        assertEquals(1f, watchedFraction(95000, 100)!!, 0f)
        assertEquals(1f, watchedFraction(Long.MAX_VALUE, 100)!!, 0f)
        assertNull(watchedFraction(10000, Long.MAX_VALUE))
    }
    @Test fun oneLabelIncludesAvailableMetadataAndProgress() {
        val video = Video("url", "A video", "A channel", null, 100, 1200, "Yesterday", resumePositionMs = 50000)
        assertEquals("A video, A channel, 1:40, 1.2K views, Yesterday, 50 percent watched", videoLabel(video))
        assertEquals("A video, Live", videoLabel(video.copy(channel = "", isLive = true, viewCount = -1, published = null)))
    }
}
