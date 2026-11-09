package dev.quietube.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports videos that stay on screen briefly (not ones flung past). List items must be keyed by video URL. */
@OptIn(FlowPreview::class)
@Composable
internal fun PrefetchVisibleVideos(state: LazyListState, prefetch: (List<String>) -> Unit) {
    val current by rememberUpdatedState(prefetch)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith(WATCH_URL) } } }
            .distinctUntilChanged().debounce(400).collect { current(it) }
    }
}
private const val WATCH_URL = "https://www.youtube.com/watch?v="

/** Warm two covers ahead and one behind at the same decoded size as a card. */
@OptIn(FlowPreview::class)
@Composable
internal fun PrefetchVisibleThumbnails(state: LazyListState,videos: List<dev.quietube.data.Video>,prefetch: (List<ThumbnailRequest>) -> Unit) {
    val current by rememberUpdatedState(prefetch)
    val items by rememberUpdatedState(videos)
    val padding = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }
    LaunchedEffect(state,padding) {
        snapshotFlow {
            val layout = state.layoutInfo
            thumbnailLookAhead(items,layout.visibleItemsInfo.mapNotNull { it.key as? String },layout.viewportSize.width-padding)
        }.distinctUntilChanged().sample(150).collect { current(it) }
    }
    androidx.compose.runtime.DisposableEffect(state) { onDispose { current(emptyList()) } }
}
