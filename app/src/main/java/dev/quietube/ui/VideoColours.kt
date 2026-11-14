package dev.quietube.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accent roles of Material's content scheme for one thumbnail, as ARGB. Surfaces stay the app's own. */
internal data class VideoAccents(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int, val surfaceTint: Int,
)

/** Picks the thumbnail's most suitable colour and builds accents from it; null for images too grey to pick from. */
internal fun videoAccents(pixels: IntArray, dark: Boolean): VideoAccents? {
    if (pixels.isEmpty()) return null
    val seed = Score.score(QuantizerCelebi.quantize(pixels, 128), 1, null, true).firstOrNull() ?: return null
    val s = SchemeContent(Hct.fromInt(seed), dark, 0.0)
    return VideoAccents(s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary,
        s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
        s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer, s.surfaceTint)
}

internal fun ColorScheme.withAccents(a: VideoAccents): ColorScheme = copy(
    primary = Color(a.primary), onPrimary = Color(a.onPrimary), primaryContainer = Color(a.primaryContainer),
    onPrimaryContainer = Color(a.onPrimaryContainer), inversePrimary = Color(a.inversePrimary),
    secondary = Color(a.secondary), onSecondary = Color(a.onSecondary), secondaryContainer = Color(a.secondaryContainer),
    onSecondaryContainer = Color(a.onSecondaryContainer), tertiary = Color(a.tertiary), onTertiary = Color(a.onTertiary),
    tertiaryContainer = Color(a.tertiaryContainer), onTertiaryContainer = Color(a.onTertiaryContainer), surfaceTint = Color(a.surfaceTint))

/** Results per thumbnail and theme, including "no usable colour", so reopening a video doesn't recompute. */
private object AccentCache {
    private val entries = object : LinkedHashMap<String, VideoAccents?>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoAccents?>) = size > 40
    }
    fun get(key: String): Result<VideoAccents?>? = synchronized(entries) { if (entries.containsKey(key)) Result.success(entries[key]) else null }
    fun put(key: String, value: VideoAccents?) = synchronized(entries) { entries[key] = value }
}

/** The current video's accents, or null (the app's colours) until they are computed or when turned off. */
@Composable
internal fun rememberVideoAccents(image: String?, dark: Boolean, enabled: Boolean): VideoAccents? {
    val context = LocalContext.current
    val key = image?.let { "$dark|$it" }
    val cached = key?.takeIf { enabled }?.let(AccentCache::get)?.getOrNull()
    return produceState(cached, key, enabled) {
        value = cached
        if (!enabled || key == null || AccentCache.get(key) != null) return@produceState
        val request = ImageRequest.Builder(context).data(image).size(112, 63).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        val accents = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            videoAccents(pixels, dark)
        }
        AccentCache.put(key, accents)
        value = accents
    }.value
}
