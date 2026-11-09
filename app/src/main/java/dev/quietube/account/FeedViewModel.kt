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
    val error: VideoError? = null, val partial: Boolean = false, val automaticPaging: Boolean = true, val initialized: Boolean = true)
@HiltViewModel
class FeedViewModel @Inject constructor(private val repository: VideoRepository, private val sessions: EncryptedSessionStore,
    @ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(FeedState(initialized = false))
    val state = mutable.asStateFlow()
    private val snapshots = FeedSnapshots()
    private val snapshotState = MutableStateFlow<Map<AccountFeed, FeedState>>(emptyMap())
    val cached = snapshotState.asStateFlow()
    private var generation = 0L
    private var cursor: Continuation? = null
    private var request: Job? = null
    private var failedAppend = false
    init {
        viewModelScope.launch { mutable.collect { state ->
            if (state.initialized && state.playlist == null) snapshotState.update { it + (state.feed to state) }
        } }
        viewModelScope.launch {
            sessions.initialize()
            val prefs = try { context.feedPreferences.data.first() } catch (_: java.io.IOException) { null }
            val initial = AccountFeed.entries.firstOrNull { it.name == prefs?.get(stringPreferencesKey("selected_feed")) } ?: AccountFeed.Home
            val playlist = prefs?.get(stringPreferencesKey("playlist_id"))?.let { id ->
                PlaylistSummary(id, prefs[stringPreferencesKey("playlist_title")] ?: "Playlist", prefs[stringPreferencesKey("playlist_thumbnail")], prefs[stringPreferencesKey("playlist_count")])
            }
            mutable.value = FeedState(feed = initial, playlist = playlist, initialized = true)
            sessions.status.filter { it.ready }.collectLatest {
                generation++; request?.cancel(); cursor = null; snapshots.clear(); snapshotState.value = emptyMap()
                mutable.value = FeedState(feed = if (it.signedIn) mutable.value.feed else AccountFeed.Home,
                    playlist = if (it.signedIn) mutable.value.playlist else null, initialized = true)
                if (!it.signedIn) context.feedPreferences.edit { prefs -> prefs.clear() }
                refresh()
            }
        }
    }
    private fun saveCurrent() { if (sessions.status.value.signedIn) snapshots.save(mutable.value, cursor, failedAppend) }
    private fun persist() {
        val state = mutable.value; val revision = sessions.status.value.revision
        viewModelScope.launch { context.feedPreferences.edit { prefs ->
            if (!sessions.status.value.signedIn || sessions.status.value.revision != revision) return@edit
            prefs.clear(); prefs[stringPreferencesKey("selected_feed")] = state.feed.name
            state.playlist?.let { playlist ->
                prefs[stringPreferencesKey("playlist_id")] = playlist.id
                prefs[stringPreferencesKey("playlist_title")] = playlist.title
                playlist.thumbnailUrl?.let { prefs[stringPreferencesKey("playlist_thumbnail")] = it }
                playlist.countText?.let { prefs[stringPreferencesKey("playlist_count")] = it }
            }
        } }
    }
    private fun show(feed: AccountFeed, playlist: PlaylistSummary? = null) {
        saveCurrent(); generation++; request?.cancel()
        val saved = snapshots.get(feed, playlist?.id)
        cursor = saved?.cursor; failedAppend = saved?.failedAppend ?: false
        mutable.value = saved?.state ?: FeedState(feed = feed, playlist = playlist, initialized = true)
        persist()
        if (saved == null) refresh()
    }
    fun select(feed: AccountFeed) {
        if (mutable.value.feed == feed && mutable.value.playlist == null) return
        show(feed)
    }
    fun open(playlist: PlaylistSummary) = show(mutable.value.feed, playlist)
    fun back() = show(mutable.value.feed)
    fun refresh() {
        generation++; request?.cancel(); cursor = null; failedAppend = false
        if (!sessions.status.value.signedIn && mutable.value.feed != AccountFeed.Home) {
            mutable.value = FeedState(feed = mutable.value.feed, initialized = true)
            return
        }
        mutable.update { it.copy(loading = true, refreshing = false, loadingMore = false, error = null, videos = emptyList(), playlists = emptyList(), hasMore = false) }
        load(false)
    }
    /** Pull-to-refresh: fetch fresh results while the current items stay on screen. */
    fun pullRefresh() {
        if (mutable.value.loading || mutable.value.refreshing || (!sessions.status.value.signedIn && mutable.value.feed != AccountFeed.Home)) return
        // Keep the cursor until fresh results arrive, so Load more still works if the refresh fails.
        generation++; request?.cancel()
        repository.invalidateFeeds()
        mutable.update { it.copy(refreshing = true, loadingMore = false, error = null) }
        val state = mutable.value
        load(false, seen = if (state.feed == AccountFeed.Home && state.playlist == null) state.videos.mapTo(HashSet(), Video::url) else emptySet())
    }
    fun more() {
        if (cursor == null || mutable.value.loading || mutable.value.loadingMore || mutable.value.refreshing) return
        mutable.update { it.copy(loadingMore = true, error = null) }; load(true)
    }
    /** Repeats whatever failed: the next page, a pull-to-refresh over existing items, or the first load. */
    fun retry() = when {
        failedAppend -> more()
        mutable.value.videos.isEmpty() && mutable.value.playlists.isEmpty() -> refresh()
        else -> pullRefresh()
    }
    private suspend fun fetch(state: FeedState, next: Continuation?) =
        state.playlist?.let { repository.playlist(it.id, next) } ?: repository.feed(state.feed, next)
    /** YouTube's refreshed Home repeats most of the previous one; lead with unseen videos and reshuffle the repeats. */
    private suspend fun freshFirst(state: FeedState, first: Outcome<VideoPage>, seen: Set<String>): Outcome<VideoPage> {
        var page = (first as? Outcome.Success)?.value ?: return first
        val pages = mutableListOf(page)
        while (pages.size < 3 && page.continuation != null && pages.sumOf { p -> p.videos.count { it.url !in seen } } < 10)
            page = (fetch(state, page.continuation) as? Outcome.Success)?.value?.also(pages::add) ?: break
        val (unseen, repeats) = pages.flatMap(VideoPage::videos).distinctBy(Video::url).partition { it.url !in seen }
        return Outcome.Success(page.copy(videos = unseen + repeats.shuffled(), skippedItems = pages.any(VideoPage::skippedItems),
            playlists = pages.flatMap(VideoPage::playlists)))
    }
    private fun load(append: Boolean, seen: Set<String> = emptySet()) {
        val state = mutable.value; val revision = sessions.status.value.revision
        val expected = generation
        val next = if (append) cursor else null
        request = viewModelScope.launch {
            val result = fetch(state, next).let { if (seen.isEmpty()) it else freshFirst(state, it, seen) }
            if (sessions.status.value.revision != revision || generation != expected) return@launch
            when (result) {
                is Outcome.Success -> {
                    val page = result.value; val current = mutable.value
                    val videos = ((if (append) current.videos else emptyList()) + page.videos).distinctBy(Video::url)
                    val playlists = ((if (append) current.playlists else emptyList()) + page.playlists).distinctBy { item -> item.id }
                    // TV Playlists hands back a continuation that repeats the same page; stop once a page adds nothing new.
                    val repeated = append && (page.videos.isNotEmpty() || page.playlists.isNotEmpty()) &&
                        videos.size == current.videos.size && playlists.size == current.playlists.size
                    cursor = page.continuation?.takeUnless { repeated }
                    mutable.update { it.copy(videos = videos, playlists = playlists,
                        loading = false, refreshing = false, loadingMore = false, hasMore = cursor != null,
                        partial = (append && it.partial) || result.value.skippedItems,
                        automaticPaging = !append || result.value.videos.isNotEmpty() || result.value.playlists.isNotEmpty()) }
                    saveCurrent()
                }
                is Outcome.Failure -> {
                    failedAppend = append
                    mutable.update { it.copy(loading = false, refreshing = false, loadingMore = false, error = result.error) }
                }
            }
        }
    }
}
