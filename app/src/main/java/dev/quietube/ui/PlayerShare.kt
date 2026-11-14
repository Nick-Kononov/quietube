package dev.quietube.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * [decoded]: the card's loaded thumbnail, drawn as is so the flight shows it from its first frame.
 * [fade]: the player wasn't already open, so it fades in around the flying thumbnail.
 */
internal data class Opening(val url: String, val image: String, val decoded: coil3.Image? = null, val fade: Boolean = true)

/**
 * Opening a video from a card flies the card's thumbnail into the player's video area (a shared element). The
 * thumbnail then stays there as a poster until playback starts, so the empty video surface never shows.
 */
@Stable
@OptIn(ExperimentalSharedTransitionApi::class)
internal class PlayerShare(val scope: SharedTransitionScope) {
    /** The video being opened from a card; its card hides its own thumbnail meanwhile. */
    var opening by mutableStateOf<Opening?>(null); private set
    /** True until the thumbnail has landed; only then may either end drop its shared element. */
    var flying by mutableStateOf(false); private set
    /**
     * The player layer has drawn once. Its first frame is slow (the whole player composes), and a flight started
     * before it would sit at the card for that frame and then jump, so both ends switch over only after it.
     */
    var departed by mutableStateOf(false); private set
    private var source: Opening? = null

    /** A card is about to open [url]; remembered until the player confirms it. */
    fun source(url: String, image: String?, decoded: coil3.Image? = null) { source = image?.let { Opening(url, it, decoded) } }
    /** Starts the flight when [url] came from a card; returns whether it did. */
    fun begin(url: String, animate: Boolean, fade: Boolean): Boolean {
        val card = source?.takeIf { animate && sameVideo(it.url, url) }?.copy(fade = fade)
        source = null
        opening = card
        flying = card != null
        departed = false
        return card != null
    }
    fun depart() { if (opening != null) departed = true }
    fun landed() { flying = false }
    fun finish() { opening = null; flying = false; departed = false }
}

/**
 * Both ends keep their visibility transitions running for the whole flight: with instant (None) transitions the
 * shared bounds animation can attach too late and the thumbnail jumps instead of flying.
 */
internal const val FLIGHT_MS = 380
@OptIn(ExperimentalSharedTransitionApi::class)
internal val FlightBounds = BoundsTransform { _, _ -> tween(FLIGHT_MS, easing = FastOutSlowInEasing) }

internal val LocalPlayerShare = staticCompositionLocalOf<PlayerShare?> { null }

private val videoIdPattern = Regex("(?:[?&]v=|/shorts/|/live/|youtu\\.be/)([A-Za-z0-9_-]{11})")
internal fun videoId(url: String?): String? = url?.let { videoIdPattern.find(it)?.groupValues?.get(1) }
internal fun sameVideo(a: String?, b: String?): Boolean = a != null && b != null && (a == b || videoId(a)?.let { it == videoId(b) } == true)
internal fun sharedThumbnailKey(url: String): String = "thumbnail:${videoId(url) ?: url}"
