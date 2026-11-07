package dev.quietube.data

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException

class ExtractorDownloaderTest {
    @Test fun `post preserves body and headers and closes response`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("result"))
            val downloader = ExtractorDownloader(OkHttpClient())
            val request = Request.newBuilder().post(server.url("/search").toString(), "query".toByteArray())
                .headers(mapOf("Content-Type" to listOf("application/json"), "X-Test" to listOf("one", "two"))).build()
            assertEquals("result", downloader.execute(request).responseBody())
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("query", recorded.body.readUtf8())
            assertEquals(listOf("one", "two"), recorded.headers.values("X-Test"))
            assertEquals(ExtractorDownloader.USER_AGENT, recorded.getHeader("User-Agent"))
            assertNull(recorded.getHeader("Cookie"))
            assertNull(recorded.getHeader("Authorization"))
        }
    }
    @Test fun `rate limit has a useful typed error`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            try {
                ExtractorDownloader(OkHttpClient()).get(server.url("/").toString())
                fail("Expected HTTP failure")
            } catch (e: HttpFailure) { assertEquals(VideoError.RateLimited, mapExtractorError(e)) }
        }
    }
    @Test fun `empty search is different from a broken renderer`() {
        val empty = org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException("no results")
        assertNull(searchPageError(listOf(empty)))
        assertEquals(VideoError.ExtractionBroken, searchPageError(listOf(empty, ParsingException("unknown renderer"))))
        assertEquals(VideoError.Network, searchPageError(listOf(IOException("offline"))))
    }
    @Test fun `errors distinguish restrictions outages and extractor breakage`() {
        assertEquals(VideoError.AgeRestricted, mapExtractorError(AgeRestrictedContentException("restricted")))
        assertEquals(VideoError.ContentUnavailable, mapExtractorError(ContentNotAvailableException("removed")))
        assertEquals(VideoError.Network, mapExtractorError(IOException("offline")))
        assertEquals(VideoError.ExtractionBroken, mapExtractorError(ParsingException("schema changed")))
    }
}
