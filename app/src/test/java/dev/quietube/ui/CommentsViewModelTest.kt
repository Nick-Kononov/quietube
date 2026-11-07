package dev.quietube.ui

import dev.quietube.data.*
import dev.quietube.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun video(id: String) = Video(id,id,"Channel",null,60,10,null)
    private fun comment(id: String) = Comment(id,"Author",null,id,null,0)
    private class Repo(val handler: suspend (String,Continuation?) -> Outcome<CommentPage>) : VideoRepository {
        override suspend fun comments(url: String,continuation: Continuation?) = handler(url,continuation)
        override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Unused")
        override suspend fun search(query: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun feed(feed: AccountFeed,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun playlist(id: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
    }
    @Test fun changingVideoCancelsStaleCommentsEvenIfSourceFinishesLate() = runTest(dispatcher) {
        val store = PlaybackStateStore()
        val vm = CommentsViewModel(Repo { url,_ ->
            withContext(NonCancellable) { delay(if (url == "old") 200 else 10) }
            Outcome.Success(CommentPage(listOf(comment(url))))
        },store)
        store.mutable.value = PlaybackState(video = video("old")); runCurrent()
        store.mutable.value = PlaybackState(video = video("new")); advanceUntilIdle()
        assertEquals("new",vm.state.value.url)
        assertEquals(listOf("new"),vm.state.value.comments.map { it.id })
    }
    @Test fun commentsWaitForStreamResolutionAndDoNotReloadOnRetry() = runTest(dispatcher) {
        val store = PlaybackStateStore(); var calls = 0
        val vm = CommentsViewModel(Repo { url,_ -> calls++; Outcome.Success(CommentPage(listOf(comment(url)))) },store)
        store.mutable.value = PlaybackState(video = video("v"),resolving = true); advanceUntilIdle()
        assertEquals(0,calls); assertTrue(vm.state.value.loading)
        store.mutable.value = PlaybackState(video = video("v")); advanceUntilIdle()
        assertEquals(1,calls); assertEquals(listOf("v"),vm.state.value.comments.map { it.id })
        // Re-extraction after an expired stream URL resolves the same video again.
        store.mutable.value = PlaybackState(video = video("v"),resolving = true); advanceUntilIdle()
        store.mutable.value = PlaybackState(video = video("v")); advanceUntilIdle()
        assertEquals(1,calls)
    }
    @Test fun failedPaginationKeepsCommentsAndRetriesSameCursor() = runTest(dispatcher) {
        val cursor = object : Continuation {}
        var calls = 0
        val store = PlaybackStateStore()
        val vm = CommentsViewModel(Repo { _,next ->
            calls++
            if (next == null) Outcome.Success(CommentPage(listOf(comment("one")),cursor,total = 50))
            else {
                assertSame(cursor,next)
                if (calls == 2) Outcome.Failure(VideoError.Network)
                else Outcome.Success(CommentPage(listOf(comment("one"),comment("two"))))
            }
        },store)
        store.mutable.value = PlaybackState(video = video("v")); advanceUntilIdle()
        vm.more(); advanceUntilIdle()
        assertEquals(VideoError.Network,vm.state.value.error)
        assertEquals(1,vm.state.value.comments.size)
        vm.more(); advanceUntilIdle()
        assertEquals(listOf("one","two"),vm.state.value.comments.map { it.id })
        assertEquals(50,vm.state.value.total); assertFalse(vm.state.value.hasMore)
    }
    @Test fun disabledCommentsAreDifferentFromEmptyAndRefreshable() = runTest(dispatcher) {
        val store = PlaybackStateStore(); var calls = 0
        val vm = CommentsViewModel(Repo { _,_ -> calls++; Outcome.Success(CommentPage(emptyList(),disabled = calls == 1)) },store)
        store.mutable.value = PlaybackState(video = video("v")); advanceUntilIdle()
        assertTrue(vm.state.value.disabled)
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.disabled); assertNull(vm.state.value.error)
    }
    @Test fun clearingPlaybackClearsCommentsAndFurtherPaging() = runTest(dispatcher) {
        val store = PlaybackStateStore()
        val vm = CommentsViewModel(Repo { _,_ -> Outcome.Success(CommentPage(listOf(comment("one")),object : Continuation {})) },store)
        store.mutable.value = PlaybackState(video = video("v")); advanceUntilIdle()
        store.mutable.value = PlaybackState(); advanceUntilIdle()
        assertTrue(vm.state.value.comments.isEmpty()); assertNull(vm.state.value.url); assertFalse(vm.state.value.hasMore)
    }
}
