package dev.quietube.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.quietube.data.Comment
import dev.quietube.data.Video
import kotlinx.coroutines.launch

@Composable
internal fun PlayerCommunity(comments: CommentsState,more: () -> Unit,refresh: () -> Unit,pullRefresh: () -> Unit,related: List<Video>,
    play: (String) -> Unit,prefetch: (List<String>) -> Unit,modifier: Modifier = Modifier,active: Boolean = true,thumbnailPrefetch: (List<ThumbnailRequest>) -> Unit = {}) {
    val relatedList = androidx.compose.foundation.lazy.rememberLazyListState()
    val pager = rememberPagerState(initialPage = 0,pageCount = { 2 })
    LaunchedEffect(active,pager.currentPage) { if (active && pager.currentPage == 0) prefetch(emptyList()) }
    if (active && pager.currentPage == 1) {
        PrefetchVisibleVideos(relatedList,prefetch)
        PrefetchVisibleThumbnails(relatedList,related,thumbnailPrefetch)
    }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = pager.currentPage) {
            Tab(selected = pager.currentPage == 0,onClick = { scope.launch { pager.animateScrollToPage(0) } },
                text = { Text("Comments${comments.total?.let { " · $it" } ?: ""}",maxLines = 1) })
            Tab(selected = pager.currentPage == 1,onClick = { scope.launch { pager.animateScrollToPage(1) } },text = { Text("Related videos") })
        }
        HorizontalPager(state = pager,modifier = Modifier.weight(1f).fillMaxWidth(),verticalAlignment = Alignment.Top) { page ->
            if (page == 0) CommentList(comments,more,refresh,pullRefresh)
            else LazyColumn(Modifier.fillMaxSize(),state = relatedList,contentPadding = PaddingValues(vertical = 16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (related.isEmpty()) item { StatusMessage(Icons.Rounded.VideoLibrary, "No related videos available.") }
                items(related,key = { it.url }) { video -> VideoCard(video) { play(video.url) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentList(state: CommentsState,more: () -> Unit,refresh: () -> Unit,pullRefresh: () -> Unit) {
    RefreshBox(refreshing = state.refreshing,refresh = pullRefresh,modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(horizontal = 20.dp,vertical = 12.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Top comments",style = MaterialTheme.typography.labelMedium) }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.disabled) item { StatusMessage(Icons.Rounded.CommentsDisabled, "Comments are turned off", "For this video.") }
        else if (!state.loading && state.error == null && state.comments.isEmpty()) item { StatusMessage(Icons.AutoMirrored.Rounded.Comment, "No comments yet.") }
        items(state.comments,key = { it.id }) { comment -> CommentCard(comment) }
        if (state.partial) item { Text("Some comments could not be loaded.",style = MaterialTheme.typography.bodySmall) }
        state.error?.let { error -> item {
            StatusMessage(Icons.Rounded.CloudOff, "Comments unavailable", error.message(), "Retry comments") {
                if (state.comments.isEmpty()) refresh() else more()
            }
        } }
        if (state.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (state.hasMore && state.error == null) item { OutlinedButton(onClick = more,modifier = Modifier.fillMaxWidth()) { Text("Load more comments") } }
    }
    }
}

@Composable
private fun CommentCard(comment: Comment) {
    var expanded by rememberSaveable(comment.id) { mutableStateOf(false) }
    var clipped by remember(comment.id) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = comment.avatarUrl,contentDescription = null,modifier = Modifier.size(36.dp).clip(CircleShape))
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(comment.author,style = MaterialTheme.typography.labelLarge)
            Text(listOfNotNull(comment.published,if (comment.pinned) "Pinned" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(comment.text,style = MaterialTheme.typography.bodyMedium,maxLines = if (expanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,onTextLayout = { clipped = it.hasVisualOverflow })
            }
            if (clipped || expanded) TextButton(onClick = { expanded = !expanded },contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Show less" else "Read more") }
            Text(listOfNotNull(comment.likes?.let { "${formatLikeCount(it.toLong())} likes" },if (comment.creatorHeart) "Creator heart" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
