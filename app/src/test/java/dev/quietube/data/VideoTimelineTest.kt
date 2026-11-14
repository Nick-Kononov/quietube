package dev.quietube.data

import org.junit.Assert.*
import org.junit.Test

class VideoTimelineTest {
    private val pages = listOf("https://i.ytimg.com/sb/FAKE_VIDEO/storyboard3_L2/M0.jpg?sigh=FAKE", "https://i.ytimg.com/sb/FAKE_VIDEO/storyboard3_L2/M1.jpg?sigh=FAKE")
    private val board = Storyboard(pages, frameWidth = 160, frameHeight = 90, frameCount = 40, frameMs = 2_000, columns = 5, rows = 5)

    @Test fun framesWalkRowsThenPages() {
        assertEquals(StoryboardFrame(pages[0], 0, 0, 160, 90), board.frameAt(0))
        assertEquals(StoryboardFrame(pages[0], 0, 0, 160, 90), board.frameAt(1_999))
        assertEquals(StoryboardFrame(pages[0], 160, 0, 160, 90), board.frameAt(2_000))
        assertEquals(StoryboardFrame(pages[0], 0, 90, 160, 90), board.frameAt(5 * 2_000))
        assertEquals(StoryboardFrame(pages[0], 640, 360, 160, 90), board.frameAt(24 * 2_000))
        assertEquals(StoryboardFrame(pages[1], 0, 0, 160, 90), board.frameAt(25 * 2_000))
    }
    @Test fun positionsOutsideTheVideoClampToTheEnds() {
        assertEquals(board.frameAt(0), board.frameAt(-5_000))
        // Frame 39 is the last: page 1, cell 14 → column 4, row 2.
        assertEquals(StoryboardFrame(pages[1], 640, 180, 160, 90), board.frameAt(10_000_000))
    }
    @Test fun missingPagesOrBrokenGeometryGiveNoFrame() {
        assertNull(board.copy(pages = pages.take(1)).frameAt(30 * 2_000))
        assertNull(board.copy(columns = 0).frameAt(0))
        assertNull(board.copy(frameMs = 0).frameAt(0))
        assertNull(board.copy(frameCount = 0).frameAt(0))
    }
    @Test fun choosesTheSharpestSmallLevel() {
        val tiny = board.copy(frameWidth = 48, frameHeight = 27, columns = 10, rows = 10)
        val large = board.copy(frameWidth = 320, frameHeight = 180, columns = 3, rows = 3)
        assertEquals(board, chooseStoryboard(listOf(tiny, large, board)))
        assertEquals(large, chooseStoryboard(listOf(large, large.copy(frameWidth = 640, frameHeight = 360))))
        assertNull(chooseStoryboard(emptyList()))
    }
    @Test fun levelsOutsideYouTubeImageHostsAreIgnored() {
        assertNull(chooseStoryboard(listOf(board.copy(pages = listOf("http://i.ytimg.com/sb/x.jpg")))))
        assertNull(chooseStoryboard(listOf(board.copy(pages = listOf("https://example.com/sb/x.jpg")))))
        assertNull(chooseStoryboard(listOf(board.copy(pages = listOf("https://i.ytimg.com.example.com/x.jpg")))))
        assertNull(chooseStoryboard(listOf(board.copy(pages = listOf("not a url")))))
        assertTrue(storyboardImageUrl("https://i9.ytimg.com/sb/FAKE_VIDEO/storyboard3_L1/M0.jpg"))
    }
    @Test fun chaptersAreSortedTrimmedAndDeduplicated() {
        val raw = listOf(Chapter("Outro", 90_000), Chapter(" Intro ", 0), Chapter("Middle", 30_000), Chapter("Again", 30_000),
            Chapter(" ", 40_000), Chapter("Past the end", 200_000), Chapter("Negative", -1))
        assertEquals(listOf(Chapter("Intro", 0), Chapter("Middle", 30_000), Chapter("Outro", 90_000)), normalizeChapters(raw, 120_000))
        assertEquals(emptyList<Chapter>(), normalizeChapters(listOf(Chapter("Only", 0)), 120_000))
        assertEquals(2, normalizeChapters(listOf(Chapter("A", 0), Chapter("B", 500_000)), 0).size)
    }
    @Test fun currentChapterIsTheLastOneStarted() {
        val chapters = listOf(Chapter("Intro", 0), Chapter("Middle", 30_000), Chapter("Outro", 90_000))
        assertEquals("Intro", chapters.at(0)?.title)
        assertEquals("Intro", chapters.at(29_999)?.title)
        assertEquals("Middle", chapters.at(30_000)?.title)
        assertEquals("Outro", chapters.at(1_000_000)?.title)
        assertNull(listOf(Chapter("Late", 5_000)).at(0))
    }
}
