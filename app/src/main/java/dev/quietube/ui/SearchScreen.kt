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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchState, recent: List<String>, edit: (String) -> Unit, search: (String) -> Unit, more: () -> Unit,
    clearHistory: () -> Unit, play: (String) -> Unit, back: () -> Unit, expandedChange: (Boolean) -> Unit, prefetch: (List<String>) -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val restingFocus = remember { FocusRequester() }
    if (!expanded) PrefetchVisibleVideos(list, prefetch)
    LaunchedEffect(expanded) { if (expanded) { withFrameNanos { }; focus.requestFocus(); keyboard?.show(); prefetch(emptyList()) } }
    SideEffect { expandedChange(expanded) }
    DisposableEffect(Unit) { onDispose { expandedChange(false) } }
    // Keep focus inside the screen; clearing Android owner focus can refocus and reopen the field.
    val expand: (Boolean) -> Unit = { value ->
        if (!value) { restingFocus.requestFocus(); keyboard?.hide() }
        expanded = value
    }
    val submit: (String) -> Unit = { value -> if (value.isNotBlank()) { expand(false); search(value) } }
    Scaffold(contentWindowInsets = WindowInsets(0)) { insets ->
        Column(Modifier.fillMaxSize().focusRequester(restingFocus).focusTarget().padding(insets)) {
            SearchBar(modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.padding(horizontal = 16.dp)),
                inputField = { SearchBarDefaults.InputField(query = state.draft, onQueryChange = edit, onSearch = submit,
                    expanded = expanded, onExpandedChange = expand, modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                        // Submit physical Enter on release before moving focus out of the field.
                        if (event.key != Key.Enter) false else { if (event.type == KeyEventType.KeyUp) submit(state.draft); true }
                    }, placeholder = { Text("Search YouTube") },
                    leadingIcon = { IconButton(onClick = { if (expanded) expand(false) else back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    trailingIcon = { if (state.draft.isNotEmpty()) IconButton(onClick = { edit("") }) { Icon(Icons.Rounded.Close, "Clear search") } }) },
                expanded = expanded, onExpandedChange = expand) {
                LazyColumn(Modifier.fillMaxSize().imePadding()) {
                    if (recent.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = clearHistory) { Text("Clear history") }
                        }
                    }
                    items(recent.filter { state.draft.isBlank() || it.contains(state.draft, ignoreCase = true) }, key = { it }) { query ->
                        ListItem(headlineContent = { Text(query) }, leadingContent = { Icon(Icons.Rounded.History, null) },
                            modifier = Modifier.clickable(role = Role.Button) { submit(query) })
                    }
                    state.suggestion?.let { suggestion -> item {
                        ListItem(headlineContent = { Text("Try searching: $suggestion") }, leadingContent = { Icon(Icons.Rounded.Search, null) },
                            modifier = Modifier.clickable(role = Role.Button) { submit(suggestion) })
                    } }
                    if (state.draft.isNotBlank()) item {
                        ListItem(headlineContent = { Text("Search for ${state.draft}") }, leadingContent = { Icon(Icons.Rounded.Search, null) },
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
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(28.dp)) else OutlinedButton(onClick = more) { Text("Load more videos") }
                    }
                }
            }
        }
    }
}
