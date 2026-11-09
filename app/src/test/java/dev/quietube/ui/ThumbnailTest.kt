package dev.quietube.ui

import org.junit.Assert.*
import org.junit.Test

class ThumbnailTest {
    @Test fun watchAndShortsUrlsMapToTheHighResThumbnail() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hq720.jpg",highResThumbnail("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("https://i.ytimg.com/vi/abc-_fghijk/hq720.jpg",highResThumbnail("https://www.youtube.com/watch?list=PL1&v=abc-_fghijk"))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hq720.jpg",highResThumbnail("https://www.youtube.com/shorts/abcdefghijk"))
    }
    @Test fun urlsWithoutAVideoIdKeepTheFeedThumbnail() {
        assertNull(highResThumbnail("https://www.youtube.com/playlist?list=PLfixture"))
        assertNull(highResThumbnail(""))
    }
}
