package dev.quietube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import dev.quietube.data.AccountFeed
import dev.quietube.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(signedIn: Boolean, profile: Profile?, open: (AccountFeed) -> Unit, search: () -> Unit,
    account: () -> Unit, settings: () -> Unit, reselect: Int) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val list = rememberLazyListState()
    var handled by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reselect) { if (reselect > handled) { list.scrollToItem(0); scroll.state.heightOffset = 0f; scroll.state.contentOffset = 0f; handled = reselect } }
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text("Library") }, scrollBehavior = scroll, actions = {
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search YouTube") }
            AccountButton(signedIn, profile, account, settings)
        })
    }) { insets ->
        if (!signedIn) Box(Modifier.fillMaxSize().padding(insets)) {
            StatusMessage(Icons.Rounded.VideoLibrary, "Your library", "Sign in to see your history, saved videos and playlists.", "Sign in", account)
        } else LazyColumn(Modifier.fillMaxSize().padding(insets), state = list) {
            libraryFeeds.forEach { feed -> item(key = feed.name) {
                ListItem(content = { Text(feed.label()) }, leadingContent = { Icon(when (feed) {
                    AccountFeed.History -> Icons.Rounded.History
                    AccountFeed.WatchLater -> Icons.Rounded.WatchLater
                    AccountFeed.Liked -> Icons.Rounded.ThumbUp
                    else -> Icons.Rounded.VideoLibrary
                }, null) }, modifier = Modifier.clickable(role = Role.Button) { open(feed) })
            } }
        }
    }
}
