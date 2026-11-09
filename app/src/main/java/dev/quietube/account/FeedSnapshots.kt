package dev.quietube.account

import dev.quietube.data.AccountFeed
import dev.quietube.data.Continuation

internal data class FeedSnapshot(val state: FeedState, val cursor: Continuation?, val failedAppend: Boolean)
internal class FeedSnapshots {
    private val values = mutableMapOf<Pair<AccountFeed, String?>, FeedSnapshot>()
    fun save(state: FeedState, cursor: Continuation?, failedAppend: Boolean) {
        if (!state.initialized) return
        if ((state.loading || state.refreshing) && state.videos.isEmpty() && state.playlists.isEmpty()) return
        values[state.feed to state.playlist?.id] = FeedSnapshot(state.copy(loading = false, refreshing = false, loadingMore = false), cursor, failedAppend)
    }
    fun get(feed: AccountFeed, playlistId: String? = null): FeedSnapshot? = values[feed to playlistId]
    fun clear() = values.clear()
}
