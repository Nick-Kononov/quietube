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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun FeedScreen(state: FeedState, session: SessionStatus, select: (AccountFeed) -> Unit,
    search: () -> Unit, account: () -> Unit, refresh: () -> Unit, more: () -> Unit,
    play: (String) -> Unit, playlist: (PlaylistSummary) -> Unit, back: () -> Unit, prefetch: (List<String>) -> Unit = {},
    pullRefresh: () -> Unit = refresh) {
    val listState = rememberLazyListState()
    PrefetchVisibleVideos(listState, prefetch)
    LaunchedEffect(state.feed, state.playlist?.id) { listState.scrollToItem(0) }
    LaunchedEffect(listState, state.hasMore, state.loading, state.loadingMore, state.automaticPaging) {
        if (state.hasMore && !state.loading && !state.loadingMore && state.automaticPaging) {
            snapshotFlow { val info = listState.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount }
                .distinctUntilChanged().collect { (last, count) -> if (last != null && count > 0 && last >= count - 3) more() }
        }
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.playlist != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }
                Text("quietube", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search YouTube") }
                TextButton(onClick = account) { Text(if (session.signedIn) "Account" else "Sign in") }
            }
            if (state.playlist == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountFeed.entries.forEach { feed -> FilterChip(selected = state.feed == feed, onClick = { select(feed) },
                    label = { Text(feed.label()) }, enabled = session.signedIn || feed == AccountFeed.Home) }
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = pullRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.playlist?.title ?: if (session.signedIn) state.feed.label() else "Your local feed",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                }
                if (state.loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (!session.signedIn && state.feed == AccountFeed.Home) item {
                    Text("Search videos to build a feed on this phone, or sign in for your personal recommendations.",
                        modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.playlists, key = { "playlist:${it.id}" }) { item ->
                    Row(Modifier.fillMaxWidth().clickable { playlist(item) }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.thumbnailUrl, "Playlist cover", Modifier.width(120.dp).aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.countText ?: "Playlist", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(state.videos, key = { it.url }) { video -> VideoCard(video) { play(video.url) } }
                state.error?.let { error -> item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(error.message(), color = MaterialTheme.colorScheme.error)
                        if (error == VideoError.AuthExpired) TextButton(onClick = account) { Text("Open account") }
                        else TextButton(onClick = { if (state.videos.isEmpty() && state.playlists.isEmpty()) refresh() else more() }) { Text("Try again") }
                    }
                } }
                if (!state.loading && state.error == null && state.videos.isEmpty() && state.playlists.isEmpty() && session.signedIn) item {
                    Text("Nothing to show here yet.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.partial) item { Text("Some items could not be read and were skipped.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                if (state.hasMore) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(28.dp))
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
