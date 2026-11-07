@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.quietube.playback.PlaybackState

/** Shown above other screens while a video is active; tap expands to the player, the cross ends playback. */
@Composable
internal fun MiniPlayer(state: PlaybackState, controller: MediaController?, expand: () -> Unit, toggle: () -> Unit, close: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = expand), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.navigationBarsPadding().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { context -> PlayerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                } }, modifier = Modifier.fillMaxSize(), update = { it.player = controller }, onReset = null, onRelease = { it.player = null })
                if (state.resolving || state.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                // The video view would otherwise swallow taps meant to expand the player.
                Box(Modifier.matchParentSize().clickable(onClick = expand))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.video?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.video?.channel.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = toggle) {
                Icon(if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playWhenReady) "Pause" else "Play")
            }
            IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close player") }
        }
    }
}
