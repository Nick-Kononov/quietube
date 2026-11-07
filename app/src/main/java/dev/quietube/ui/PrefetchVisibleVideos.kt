package dev.quietube.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
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
