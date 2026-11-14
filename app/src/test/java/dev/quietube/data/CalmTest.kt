package dev.quietube.data

import dev.quietube.links.shareableLink
import dev.quietube.playback.shortcutLongLabel
import dev.quietube.playback.shortcutShortLabel
import org.junit.Assert.*
import org.junit.Test

class CalmTest {
    private fun video(id: String, resume: Long? = null, live: Boolean = false, short: Boolean = false) =
        Video("https://www.youtube.com/watch?v=$id", "Title $id", "Channel", null, 60, 10, null, isLive = live, resumePositionMs = resume, isShort = short)

    @Test fun caughtUpSitsAboveTheNewestWatchedVideo() {
        assertEquals(2, caughtUpIndex(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"), video("ccccccccccc", 0), video("ddddddddddd"), video("eeeeeeeeeee", 5_000))))
        assertEquals("Everything watched", 0, caughtUpIndex(listOf(video("aaaaaaaaaaa", 1), video("bbbbbbbbbbb"))))
        assertNull("Nothing watched: no marker", caughtUpIndex(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"))))
        assertNull(caughtUpIndex(emptyList()))
    }
    @Test fun autoplayFollowsThePlaylistAndStopsAtItsEnd() {
        val related = listOf(video("aaaaaaaaaaa", live = true), video("bbbbbbbbbbb", short = true), video("ccccccccccc"), video("ddddddddddd"))
        assertEquals("https://www.youtube.com/watch?v=xxxxxxxxxxx", autoplayNext(listOf("https://www.youtube.com/watch?v=xxxxxxxxxxx"), related, null))
        assertNull("Last playlist video: stop, don't drift into related", autoplayNext(emptyList(), related, null))
        assertEquals("Skips live streams and Shorts", related[2].url, autoplayNext(null, related, null))
        assertEquals("Never the same video again", related[3].url, autoplayNext(null, related, related[2].url))
        assertNull(autoplayNext(null, emptyList(), null))
    }
    @Test fun sharedLinksCarryOnlyTheVideo() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", shareableLink("https://m.youtube.com/watch?list=PL1&v=abcdefghijk&t=30&si=tracking"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", shareableLink("https://youtu.be/abcdefghijk?si=x"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", shareableLink("https://www.youtube.com/shorts/abcdefghijk"))
        assertNull(shareableLink("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
    }
    @Test fun shortcutLabelsFitTheLauncher() {
        assertEquals("Cooking pasta", shortcutShortLabel("Cooking pasta"))
        assertTrue(shortcutShortLabel("A very long video title that goes on and on").length <= 25)
        assertTrue(shortcutShortLabel("A very long video title that goes on and on").endsWith("…"))
        assertEquals("Continue watching Cooking pasta", shortcutLongLabel("Cooking pasta"))
        assertTrue(shortcutLongLabel("x".repeat(100)).length <= 60)
    }
}
