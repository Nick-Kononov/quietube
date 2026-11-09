package dev.quietube.account

import dev.quietube.data.*
import org.junit.Assert.*
import org.junit.Test

class FeedSnapshotsTest {
    private val video = Video("url", "Title", "Channel", null, 100, 1, null)
    @Test fun separateFeedsRestoreTheirOwnItemsCursorAndRetryMode() {
        val cache = FeedSnapshots(); val cursor = object : Continuation {}
        cache.save(FeedState(feed = AccountFeed.Home, videos = listOf(video), loadingMore = true, hasMore = true), cursor, true)
        cache.save(FeedState(feed = AccountFeed.Subscriptions, videos = listOf(video.copy(title = "Subscription"))), null, false)
        val home = cache.get(AccountFeed.Home)!!
        assertEquals(listOf(video), home.state.videos); assertSame(cursor, home.cursor); assertTrue(home.failedAppend)
        assertFalse(home.state.loadingMore)
        assertEquals("Subscription", cache.get(AccountFeed.Subscriptions)!!.state.videos.single().title)
    }
    @Test fun playlistBackRestoresLibraryAndUnfinishedInitialLoadsAreNotCached() {
        val cache = FeedSnapshots(); val playlist = PlaylistSummary("id", "Playlist", null, "1 video")
        cache.save(FeedState(feed = AccountFeed.Playlists, playlists = listOf(playlist)), null, false)
        cache.save(FeedState(feed = AccountFeed.Playlists, playlist = playlist, videos = listOf(video)), null, false)
        cache.save(FeedState(feed = AccountFeed.Home, loading = true), null, false)
        assertEquals(listOf(playlist), cache.get(AccountFeed.Playlists)!!.state.playlists)
        assertEquals(listOf(video), cache.get(AccountFeed.Playlists, "id")!!.state.videos)
        assertNull(cache.get(AccountFeed.Home))
        cache.clear(); assertNull(cache.get(AccountFeed.Playlists)); assertNull(cache.get(AccountFeed.Playlists, "id"))
    }
}
