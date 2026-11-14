package dev.quietube.ui

import org.junit.Assert.*
import org.junit.Test

class VideoGesturesTest {
    @Test fun thirdsSplitTheVideo() {
        assertEquals(-1, videoThird(0f, 900f))
        assertEquals(-1, videoThird(299f, 900f))
        assertEquals(0, videoThird(450f, 900f))
        assertEquals(1, videoThird(601f, 900f))
        assertEquals(0, videoThird(10f, 0f))
    }
    @Test fun repeatedTapsOnOneSideAddUp() {
        var feedback: SeekFeedback? = null
        feedback = feedback.next(1, false)
        assertEquals(SeekFeedback(1, 10, 1), feedback)
        feedback = feedback.next(1, true)
        feedback = feedback.next(1, true)
        assertEquals(SeekFeedback(1, 30, 3), feedback)
        feedback = feedback.next(-1, true)
        assertEquals("Switching sides starts again", SeekFeedback(-1, 10, 4), feedback)
        feedback = feedback.next(-1, false)
        assertEquals("A fresh double tap starts again", SeekFeedback(-1, 10, 5), feedback)
    }
    @Test fun seeksStayInsideTheVideo() {
        assertEquals(20_000L, seekTarget(10_000, 60_000, 10_000))
        assertEquals(0L, seekTarget(4_000, 60_000, -10_000))
        assertEquals(60_000L, seekTarget(55_000, 60_000, 10_000))
        assertEquals("Unknown duration only clamps at zero", 75_000L, seekTarget(65_000, -1, 10_000))
    }
    @Test fun scrubPreviewTracksTheTimeBarWithoutLeavingTheVideo() {
        val span = TimeBarSpan(left = 40f, right = 1040f, top = 500f)
        assertEquals(40f, scrubX(span, 0, 100_000), 0f)
        assertEquals(540f, scrubX(span, 50_000, 100_000), 0f)
        assertEquals(1040f, scrubX(span, 200_000, 100_000), 0f)
        assertEquals(40f, scrubX(span, 50_000, 0), 0f)
        assertEquals(0f, previewLeft(40f, 420f, 1080f), 0f)
        assertEquals(330f, previewLeft(540f, 420f, 1080f), 0f)
        assertEquals(660f, previewLeft(1040f, 420f, 1080f), 0f)
        assertEquals(0f, previewLeft(100f, 2000f, 1080f), 0f)
    }
    @Test fun ambientGlowNeedsEveryCondition() {
        assertTrue(ambientAllowed(enabled = true, dark = true, videoOnly = false, sdk = 31, powerSave = false))
        assertFalse(ambientAllowed(enabled = false, dark = true, videoOnly = false, sdk = 37, powerSave = false))
        assertFalse(ambientAllowed(enabled = true, dark = false, videoOnly = false, sdk = 37, powerSave = false))
        assertFalse(ambientAllowed(enabled = true, dark = true, videoOnly = true, sdk = 37, powerSave = false))
        assertFalse(ambientAllowed(enabled = true, dark = true, videoOnly = false, sdk = 30, powerSave = false))
        assertFalse(ambientAllowed(enabled = true, dark = true, videoOnly = false, sdk = 37, powerSave = true))
    }
    @Test fun cardsAndPlayerMatchByVideoId() {
        assertEquals("abcdefghijk", videoId("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk", videoId("https://youtu.be/abcdefghijk?t=3"))
        assertEquals("abcdefghijk", videoId("https://www.youtube.com/shorts/abcdefghijk"))
        assertTrue(sameVideo("https://www.youtube.com/watch?v=abcdefghijk", "https://m.youtube.com/watch?list=PL1&v=abcdefghijk"))
        assertFalse(sameVideo("https://www.youtube.com/watch?v=abcdefghijk", "https://www.youtube.com/watch?v=bbcdefghijk"))
        assertFalse(sameVideo(null, "https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals(sharedThumbnailKey("https://youtu.be/abcdefghijk"), sharedThumbnailKey("https://www.youtube.com/watch?v=abcdefghijk"))
    }
}
