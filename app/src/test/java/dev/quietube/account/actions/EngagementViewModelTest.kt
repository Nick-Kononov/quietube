package dev.quietube.account.actions

import dev.quietube.account.SessionStatus
import dev.quietube.data.*
import dev.quietube.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = MutableStateFlow(SessionStatus(true,true,1))
    private val playback = PlaybackStateStore()
    private val preferences = MutableStateFlow(SyncSettings())
    private val repo = Repository()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun video(id: String) = Video("https://www.youtube.com/watch?v=$id",id,"Channel",null,60,0,null)
    private suspend fun TestScope.model(): EngagementViewModel {
        playback.mutable.value = PlaybackState(video = video("aaaaaaaaaaa"))
        val vm = EngagementViewModel(repo,sessions,playback,preferences,{ null },{}, {})
        runCurrent()
        return vm
    }
    @Test fun `rating updates synchronously and accepted response keeps it`() = runTest(dispatcher) {
        val vm = model()
        vm.rate(Rating.Like)
        assertTrue(vm.state.value.busy)
        assertEquals(Rating.Like,vm.state.value.info.rating)
        assertEquals("11",vm.state.value.info.likes)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(Rating.Like,vm.state.value.info.rating)
        assertEquals(listOf(Rating.Like to Rating.None),repo.ratings)
    }
    @Test fun `failure restores exact state and retry sends intended rating once`() = runTest(dispatcher) {
        val vm = model()
        val before = vm.state.value.info
        repo.result = Outcome.Failure(VideoError.Network)
        vm.rate(Rating.Like); advanceUntilIdle()
        assertEquals(before,vm.state.value.info)
        assertEquals("Couldn't update rating.",vm.state.value.message)
        assertEquals("Retry",vm.state.value.messageAction)
        val id = vm.state.value.messageId
        vm.consumeMessage(vm.state.value.message!!)
        repo.result = Outcome.Success(Unit)
        vm.messageAction(id); vm.messageAction(id); advanceUntilIdle()
        assertEquals(2,repo.ratings.size)
        assertEquals(Rating.Like,vm.state.value.info.rating)
    }
    @Test fun `active rating removes it and count never goes negative`() = runTest(dispatcher) {
        repo.info = repo.info.copy(rating = Rating.Like,likes = "0")
        val vm = model()
        vm.rate(Rating.Like)
        assertEquals(Rating.None,vm.state.value.info.rating)
        assertEquals("0",vm.state.value.info.likes)
        advanceUntilIdle()
        assertEquals(Rating.None to Rating.Like,repo.ratings.single())
    }
    @Test fun `switching from like to dislike removes one like and preserves estimate`() = runTest(dispatcher) {
        repo.info = repo.info.copy(rating = Rating.Like,dislikes = "123",estimatedDislikes = true)
        val vm = model()
        vm.rate(Rating.Dislike)
        assertEquals("9",vm.state.value.info.likes)
        assertEquals("123",vm.state.value.info.dislikes)
        assertTrue(vm.state.value.info.estimatedDislikes)
        advanceUntilIdle()
    }
    @Test fun `unsubscribe is optimistic and undo resubscribes once`() = runTest(dispatcher) {
        repo.info = repo.info.copy(subscribed = true)
        val vm = model()
        vm.subscribe()
        assertEquals(false,vm.state.value.info.subscribed)
        advanceUntilIdle()
        assertEquals("Unsubscribed",vm.state.value.message)
        assertEquals("Undo",vm.state.value.messageAction)
        val id = vm.state.value.messageId
        vm.messageAction(id); vm.messageAction(id)
        assertEquals(true,vm.state.value.info.subscribed)
        advanceUntilIdle()
        assertEquals(listOf(false,true),repo.subscriptions)
    }
    @Test fun `failed subscription rolls back and retry uses same target`() = runTest(dispatcher) {
        val vm = model()
        repo.result = Outcome.Failure(VideoError.Network)
        vm.subscribe(); advanceUntilIdle()
        assertEquals(false,vm.state.value.info.subscribed)
        repo.result = Outcome.Success(Unit)
        vm.messageAction(vm.state.value.messageId); advanceUntilIdle()
        assertEquals(listOf(true,true),repo.subscriptions)
        assertEquals(true,vm.state.value.info.subscribed)
    }
    @Test fun `rapid taps serialize mutations`() = runTest(dispatcher) {
        val vm = model()
        vm.rate(Rating.Like); vm.rate(Rating.Dislike); vm.subscribe()
        advanceUntilIdle()
        assertEquals(1,repo.ratings.size)
        assertTrue(repo.subscriptions.isEmpty())
    }
    @Test fun `sign out cancels pending action and invalidates snackbar`() = runTest(dispatcher) {
        val vm = model()
        repo.result = Outcome.Failure(VideoError.Network)
        vm.rate(Rating.Like); advanceUntilIdle()
        val id = vm.state.value.messageId
        sessions.value = SessionStatus(true,false,2); runCurrent()
        vm.messageAction(id); vm.rate(Rating.Like); vm.subscribe(); advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
        assertEquals(Engagement(),vm.state.value.info)
        assertEquals(1,repo.ratings.size)
    }
    @Test fun `late noncancellable mutation cannot change a new video`() = runTest(dispatcher) {
        val vm = model()
        repo.slow = true
        vm.rate(Rating.Like); runCurrent()
        playback.mutable.value = PlaybackState(video = video("bbbbbbbbbbb")); runCurrent()
        advanceUntilIdle()
        assertTrue(vm.state.value.url!!.endsWith("bbbbbbbbbbb"))
        assertEquals(Rating.None,vm.state.value.info.rating)
        assertNull(vm.state.value.message)
    }
    @Test fun `old undo cannot act on another video or account`() = runTest(dispatcher) {
        repo.info = repo.info.copy(subscribed = true)
        val vm = model()
        vm.subscribe(); advanceUntilIdle()
        val id = vm.state.value.messageId
        playback.mutable.value = PlaybackState(video = video("bbbbbbbbbbb")); runCurrent()
        vm.messageAction(id); advanceUntilIdle()
        assertEquals(listOf(false),repo.subscriptions)
    }
    @Test fun `account revision blocks taps before collector resets state`() = runTest(dispatcher) {
        val vm = model()
        sessions.value = SessionStatus(true,true,2)
        vm.rate(Rating.Like); vm.subscribe(); advanceUntilIdle()
        assertTrue(repo.ratings.isEmpty())
        assertTrue(repo.subscriptions.isEmpty())
    }
    @Test fun `one haptic event per accepted action and none for rollback or refresh`() = runTest(dispatcher) {
        val vm = model()
        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.acceptedChanges.collect { count++ } }
        repo.result = Outcome.Failure(VideoError.Network)
        vm.rate(Rating.Like); vm.rate(Rating.Dislike); advanceUntilIdle()
        assertEquals(1,count)
        vm.messageAction(vm.state.value.messageId); advanceUntilIdle()
        assertEquals(2,count)
        vm.refresh(); advanceUntilIdle()
        assertEquals(2,count)
    }
    @Test fun `formatted or missing like totals are preserved`() {
        val change = EngagementChange.Rate("url",1,Rating.Like)
        assertEquals("1.2K",change.optimistic(Engagement(likes = "1.2K")).likes)
        assertNull(change.optimistic(Engagement()).likes)
    }
    private class Repository : VideoRepository {
        var info = Engagement(Rating.None,"10",channelId = "UCabcdefghijklmnopqrstuv",subscribed = false)
        var result: Outcome<Unit> = Outcome.Success(Unit)
        var slow = false
        val ratings = mutableListOf<Pair<Rating,Rating>>()
        val subscriptions = mutableListOf<Boolean>()
        override suspend fun engagement(url: String) = Outcome.Success(info)
        override suspend fun rate(url: String,rating: Rating,previous: Rating): Outcome<Unit> {
            ratings += rating to previous
            if (slow) withContext(NonCancellable) { delay(1000) }
            return result
        }
        override suspend fun subscribe(channelId: String,subscribe: Boolean): Outcome<Unit> { subscriptions += subscribe; return result }
        override suspend fun feed(feed: AccountFeed,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun playlist(id: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun search(query: String,continuation: Continuation?): Outcome<VideoPage> = error("Unused")
        override suspend fun playback(url: String): Outcome<PlaybackDetails> = error("Unused")
    }
}
