package dev.quietube.ui

import org.junit.Assert.*
import org.junit.Test

class LikeCountTest {
    @Test fun missingTvCountUsesPublicYouTubeLikes() {
        assertEquals("12.3K",displayLikeCount(null,12_345))
        assertEquals("1.2M",displayLikeCount("",1_234_567))
        assertEquals("42",displayLikeCount("  ",42))
    }
    @Test fun knownZeroIsDisplayedAndUnavailableCountStaysUnknown() {
        assertEquals("0",displayLikeCount(null,0))
        assertEquals("—",displayLikeCount(null,null))
        assertEquals("—",displayLikeCount(null,-1))
    }
    @Test fun freshAccountCountWinsOverAnonymousSnapshot() {
        assertEquals("12.4K",displayLikeCount("12.4K",12_345))
    }
}
