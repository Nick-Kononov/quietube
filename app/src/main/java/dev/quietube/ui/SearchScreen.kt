package dev.quietube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import dev.quietube.data.Video
import dev.quietube.data.VideoError
import java.util.Locale

private val Mint = Color(0xFFB5E9CC)
private val Background = Color(0xFF101215)
private val Panel = Color(0xFF1B1E23)

@Composable
fun QuietubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF133624),
        background = Background, surface = Background, surfaceVariant = Panel,
        onSurface = Color(0xFFE7E9EB), onSurfaceVariant = Color(0xFFA4AAB3)), content = content)
}

@Composable
fun SearchScreen(state: SearchState, edit: (String) -> Unit, search: (String) -> Unit, more: () -> Unit, play: (String) -> Unit, signedIn: Boolean, account: () -> Unit, home: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit: (String) -> Unit = { keyboard?.hide(); search(it) }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = home) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Home") }
                Surface(shape = RoundedCornerShape(12.dp), color = Mint) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Background, modifier = Modifier.padding(8.dp).size(24.dp))
                }
                Spacer(Modifier.width(11.dp))
                Text("quietube", fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Spacer(Modifier.weight(1f))
                Surface(color = Panel, shape = RoundedCornerShape(50), onClick = account) {
                    Text(if (signedIn) "Account" else "Sign in", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = edit,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search YouTube") }, singleLine = true,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { submit(state.draft) }, enabled = state.draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(state.draft) }))
            if (!state.searched) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("A little less noise.", color = Mint, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(14.dp))
                    Text("Find your next\ndeep dive.", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1).sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Search videos, discover something new,\nand make room for your curiosity.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(28.dp))
                    listOf("Music for focus", "Space & science", "Kotlin tutorials").forEach { topic ->
                        SuggestionChip(onClick = { submit(topic) }, label = { Text(topic) },
                            icon = { Icon(Icons.Rounded.NorthEast, null, Modifier.size(16.dp)) })
                    }
                    Spacer(Modifier.height(32.dp))
                    Text("YOUR OWN LITTLE CORNER", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                    Text("Watch here. Keep listening anywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item {
                        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Search results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("VIDEOS", color = Mint, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                        }
                    }
                    if (state.loading) {
                        items(3) {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Panel),
                                    contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                                Spacer(Modifier.height(12.dp))
                                Box(Modifier.fillMaxWidth(.75f).height(16.dp).background(Panel, RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                    state.suggestion?.let { suggestion -> item {
                        TextButton(onClick = { submit(suggestion) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("Try searching: $suggestion")
                        }
                    } }
                    items(state.videos, key = { it.url }) { video -> VideoCard(video) { play(video.url) } }
                    if (!state.loading && state.error != null) item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.CloudOff, null, tint = Mint)
                            Text(state.error.message(), modifier = Modifier.padding(vertical = 12.dp))
                            OutlinedButton(onClick = { if (state.videos.isEmpty()) submit(state.query) else more() }) { Text("Try again") }
                        }
                    }
                    if (!state.loading && state.error == null && state.videos.isEmpty()) item {
                        Text("No videos found. Try a different search.", modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.partial) item {
                        Text("Some results could not be read and were skipped.", modifier = Modifier.padding(horizontal = 22.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.hasMore && state.error == null) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(28.dp))
                            else OutlinedButton(onClick = more) { Text("Load more videos") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoCard(video: Video, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Panel)) {
            SubcomposeAsyncImage(model = video.thumbnailUrl, contentDescription = "Thumbnail for ${video.title}",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Image, null, tint = Color.Gray) } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ImageNotSupported, "Thumbnail unavailable", tint = Color.Gray) } })
            val duration = if (video.isLive) "LIVE" else formatDuration(video.durationSeconds)
            if (duration != null) Text(duration, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(listOfNotNull(video.channel.takeIf(String::isNotBlank), formatViews(video.viewCount), video.published)
            .joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp))
    }
}

internal fun formatDuration(seconds: Long): String? = when {
    seconds < 0 -> null
    seconds >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else -> String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
internal fun displayLikeCount(accountCount: String?,publicCount: Long?): String =
    accountCount?.takeIf { it.isNotBlank() } ?: formatLikeCount(publicCount) ?: "—"
internal fun formatLikeCount(count: Long?): String? = when {
    count == null || count < 0 -> null
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}
private fun formatViews(views: Long): String? = when {
    views < 0 -> null
    views >= 1_000_000 -> String.format(Locale.US, "%.1fM views", views / 1_000_000.0)
    views >= 1_000 -> String.format(Locale.US, "%.1fK views", views / 1_000.0)
    else -> "$views views"
}
internal fun VideoError.message(): String = when (this) {
    VideoError.ContentUnavailable -> "This content is unavailable. Try another search."
    VideoError.AgeRestricted -> "This content requires age verification."
    VideoError.AuthExpired -> "Your session expired. Sign in again."
    VideoError.ExtractionBroken -> "YouTube returned an unreadable response. An extractor update may be needed."
    VideoError.StreamRejected -> "YouTube rejected this stream. Proof-of-origin verification may be required."
    VideoError.UnsupportedPlayback -> "This video format could not be played. Try a lower quality."
    VideoError.Network -> "Could not reach YouTube. Check your connection and try again."
    VideoError.RateLimited -> "YouTube is limiting requests or asking for verification. Please try again later."
}
