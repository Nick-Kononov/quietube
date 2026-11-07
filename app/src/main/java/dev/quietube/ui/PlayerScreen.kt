@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.ui

import android.graphics.Rect
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import dev.quietube.playback.PlaybackState
import dev.quietube.account.actions.*
import dev.quietube.data.Rating

@Composable
fun PlayerScreen(state: PlaybackState, controller: MediaController?, connectionError: Boolean, inPip: Boolean,
    comments: CommentsState, moreComments: () -> Unit, refreshComments: () -> Unit,
    engagement: EngagementState, rate: (Rating) -> Unit, subscribe: () -> Unit, refreshAccount: () -> Unit, history: (Boolean) -> Unit, estimates: (Boolean) -> Unit,
    back: () -> Unit, pip: () -> Unit, bounds: (Rect) -> Unit, quality: (Int?) -> Unit, play: (String) -> Unit) {
    var showQuality by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    val videoOnly = inPip || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize().background(Color.Black).then(if (inPip) Modifier else Modifier.safeDrawingPadding())) {
        if (!videoOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to search") }
            Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = pip, enabled = state.video != null && state.error == null && !state.resolving) {
                Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture")
            }
        }
        Box(Modifier.fillMaxWidth().then(if (videoOnly) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
            .onGloballyPositioned { coordinates ->
                val r = coordinates.boundsInWindow()
                bounds(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            }, contentAlignment = Alignment.Center) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowNextButton(false); setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // TextureView is unnecessary; SurfaceView lets the decoder render directly.
            } }, modifier = Modifier.fillMaxSize(), update = {
                it.player = controller
                it.useController = !inPip
                it.keepScreenOn = state.isPlaying && !inPip
            }, onReset = null, onRelease = { it.player = null })
            if (state.resolving || state.buffering) CircularProgressIndicator()
        }
        if (!videoOnly) Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
            TextButton(onClick = { detailsExpanded = !detailsExpanded },modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.video?.title ?: "Choose a video from search",style = MaterialTheme.typography.titleMedium,maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (detailsExpanded) "Hide details and controls" else "Details and controls",style = MaterialTheme.typography.labelSmall)
                }
                Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if (detailsExpanded) "Collapse details" else "Expand details")
            }
            AnimatedVisibility(detailsExpanded) {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp,vertical = 8.dp)) {
                    Text(state.video?.channel.orEmpty(),color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showQuality = true }, enabled = state.qualities.isNotEmpty()) {
                            val actual = state.actualHeight.takeIf { it > 0 } ?: state.selectedHeight
                            Text(if (state.manualQuality == null) "Auto${if (actual > 0) " · ${actual}p" else ""}" else "${state.manualQuality}p")
                        }
                        Text("Background audio enabled", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = engagement.info.rating == Rating.Like,onClick = { rate(Rating.Like) },
                            enabled = engagement.signedIn && !engagement.busy,label = { Text("Like · ${displayLikeCount(engagement.info.likes,state.video?.likeCount)}") })
                        FilterChip(selected = engagement.info.rating == Rating.Dislike,onClick = { rate(Rating.Dislike) },
                            enabled = engagement.signedIn && !engagement.busy,label = { Text("Dislike · ${if (engagement.info.estimatedDislikes) "≈" else ""}${engagement.info.dislikes ?: "—"}") })
                    }
                    if (engagement.signedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = subscribe,enabled = !engagement.busy && engagement.info.channelId != null && engagement.info.subscribed != null) {
                                Text(when (engagement.info.subscribed) { true -> "Unsubscribe"; false -> "Subscribe"; null -> "Subscription unavailable" })
                            }
                            TextButton(onClick = refreshAccount,enabled = !engagement.busy) { Text("Refresh") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sync watch history",modifier = Modifier.weight(1f))
                            Switch(checked = engagement.settings.history,onCheckedChange = history)
                        }
                        state.syncMessage?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
                    } else Text("Sign in to rate videos, subscribe and sync history.",style = MaterialTheme.typography.bodySmall)
                    engagement.message?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Estimate dislikes",modifier = Modifier.weight(1f))
                        Switch(checked = engagement.settings.dislikeEstimates,onCheckedChange = estimates)
                    }
                    Text("Return YouTube Dislike estimates receive this video's ID when enabled. Counts can be delayed or unavailable.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (connectionError) Text("Could not connect to playback. Close and reopen Quietube.",modifier = Modifier.padding(horizontal = 20.dp))
            state.error?.let { error ->
                Text(error.message(),color = MaterialTheme.colorScheme.error,modifier = Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = { state.video?.url?.let(play) }) { Text("Retry playback") }
            }
            key(state.video?.url) {
                PlayerCommunity(comments,moreComments,refreshComments,state.related,play,Modifier.weight(1f))
            }
        }
    }
    if (showQuality && !inPip) AlertDialog(onDismissRequest = { showQuality = false },
        title = { Text("Video quality") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("Auto uses 1080p on Wi-Fi and 720p on mobile data.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { quality(null); showQuality = false }) { Text("Auto${if (state.manualQuality == null) " ✓" else ""}") }
                state.qualities.forEach { height ->
                    TextButton(onClick = { quality(height); showQuality = false }) {
                        Text("${height}p${if (height == state.manualQuality) " ✓" else ""}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showQuality = false }) { Text("Done") } })
    }
}
