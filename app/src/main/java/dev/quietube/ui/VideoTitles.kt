package dev.quietube.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.quietube.links.videoId

/** DeArrow's replacement titles; reading [replacements] or [enabled] in composition follows their changes. */
@Stable
internal class VideoTitles(val replacements: () -> Map<String, String>, val enabled: () -> Boolean, val request: (String) -> Unit)

internal val LocalVideoTitles = staticCompositionLocalOf<VideoTitles?> { null }

/** The title to show for [url]: DeArrow's while it's on and has a trusted one, otherwise [original]. */
@Composable
internal fun displayTitle(url: String?, original: String): String {
    val titles = LocalVideoTitles.current ?: return original
    val id = videoId(url) ?: return original
    val on = titles.enabled()
    LaunchedEffect(id, on) { if (on) titles.request(id) }
    return if (on) titles.replacements()[id] ?: original else original
}
