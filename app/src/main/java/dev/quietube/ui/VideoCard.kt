package dev.quietube.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.quietube.data.Video

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun VideoCard(video: Video, onClick: () -> Unit) {
    // Feeds return 320px thumbnails; prefer the 1280px one, which older uploads and live streams may lack.
    val highRes = remember(video.url) { highResThumbnail(video.url) }
    var fallback by remember(video.url) { mutableStateOf(highRes == null) }
    var failed by remember(video.url) { mutableStateOf(false) }
    var loaded by remember(video.url) { mutableStateOf(false) }
    var decoded by remember(video.url) { mutableStateOf<coil3.Image?>(null) }
    val image = if (fallback) video.thumbnailUrl else highRes
    val share = LocalPlayerShare.current
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = videoLabel(video) }
        .clickable { share?.source(video.url, image.takeIf { loaded }, decoded); onClick() }.padding(horizontal = 20.dp)) {
        Column(Modifier.clearAndSetSemantics {}) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)) {
            // Drawn underneath, so the image covers it once loaded; a placeholder painter would be cropped.
            if (!loaded) Icon(if (failed) Icons.Rounded.ImageNotSupported else Icons.Rounded.Image,
                null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val thumbnail = @Composable { modifier: Modifier ->
                AsyncImage(image, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { loaded = true; decoded = it.result.image },
                    onError = { if (!fallback) fallback = true else failed = true })
            }
            // While this video opens, its thumbnail is flying to (or resting in) the player.
            if (share == null) thumbnail(Modifier)
            else androidx.compose.animation.AnimatedVisibility(!(share.departed && sameVideo(share.opening?.url, video.url)), enter = EnterTransition.None, exit = fadeOut(tween(FLIGHT_MS))) {
                with(share.scope) { thumbnail(Modifier.sharedElement(rememberSharedContentState(sharedThumbnailKey(video.url)), this@AnimatedVisibility, FlightBounds)) }
            }
            val duration = if (video.isLive) "LIVE" else formatDuration(video.durationSeconds)
            watchedFraction(video.resumePositionMs, video.durationSeconds, video.isLive)?.let { fraction ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = .35f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            if (duration != null) Text(duration, color = OverlayWhite, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = .8f), MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(listOfNotNull(video.channel.takeIf(String::isNotBlank), formatViews(video.viewCount), video.published)
            .joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private val thumbnailVideoId = Regex("(?:[?&]v=|/shorts/)([A-Za-z0-9_-]{11})")
internal fun highResThumbnail(videoUrl: String): String? =
    thumbnailVideoId.find(videoUrl)?.groupValues?.get(1)?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" }
