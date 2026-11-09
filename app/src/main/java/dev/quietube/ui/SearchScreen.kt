package dev.quietube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchState, recent: List<String>, edit: (String) -> Unit, search: (String) -> Unit, more: () -> Unit,
    clearHistory: () -> Unit, play: (String) -> Unit, back: () -> Unit, expandedChange: (Boolean) -> Unit, prefetch: (List<String>) -> Unit = {},thumbnailPrefetch: (List<ThumbnailRequest>) -> Unit = {}) {
    val bar = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val text = rememberTextFieldState(state.draft)
    val scope = rememberCoroutineScope()
    val expanded = bar.currentValue == SearchBarValue.Expanded
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    if (!expanded) {
        PrefetchVisibleVideos(list, prefetch)
        PrefetchVisibleThumbnails(list,state.videos,thumbnailPrefetch)
    }
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect(edit) }
    SideEffect { expandedChange(expanded) }
    DisposableEffect(Unit) { onDispose { expandedChange(false) } }
    val collapse: () -> Unit = { keyboard?.hide(); scope.launch { bar.animateToCollapsed() } }
    val submit: (String) -> Unit = { value -> if (value.isNotBlank()) { text.setTextAndPlaceCursorAtEnd(value); collapse(); search(value) } }
    val input: @Composable () -> Unit = {
        SearchBarDefaults.InputField(textFieldState = text,searchBarState = bar,onSearch = submit,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.Enter) false else { if (event.type == KeyEventType.KeyUp) submit(text.text.toString()); true }
            },placeholder = { Text("Search YouTube") },
            leadingIcon = { IconButton(onClick = { if (expanded) collapse() else back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back") } },
            trailingIcon = { if (text.text.isNotEmpty()) IconButton(onClick = { text.setTextAndPlaceCursorAtEnd(""); edit("") }) { Icon(Icons.Rounded.Close,"Clear search") } })
    }
    Scaffold(contentWindowInsets = WindowInsets(0)) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            SearchBar(state = bar,inputField = input,modifier = Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp))
            ExpandedFullScreenSearchBar(state = bar,inputField = input) {
                LazyColumn(Modifier.fillMaxSize().imePadding()) {
                    if (recent.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = clearHistory) { Text("Clear history") }
                        }
                    }
                    items(recent.filter { state.draft.isBlank() || it.contains(state.draft, ignoreCase = true) }, key = { it }) { query ->
                        ListItem(content = { Text(query) }, leadingContent = { Icon(Icons.Rounded.History, null) },
                            modifier = Modifier.clickable(role = Role.Button) { submit(query) })
                    }
                    state.suggestion?.let { suggestion -> item {
                        ListItem(content = { Text("Try searching: $suggestion") }, leadingContent = { Icon(Icons.Rounded.Search, null) },
                            modifier = Modifier.clickable(role = Role.Button) { submit(suggestion) })
                    } }
                    if (state.draft.isNotBlank()) item {
                        ListItem(content = { Text("Search for ${state.draft}") }, leadingContent = { Icon(Icons.Rounded.Search, null) },
                            modifier = Modifier.clickable(role = Role.Button) { submit(state.draft) })
                    } else item { Text("Find something worth watching.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp)) }
                }
            }
            if (!expanded) LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (!state.searched) item { StatusMessage(Icons.Rounded.Search, "Find your next deep dive", "Search videos or choose a recent search above.") }
                if (state.loading) items(3) { VideoCardPlaceholder() }
                state.suggestion?.let { suggestion -> item {
                    TextButton(onClick = { submit(suggestion) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Try searching: $suggestion") }
                } }
                items(state.videos, key = { it.url }, contentType = { "video" }) { video -> VideoCard(video) { play(video.url) } }
                if (state.searched && !state.loading && state.error != null) item {
                    StatusMessage(Icons.Rounded.CloudOff, "Search unavailable", state.error.message(), "Try again") {
                        if (state.videos.isEmpty()) submit(state.query) else more()
                    }
                }
                if (state.searched && !state.loading && state.error == null && state.videos.isEmpty()) item {
                    StatusMessage(Icons.Rounded.Search, "No videos found", "Try a different search.")
                }
                if (state.partial) item { Text("Some results could not be read and were skipped.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (state.hasMore && state.error == null) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) AppLoadingIndicator(Modifier.size(28.dp)) else OutlinedButton(onClick = more) { Text("Load more videos") }
                    }
                }
            }
        }
    }
}
