package dev.quietube.links

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinksTest {
    private val id = "aqz-KE-bpKQ"
    private val canonical = "https://www.youtube.com/watch?v=$id"
    @Test fun supportedFormsCanonicalizeAndDiscardTracking() {
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com")) {
            assertEquals(canonical, parseYouTubeLink("https://$host/watch?v=$id&si=tracking"))
            for (path in listOf("shorts", "live")) assertEquals(canonical, parseYouTubeLink("https://$host/$path/$id"))
        }
        assertEquals(canonical, parseYouTubeLink("https://youtu.be/$id?si=tracking"))
        assertEquals(canonical, parseYouTubeLink("HTTPS://WWW.YOUTUBE.COM/watch?v=$id"))
    }
    @Test fun timestampsAreNormalizedIncludingZero() {
        for ((raw, seconds) in listOf("90" to 90L, "1m30s" to 90L, "1h2m3s" to 3723L, "0" to 0L)) {
            for (key in listOf("t", "start")) {
                val parsed = parseYouTubeLink("$canonical&$key=$raw")
                assertEquals("$canonical&t=$seconds", parsed)
                assertEquals(seconds * 1000, linkStartMs(parsed!!))
            }
        }
        assertEquals("$canonical&t=90", parseYouTubeLink("https://youtu.be/$id#t=1m30s"))
        assertNull(linkStartMs(canonical))
    }
    @Test fun rejectsAmbiguousOrMaliciousLinks() {
        for (url in listOf("http://youtu.be/$id", "javascript:$canonical", "https://youtube.com.evil.com/watch?v=$id",
            "https://youtube.com@evil.com/watch?v=$id", "https://evil.com@youtube.com/watch?v=$id", "https://youtube.com:443/watch?v=$id",
            "https://youtube.com/playlist?list=abc", "https://youtu.be/${id}X", "https://youtu.be/$id/extra",
            "$canonical&v=$id", "$canonical&t=-1", "$canonical&t=nope", "$canonical&t=1&start=2",
            "$canonical&t=99999999999999999999", "$canonical&t=99999999999999999999h", "https://youtu.be/%61qz-KE-bpKQ",
            "https://youtube.com/watch?v=short", "https://youtube.com/watch?v=aqz-KE-bpK!", "$canonical&t=%ZZ")) {
            assertNull(url, parseYouTubeLink(url))
        }
    }
    @Test fun sharingFindsFirstValidYouTubeVideo() {
        assertEquals("$canonical&t=90", sharedYouTubeLink("Watch this: (https://youtu.be/$id?t=90). Some text $canonical"))
        assertEquals(canonical, sharedYouTubeLink("https://evil.com/watch?v=$id\n$canonical"))
        assertNull(sharedYouTubeLink("https://youtube.com.evil.com/watch?v=$id"))
        assertNull(sharedYouTubeLink("No video here"))
    }
}
