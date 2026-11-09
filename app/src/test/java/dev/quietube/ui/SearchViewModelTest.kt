package dev.quietube.ui

import androidx.lifecycle.SavedStateHandle
import dev.quietube.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun video(id: String) = Video("https://www.youtube.com/watch?v=$id", id, "Channel", null, 30, 5, null)

    @Test fun `new search cancels earlier results`() = runTest(dispatcher) {
        val repo = object : VideoRepository {
            override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Not used by search")
            override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> {
                delay(if (query == "old") 200 else 10)
                return Outcome.Success(VideoPage(listOf(video(query))))
            }
        }
        val vm = SearchViewModel(repo, SavedStateHandle(), FakeRecentSearches())
        vm.search("old")
        runCurrent()
        vm.search("new")
        advanceUntilIdle()
        assertEquals(listOf("new"), vm.state.value.videos.map { it.title })
        assertFalse(vm.state.value.loading)
    }
    @Test fun `pagination error preserves videos and retry uses same cursor`() = runTest(dispatcher) {
        val cursor = object : Continuation {}
        var calls = 0
        val repo = object : VideoRepository {
            override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Not used by search")
            override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> {
                calls++
                if (continuation == null) return Outcome.Success(VideoPage(listOf(video("one")), cursor))
                assertSame(cursor, continuation)
                if (calls == 2) return Outcome.Failure(VideoError.Network)
                return Outcome.Success(VideoPage(listOf(video("one"), video("two"))))
            }
        }
        val vm = SearchViewModel(repo, SavedStateHandle(), FakeRecentSearches())
        vm.search("kotlin"); advanceUntilIdle()
        vm.more(); advanceUntilIdle()
        assertEquals(1, vm.state.value.videos.size)
        assertEquals(VideoError.Network, vm.state.value.error)
        vm.more(); advanceUntilIdle()
        assertEquals(listOf("one", "two"), vm.state.value.videos.map { it.title })
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.hasMore)
    }
    @Test fun `restored query reloads and blank search makes no request`() = runTest(dispatcher) {
        var calls = 0
        val repo = object : VideoRepository {
            override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
            override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Not used by search")
            override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> {
                calls++
                return Outcome.Success(VideoPage(emptyList()))
            }
        }
        val vm = SearchViewModel(repo, SavedStateHandle(mapOf("query" to "saved")), FakeRecentSearches())
        advanceUntilIdle()
        assertEquals("saved", vm.state.value.query)
        vm.search("   "); advanceUntilIdle()
        assertEquals(1, calls)
    }
    @Test fun `search records history but a restored query does not resurrect cleared history`() = runTest(dispatcher) {
        val history = FakeRecentSearches()
        val repo = emptyRepository()
        val saved = SavedStateHandle()
        val vm = SearchViewModel(repo, saved, history)
        vm.search("  Kotlin  "); advanceUntilIdle()
        assertEquals(listOf("Kotlin"), history.queries.value)
        vm.clearHistory(); advanceUntilIdle()
        assertTrue(history.queries.value.isEmpty())
        SearchViewModel(repo, SavedStateHandle(mapOf("query" to "Kotlin")), history)
        advanceUntilIdle()
        assertTrue(history.queries.value.isEmpty())
    }
    @Test fun `unsubmitted draft survives restoration without a network request`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = SearchViewModel(emptyRepository(), saved, FakeRecentSearches())
        vm.edit("a draft")
        assertEquals("a draft", saved.get<String>("draft"))
        val restored = SearchViewModel(emptyRepository(), SavedStateHandle(mapOf("draft" to "a draft")), FakeRecentSearches())
        advanceUntilIdle()
        assertEquals("a draft", restored.state.value.draft)
        assertFalse(restored.state.value.searched)
    }
    private fun emptyRepository() = object : VideoRepository {
        override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
        override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = error("Not used")
        override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Not used")
        override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> = Outcome.Success(VideoPage(emptyList()))
    }

}
