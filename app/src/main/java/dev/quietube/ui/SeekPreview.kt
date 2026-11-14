package dev.quietube.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import dev.quietube.data.Chapter
import dev.quietube.data.Storyboard
import dev.quietube.data.at
import dev.quietube.data.frameAt
import kotlin.math.roundToInt

/** Where Media3's time bar draws its track inside the PlayerView, in pixels. */
internal data class TimeBarSpan(val left: Float, val right: Float, val top: Float)

internal fun scrubX(span: TimeBarSpan, position: Long, duration: Long): Float =
    if (duration <= 0) span.left else span.left + (span.right - span.left) * (position.toFloat() / duration).coerceIn(0f, 1f)

/** Centres a preview of [width] over [centre] without letting it leave a container [container] wide. */
internal fun previewLeft(centre: Float, width: Float, container: Float): Float = (centre - width / 2f).coerceIn(0f, (container - width).coerceAtLeast(0f))

/** The frame preview, chapter and time shown above the time bar while scrubbing. */
@Composable
internal fun ScrubPreview(storyboard: Storyboard?, chapters: List<Chapter>, position: Long, duration: Long, span: TimeBarSpan, modifier: Modifier = Modifier) {
    val frame = storyboard?.frameAt(position)
    val page = rememberStoryboardPage(frame?.page)
    Layout(content = {
        Column(Modifier.clearAndSetSemantics {}.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (frame != null) Box(Modifier.width(160.dp).aspectRatio(frame.width.toFloat() / frame.height).clip(RoundedCornerShape(8.dp)).background(VideoBlack)) {
                val image = page?.takeIf { it.first == frame.page }?.second
                if (image != null && frame.left + frame.width <= image.width && frame.top + frame.height <= image.height) Canvas(Modifier.fillMaxSize()) {
                    drawImage(image, IntOffset(frame.left, frame.top), IntSize(frame.width, frame.height),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
                }
            }
            chapters.at(position)?.let { Text(it.title, color = OverlayWhite, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp).padding(horizontal = 4.dp, vertical = 2.dp)) }
            Text(formatDuration(position / 1000) ?: "", color = OverlayWhite, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val preview = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = previewLeft(scrubX(span, position, duration), preview.width.toFloat(), constraints.maxWidth.toFloat())
            preview.place(x.roundToInt(), (span.top - preview.height - 8.dp.toPx()).roundToInt().coerceAtLeast(0))
        }
    }
}

/** The chapter playing now, just above the time bar while the controls show. */
@Composable
internal fun ChapterLabel(title: String, span: TimeBarSpan, modifier: Modifier = Modifier) {
    Layout(content = {
        Text(title, color = OverlayWhite, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
    }, modifier = modifier.fillMaxSize()) { measurables, constraints ->
        val label = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (span.right - span.left).roundToInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            label.place(span.left.roundToInt(), (span.top - label.height - 4.dp.toPx()).roundToInt().coerceAtLeast(0))
        }
    }
}

/** Loads one storyboard sprite through the anonymous image loader; signed sprite URLs stay out of the disk cache. */
@Composable
private fun rememberStoryboardPage(url: String?): Pair<String, ImageBitmap>? {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    LaunchedEffect(url) {
        if (url == null || shown?.first == url) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).diskCachePolicy(CachePolicy.DISABLED).build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.let { shown = url to it }
    }
    return shown
}
