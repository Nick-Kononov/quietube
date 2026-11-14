package dev.quietube.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DeArrowTest {
    private val id = "abcdefghijk"
    private fun branding(vararg titles: String) = """{"$id":{"titles":[${titles.joinToString(",")}],"thumbnails":[],"randomTime":0.3,"videoDuration":100},
        "zzzzzzzzzzz":{"titles":[{"title":"Someone else's","original":false,"votes":5,"locked":false,"UUID":"x"}]}}"""

    @Test fun usesTheFirstTitleOnlyWhenTrusted() {
        assertEquals("A calm title", parseBrandingTitle(branding("""{"title":"A calm title","original":false,"votes":0,"locked":false,"UUID":"a"}"""), id))
        assertNull("Voted down", parseBrandingTitle(branding("""{"title":"Unvetted","original":false,"votes":-1,"locked":false,"UUID":"a"}"""), id))
        assertEquals("Locked by a moderator", parseBrandingTitle(branding("""{"title":"Locked by a moderator","original":false,"votes":-2,"locked":true,"UUID":"a"}"""), id))
        assertNull("The original title won", parseBrandingTitle(branding("""{"title":"Original","original":true,"votes":4,"locked":false,"UUID":"a"}"""), id))
        assertNull(parseBrandingTitle(branding(), id))
        assertNull("Another video's entry", parseBrandingTitle(branding(), "yyyyyyyyyyy"))
        assertNull(parseBrandingTitle("{}", id))
    }
    @Test fun dropsKeepFormattingMarkers() {
        assertEquals("How the iPhone 15 got USB-C", cleanTitle("How the >iPhone 15 got >USB-C"))
        assertEquals("A > B", cleanTitle("A > B"))
        assertNull(cleanTitle("  \u0007 "))
        assertEquals(300, cleanTitle("x".repeat(400))!!.length)
    }
    @Test fun fetchesByHashPrefixAndCachesNegatives() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(branding("""{"title":"A calm title","original":false,"votes":1,"locked":false,"UUID":"a"}""")))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = DeArrow(server.url("/"))
            assertEquals("A calm title", client.title(id))
            assertNull(client.title("bbcdefghijk"))
            assertNull(client.title("bbcdefghijk"))
            assertEquals(2, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/api/branding/ca2f", request.path)
            assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun titlesAreNeverRequestedWhileDeArrowIsOff() = runBlocking {
        MockWebServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val titles = TitleReplacements(DeArrow(server.url("/")), flowOf(false), scope)
            titles.request(id)
            Thread.sleep(200)
            assertEquals(0, server.requestCount)
            scope.cancel()

            server.enqueue(MockResponse().setBody(branding("""{"title":"A calm title","original":false,"votes":1,"locked":false,"UUID":"a"}""")))
            val onScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val on = TitleReplacements(DeArrow(server.url("/")), flowOf(true), onScope)
            withTimeout(2_000) { on.enabled.first { it } }
            on.request(id)
            assertEquals("A calm title", withTimeout(5_000) { on.titles.first { it.isNotEmpty() } }[id])
            assertEquals(1, server.requestCount)
            onScope.cancel()
        }
    }
}
