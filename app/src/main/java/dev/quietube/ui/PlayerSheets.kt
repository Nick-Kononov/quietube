@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.quietube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.quietube.account.actions.EngagementState
import dev.quietube.cast.CastState
import dev.quietube.playback.PlaybackState

@Composable
internal fun QualitySheet(state: PlaybackState, snackbar: SnackbarHostState?, dismiss: () -> Unit, select: (Int?) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Video quality", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            val actual = state.actualHeight.takeIf { it > 0 }?.let { "Playing at ${it}p" } ?: "Waiting for video"
            ListItem(headlineContent = { Text("Auto · 1080p on Wi-Fi, 720p on mobile data") },
                supportingContent = { Text(if (state.manualQuality == null) actual else "Uses the network's resolution cap.") },
                leadingContent = { RadioButton(state.manualQuality == null, onClick = null) },
                modifier = Modifier.selectable(state.manualQuality == null, role = Role.RadioButton, onClick = { select(null) }))
            snackbar?.let { SnackbarHost(it, Modifier.padding(horizontal = 16.dp)) }
            state.qualities.forEach { height ->
                ListItem(headlineContent = { Text("${height}p") },
                    supportingContent = if (state.manualQuality == height) ({ Text(actual) }) else null,
                    leadingContent = { RadioButton(state.manualQuality == height, onClick = null) },
                    modifier = Modifier.selectable(state.manualQuality == height, role = Role.RadioButton, onClick = { select(height) }))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun CastSheet(cast: CastState, actions: CastActions, snackbar: SnackbarHostState?) {
    ModalBottomSheet(onDismissRequest = actions.dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Play on TV", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            if (cast.scanning || cast.sending != null) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(cast.sending?.let { "Opening video on ${it.name}…" } ?: "Looking for TVs on this Wi-Fi…")
            }
            cast.tvs.forEach { tv ->
                ListItem(headlineContent = { Text(tv.name) }, leadingContent = { Icon(Icons.Rounded.Tv, null) })
                if (tv.appUrl != null) ListItem(headlineContent = { Text("YouTube app · HD, with ads") }, modifier = Modifier.padding(start = 24.dp)
                    .clickable(enabled = !cast.scanning && cast.sending == null, role = Role.Button) { actions.play(tv, false) })
                if (tv.renderer != null) ListItem(headlineContent = { Text("Direct from phone · HD, no ads · experimental") }, modifier = Modifier.padding(start = 24.dp)
                    .clickable(enabled = !cast.scanning && cast.sending == null, role = Role.Button) { actions.play(tv, true) })
            }
            snackbar?.let { SnackbarHost(it, Modifier.padding(horizontal = 16.dp)) }
            TextButton(onClick = actions.scan, enabled = !cast.scanning && cast.sending == null, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Search again") }
            Text("The YouTube app plays it under the TV's account. Direct streams through this phone, which must stay on the same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
internal fun DescriptionSheet(state: PlaybackState, engagement: EngagementState, subscribe: () -> Unit, refresh: () -> Unit, snackbar: SnackbarHostState?,
    dismiss: () -> Unit, quality: () -> Unit, settings: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.video?.title ?: "Video details", style = MaterialTheme.typography.titleLarge)
            snackbar?.let { SnackbarHost(it) }
            Text(state.video?.channel.orEmpty(), style = MaterialTheme.typography.titleMedium)
            state.video?.let { video -> Text(listOfNotNull(formatViews(video.viewCount), formatPublishedDate(video.published)).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = quality, enabled = state.qualities.isNotEmpty()) {
                Text(if (state.manualQuality == null) "Quality · Auto" else "Quality · ${state.manualQuality}p")
            }
            if (engagement.signedIn) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = subscribe, enabled = !engagement.busy && engagement.info.channelId != null && engagement.info.subscribed != null) {
                    Text(when (engagement.info.subscribed) { true -> "Unsubscribe"; false -> "Subscribe"; null -> "Subscription unavailable" })
                }
                TextButton(onClick = refresh, enabled = !engagement.busy) { Text("Refresh") }
            } else Text("Sign in to rate videos, subscribe and sync history.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = settings) { Text("Settings") }
            HorizontalDivider()
            SelectionContainer { Text(state.description ?: "No description available.", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
