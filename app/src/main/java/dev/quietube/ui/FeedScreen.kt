package dev.quietube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.quietube.account.FeedState
import dev.quietube.account.SessionStatus
import dev.quietube.data.*
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: FeedState, session: SessionStatus,
    search: () -> Unit, account: () -> Unit, refresh: () -> Unit, more: () -> Unit,
    play: (String) -> Unit, playlist: (PlaylistSummary) -> Unit, back: () -> Unit, prefetch: (List<String>) -> Unit = {},
    pullRefresh: () -> Unit = refresh, retry: () -> Unit = refresh, profile: Profile? = null, settings: (() -> Unit)? = null, reselect: Int = 0, active: Boolean = true) {
    val listState = rememberLazyListState()
    if (active) PrefetchVisibleVideos(listState, prefetch)
    val restoreIndex = remember { listState.firstVisibleItemIndex }
    var restoring by remember { mutableStateOf(restoreIndex > 0 || listState.firstVisibleItemScrollOffset > 0) }
    // Refill pages before attaching the restored list, or a short first page would clamp its saved position.
    LaunchedEffect(active, restoring, state.initialized, state.loading, state.loadingMore, state.hasMore, state.videos.size, state.playlists.size, state.error) {
        if (restoring && state.initialized && !state.loading && !state.loadingMore) {
            if (state.error == null && state.hasMore && state.automaticPaging && state.videos.size + state.playlists.size <= restoreIndex) { if (active) more() }
            else restoring = false
        }
    }
    // An opened playlist is screen state, not a navigation entry; Back returns to the library.
    androidx.activity.compose.BackHandler(enabled = state.playlist != null, onBack = back)
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var handled by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reselect) {
        if (reselect > handled) { restoring = false; listState.scrollToItem(0); scroll.state.heightOffset = 0f; scroll.state.contentOffset = 0f; handled = reselect; pullRefresh() }
    }
    // Keyed items keep the old first video anchored after a reshuffle; a finished pull starts again from the top.
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(state.refreshing) {
        if (pulling && !state.refreshing && state.error == null) listState.scrollToItem(0)
        pulling = state.refreshing
    }
    LaunchedEffect(active, listState, state.hasMore, state.loading, state.loadingMore, state.automaticPaging) {
        if (active && state.hasMore && !state.loading && !state.loadingMore && state.automaticPaging) {
            snapshotFlow { val info = listState.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount }
                .distinctUntilChanged().collect { (last, count) -> if (last != null && count > 0 && last >= count - 3) more() }
        }
    }
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        TopAppBar(title = {
            if (state.playlist != null) Text(state.playlist.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else if (state.feed == AccountFeed.Home) Wordmark() else Text(state.feed.label())
        }, navigationIcon = {
            if (state.feed in libraryFeeds || state.playlist != null) IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (state.playlist != null) "Back to playlists" else "Back to library")
            }
        }, scrollBehavior = scroll, actions = {
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search YouTube") }
            AccountButton(session.signedIn, profile, account, settings)
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (!session.signedIn && state.feed != AccountFeed.Home) {
                StatusMessage(Icons.Rounded.AccountCircle, state.feed.label(), "Sign in to see your ${state.feed.label().lowercase()}.", "Sign in", account)
                return@Column
            }
            RefreshBox(refreshing = state.refreshing, refresh = pullRefresh, modifier = Modifier.fillMaxSize()) {
            if (restoring) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) { repeat(3) { VideoCardPlaceholder() } }
            else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if ((!state.initialized || state.loading) && state.videos.isEmpty() && state.playlists.isEmpty()) items(3) { VideoCardPlaceholder() }
                if (!session.signedIn && state.feed == AccountFeed.Home) item {
                    Text("Search videos to build a feed on this phone, or sign in for your personal recommendations.",
                        modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.playlists, key = { "playlist:${it.id}" }, contentType = { "playlist" }) { item ->
                    ListItem(content = { Text(item.title) }, supportingContent = { Text(item.countText ?: "Playlist") },
                        leadingContent = { AsyncImage(item.thumbnailUrl, null, Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) },
                        modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { playlist(item) })
                }
                items(state.videos, key = { it.url }, contentType = { "video" }) { video -> VideoCard(video) { play(video.url) } }
                state.error?.let { error -> item {
                    StatusMessage(Icons.Rounded.CloudOff, "Feed unavailable", error.message(),
                        if (error == VideoError.AuthExpired) "Open account" else "Try again", if (error == VideoError.AuthExpired) account else retry)
                } }
                if (state.initialized && !state.loading && state.error == null && state.videos.isEmpty() && state.playlists.isEmpty()) item {
                    StatusMessage(Icons.Rounded.VideoLibrary, "Nothing to show here yet.")
                }
                if (state.partial) item { Text("Some items could not be read and were skipped.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                if (state.hasMore) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) AppLoadingIndicator(Modifier.size(28.dp))
                        else OutlinedButton(onClick = more) { Text("Load more") }
                    }
                }
            }
            }
        }
    }
}
internal fun AccountFeed.label(): String = when (this) {
    AccountFeed.Home -> "Home"; AccountFeed.Subscriptions -> "Subscriptions"; AccountFeed.History -> "History"
    AccountFeed.WatchLater -> "Watch Later"; AccountFeed.Liked -> "Liked videos"; AccountFeed.Playlists -> "Playlists"
}
