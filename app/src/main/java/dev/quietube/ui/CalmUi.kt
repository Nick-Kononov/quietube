package dev.quietube.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quietube.data.CalmSettings
import dev.quietube.data.Video
import dev.quietube.links.shareableLink
import dev.quietube.playback.UpNext
import kotlinx.coroutines.delay

internal val LocalCalm = staticCompositionLocalOf { CalmSettings() }

/** Focus mode: thumbnails stay grey on cards; the opened video plays in colour. */
internal val GreyThumbnail = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
internal fun CaughtUpMarker() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Icon(Icons.Rounded.DoneAll, null, tint = MaterialTheme.colorScheme.primary)
        Text("You're all caught up", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Autoplay's countdown over the finished video. */
@Composable
internal fun UpNextCard(next: UpNext, playNow: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember(next) { mutableLongStateOf(0L) }
    LaunchedEffect(next) {
        while (true) { seconds = ((next.atMs - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0); delay(250) }
    }
    val title = next.title?.let { displayTitle(next.url, it) }
    Surface(modifier.widthIn(max = 420.dp).padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Text(if (seconds > 0) "Up next in $seconds" else "Up next", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(cancel) { Text("Cancel") }
                FilledTonalButton(playNow, Modifier.padding(start = 8.dp)) { Text("Play now") }
            }
        }
    }
}

/** A card's long-press menu: only actions that work without an account change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoMenu(video: Video, title: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    val link = remember(video.url) { shareableLink(video.url) ?: video.url }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (video.channel.isNotBlank()) Text(video.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // DeArrow replaced the title; the uploader's own stays one look away.
            if (title != video.title) Text("Original title: ${video.title}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        ListItem(content = { Text("Share") }, leadingContent = { Icon(Icons.Rounded.Share, null) }, modifier = Modifier.clickable(role = Role.Button) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            dismiss()
        })
        ListItem(content = { Text("Copy link") }, leadingContent = { Icon(Icons.Rounded.ContentCopy, null) }, modifier = Modifier.clickable(role = Role.Button) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("YouTube link", link))
            // Android 13 and later confirm copies themselves.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            dismiss()
        })
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}
