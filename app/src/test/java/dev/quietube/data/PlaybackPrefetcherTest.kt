package dev.quietube.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPrefetcherTest {
    private fun details(url: String) = PlaybackDetails(Video(url,url,"Channel",null,60,1,null),emptyList(),emptyList())
    private class Source(val handler: suspend (String) -> Outcome<PlaybackDetails>) : AnonymousSource {
        val calls = mutableListOf<String>()
        override suspend fun playback(url: String): Outcome<PlaybackDetails> { calls += url; return handler(url) }
        override suspend fun playlist(id: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun search(query: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
    }
    private var now = 0L
    private var wifi = true
    // Workers run in backgroundScope, which runCurrent() drives but advanceUntilIdle() does not.
    private fun TestScope.prefetcher(source: Source) = PlaybackPrefetcher(source,{ wifi },backgroundScope) { now }

    @Test fun tapReusesInFlightPrefetch() = runTest {
        val source = Source { delay(1_000); Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a")); runCurrent()
        assertEquals(listOf("a"),source.calls)
        assertEquals("a",(cache.get("a") as Outcome.Success).value.video.url)
        assertEquals(listOf("a"),source.calls)
    }
    @Test fun nothingIsPrefetchedOffWifiButTapsStillResolve() = runTest {
        wifi = false
        val source = Source { Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a","b")); runCurrent()
        assertTrue(source.calls.isEmpty())
        assertTrue(cache.get("a") is Outcome.Success)
        assertEquals(listOf("a"),source.calls)
    }
    @Test fun resolvesTwoAtATimeAndDropsVideosScrolledAway() = runTest {
        val release = CompletableDeferred<Unit>()
        val source = Source { release.await(); Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a","b","c","d")); runCurrent()
        assertEquals(listOf("a","b"),source.calls)
        cache.visible(listOf("e")); release.complete(Unit); runCurrent()
        assertEquals(listOf("a","b","e"),source.calls)
    }
    @Test fun failedPrefetchIsNotCachedAndTapRetries() = runTest {
        var fail = true
        val source = Source { if (fail) { fail = false; Outcome.Failure(VideoError.Network) } else Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a")); runCurrent()
        assertTrue(cache.get("a") is Outcome.Success)
        assertEquals(listOf("a","a"),source.calls)
    }
    @Test fun tapSharingAFailingPrefetchGetsItsOwnAttempt() = runTest {
        var fail = true
        val source = Source { delay(100); if (fail) { fail = false; Outcome.Failure(VideoError.Network) } else Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a")); runCurrent()
        assertTrue(cache.get("a") is Outcome.Success)
    }
    @Test fun resultsExpireAndCanBeInvalidated() = runTest {
        val source = Source { Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.get("a"); cache.get("a")
        assertEquals(1,source.calls.size)
        cache.invalidate("a"); cache.get("a")
        assertEquals(2,source.calls.size)
        now += PlaybackPrefetcher.TTL_MS; cache.get("a")
        assertEquals(3,source.calls.size)
    }
    @Test fun rateLimitingPausesPrefetchButNotTaps() = runTest {
        val source = Source { if (it == "a") Outcome.Failure(VideoError.RateLimited) else Outcome.Success(details(it)) }
        val cache = prefetcher(source)
        cache.visible(listOf("a")); runCurrent()
        cache.visible(listOf("b")); runCurrent()
        assertEquals(listOf("a"),source.calls)
        assertTrue(cache.get("c") is Outcome.Success)
        now += PlaybackPrefetcher.RATE_LIMIT_PAUSE_MS
        cache.visible(listOf("b")); runCurrent()
        assertEquals(listOf("a","c","b"),source.calls)
    }
}
