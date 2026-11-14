package dev.quietube.data

import dev.quietube.data.SegmentCategory.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockTest {
    private val id = "abcdefghijk"
    private fun segment(category: SegmentCategory, start: Long, end: Long) = SkipSegment(category, start, end)

    @Test fun onlyAFourCharacterHashPrefixIdentifiesTheVideo() {
        assertEquals("ca2f", hashPrefix(id))
        assertEquals("65ac", hashPrefix("bbcdefghijk"))
    }
    @Test fun keepsOnlyThisVideosWellFormedSkipSegments() {
        val json = """[
            {"videoID":"$id","hash":"ca2f","segments":[
              {"segment":[30.5,60.0],"category":"selfpromo","actionType":"skip","UUID":"u1","votes":3,"locked":0},
              {"segment":[10.0,20.25],"category":"sponsor","actionType":"skip","UUID":"u2"},
              {"segment":[10.0,20.25],"category":"sponsor","actionType":"skip","UUID":"u2"},
              {"segment":[5.0,6.0],"category":"music_offtopic","actionType":"skip"},
              {"segment":[7.0,9.0],"category":"sponsor","actionType":"mute"},
              {"segment":[8.0,8.2],"category":"intro","actionType":"skip"},
              {"segment":[-1.0,4.0],"category":"intro","actionType":"skip"},
              {"segment":[1.0],"category":"intro","actionType":"skip"}]},
            {"videoID":"zzzzzzzzzzz","segments":[{"segment":[0,100],"category":"sponsor","actionType":"skip"}]}]"""
        assertEquals(listOf(segment(Sponsor, 10_000, 20_250), segment(SelfPromo, 30_500, 60_000)), parseSegments(json, id))
        assertEquals(emptyList<SkipSegment>(), parseSegments("[]", id))
    }
    @Test fun skipsOnlyWhenPlaybackRunsIntoASegment() {
        val list = listOf(segment(Sponsor, 10_000, 20_000), segment(SelfPromo, 30_000, 40_000))
        val skip = setOf(Sponsor)
        assertEquals(list[0], segmentToSkip(list, skip, 9_900, 10_100, emptySet()))
        assertEquals("Starting at the segment counts", list[0], segmentToSkip(list, skip, 10_000, 10_000, emptySet()))
        assertNull("A seek into the middle resets the previous position", segmentToSkip(list, skip, 15_000, 15_200, emptySet()))
        assertNull("Show-only categories never skip", segmentToSkip(list, skip, 29_900, 30_100, emptySet()))
        assertNull("Undone segments play", segmentToSkip(list, skip, 9_900, 10_100, setOf(list[0])))
        assertNull("Not at the very end", segmentToSkip(list, skip, 9_000, 19_800, emptySet()))
    }
    @Test fun aSkipLandsPastTouchingSkippedSegments() {
        val list = listOf(segment(Sponsor, 0, 10_000), segment(Intro, 10_100, 15_000), segment(Sponsor, 14_000, 18_000), segment(Sponsor, 30_000, 40_000))
        assertEquals(18_000L, skipEnd(list[0], list, setOf(Sponsor, Intro), emptySet()))
        assertEquals("Intro shown only: stop before it", 10_000L, skipEnd(list[0], list, setOf(Sponsor), emptySet()))
    }
    @Test fun showOnlySegmentsOfferAManualSkip() {
        val list = listOf(segment(Sponsor, 10_000, 20_000), segment(SelfPromo, 30_000, 40_000))
        assertEquals(list[1], manualSegment(list, setOf(Sponsor), 35_000))
        assertNull(manualSegment(list, setOf(Sponsor), 15_000))
        assertNull("Not in its last second", manualSegment(list, setOf(Sponsor), 39_500))
    }
    @Test fun defaultsSkipSponsorsShowTheRestAndLeaveFillerOff() {
        val settings = ThirdPartySettings(sponsorBlock = true)
        assertEquals(setOf(Sponsor), settings.skipped)
        assertEquals(SegmentCategory.entries.toSet() - Filler, settings.marked)
        assertEquals(emptySet<SegmentCategory>(), ThirdPartySettings().skipped)
        assertEquals(emptySet<SegmentCategory>(), ThirdPartySettings(modes = mapOf(Intro to SegmentMode.Skip)).marked)
        assertEquals(setOf(Sponsor, Intro), ThirdPartySettings(true, mapOf(Intro to SegmentMode.Skip)).skipped)
    }
    @Test fun requestsSendOnlyTheHashPrefixWithoutCookiesAndCacheTheAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"videoID":"$id","segments":[{"segment":[1,5],"category":"sponsor","actionType":"skip"}]}]"""))
            val client = SponsorBlock(server.url("/"))
            assertEquals(listOf(segment(Sponsor, 1_000, 5_000)), client.segments(id))
            assertEquals(listOf(segment(Sponsor, 1_000, 5_000)), client.segments(id))
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/api/skipSegments/ca2f", request.requestUrl!!.encodedPath)
            assertEquals(SegmentCategory.entries.map { it.key }, request.requestUrl!!.queryParameterValues("category"))
            assertEquals(listOf("skip"), request.requestUrl!!.queryParameterValues("actionType"))
            assertFalse(request.path!!.contains(id))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun nothingKnownIsEmptyButFailuresAreNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
            server.enqueue(MockResponse().setBody("[" + " ".repeat(600 * 1024) + "]"))
            server.enqueue(MockResponse().setBody("not json"))
            val client = SponsorBlock(server.url("/"))
            assertEquals(emptyList<SkipSegment>(), client.segments(id))
            assertNull("Redirects aren't followed", client.segments("bbcdefghijk"))
            assertNull("Oversized bodies are refused", client.segments("cbcdefghijk"))
            assertNull(client.segments("dbcdefghijk"))
            assertEquals(4, server.requestCount)
        }
    }
}
