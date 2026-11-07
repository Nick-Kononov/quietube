package dev.quietube.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.data.*
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.feedPreferences by preferencesDataStore("feed_preferences")
data class FeedState(val feed: AccountFeed = AccountFeed.Home, val playlist: PlaylistSummary? = null,
    val videos: List<Video> = emptyList(), val playlists: List<PlaylistSummary> = emptyList(),
    val loading: Boolean = false, val refreshing: Boolean = false, val loadingMore: Boolean = false, val hasMore: Boolean = false,
    val error: VideoError? = null, val partial: Boolean = false, val automaticPaging: Boolean = true)
@HiltViewModel
class FeedViewModel @Inject constructor(private val repository: VideoRepository, private val sessions: EncryptedSessionStore,
    @ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(FeedState())
    val state = mutable.asStateFlow()
    private var cursor: Continuation? = null
    private var request: Job? = null
    init {
        viewModelScope.launch {
            sessions.initialize()
            val saved = try { context.feedPreferences.data.first()[stringPreferencesKey("selected_feed")] } catch (_: Exception) { null }
            val initial = AccountFeed.entries.firstOrNull { it.name == saved } ?: AccountFeed.Home
            mutable.value = FeedState(feed = initial)
            sessions.status.filter { it.ready }.collectLatest {
                request?.cancel(); cursor = null
                if (!it.signedIn) context.feedPreferences.edit { prefs -> prefs.clear() }
                mutable.value = FeedState(feed = if (it.signedIn) mutable.value.feed else AccountFeed.Home)
                refresh()
            }
        }
    }
    fun select(feed: AccountFeed) {
        mutable.value = FeedState(feed = feed)
        viewModelScope.launch { context.feedPreferences.edit { it[stringPreferencesKey("selected_feed")] = feed.name } }
        refresh()
    }
    fun open(playlist: PlaylistSummary) { mutable.value = FeedState(feed = mutable.value.feed, playlist = playlist); refresh() }
    fun back() { mutable.value = FeedState(feed = mutable.value.feed); refresh() }
    fun refresh() {
        request?.cancel(); cursor = null
        mutable.update { it.copy(loading = true, loadingMore = false, error = null, videos = emptyList(), playlists = emptyList(), hasMore = false) }
        load(false)
    }
    /** Pull-to-refresh: fetch fresh results while the current items stay on screen. */
    fun pullRefresh() {
        if (mutable.value.loading || mutable.value.refreshing) return
        request?.cancel(); cursor = null
        repository.invalidateFeeds()
        mutable.update { it.copy(refreshing = true, loadingMore = false, error = null) }
        load(false)
    }
    fun more() {
        if (cursor == null || mutable.value.loading || mutable.value.loadingMore) return
        mutable.update { it.copy(loadingMore = true, error = null) }; load(true)
    }
    private fun load(append: Boolean) {
        val state = mutable.value; val revision = sessions.status.value.revision
        val next = if (append) cursor else null
        request = viewModelScope.launch {
            val result = state.playlist?.let { repository.playlist(it.id, next) } ?: repository.feed(state.feed, next)
            if (sessions.status.value.revision != revision) return@launch
            when (result) {
                is Outcome.Success -> {
                    cursor = result.value.continuation
                    mutable.update { it.copy(videos = ((if (append) it.videos else emptyList()) + result.value.videos).distinctBy(Video::url),
                        playlists = ((if (append) it.playlists else emptyList()) + result.value.playlists).distinctBy { item -> item.id },
                        loading = false, refreshing = false, loadingMore = false, hasMore = cursor != null, partial = result.value.skippedItems,
                        automaticPaging = !append || result.value.videos.isNotEmpty() || result.value.playlists.isNotEmpty()) }
                }
                is Outcome.Failure -> mutable.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = result.error) }
            }
        }
    }
}
