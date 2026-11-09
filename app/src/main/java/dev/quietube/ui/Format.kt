package dev.quietube.ui

import dev.quietube.data.VideoError
import java.util.Locale

internal fun formatDuration(seconds: Long): String? = when {
    seconds < 0 -> null
    seconds >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else -> String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
internal fun displayLikeCount(accountCount: String?,publicCount: Long?): String =
    accountCount?.takeIf { it.isNotBlank() } ?: formatLikeCount(publicCount) ?: "—"
/** Dislikes arrive as digits (Return YouTube Dislike) or as YouTube's own short text. */
internal fun displayDislikeCount(count: String?,estimated: Boolean): String {
    val text = count?.toLongOrNull()?.let(::formatLikeCount) ?: count?.takeIf { it.isNotBlank() } ?: return "—"
    return if (estimated) "≈$text" else text
}
internal fun formatLikeCount(count: Long?): String? = when {
    count == null || count < 0 -> null
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}
internal fun formatViews(views: Long): String? = formatLikeCount(views)?.let { "$it views" }
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

internal fun watchedFraction(positionMs: Long?, durationSeconds: Long, live: Boolean = false): Float? {
    if (live || positionMs == null || durationSeconds <= 0) return null
    val fraction = (positionMs.toDouble() / durationSeconds / 1000).coerceIn(0.0, 1.0)
    return when { fraction < .02 -> null; fraction >= .95 -> 1f; else -> fraction.toFloat() }
}
internal fun videoLabel(video: dev.quietube.data.Video): String = listOfNotNull(video.title, video.channel.takeIf(String::isNotBlank),
    if (video.isLive) "Live" else formatDuration(video.durationSeconds), formatViews(video.viewCount), video.published,
    watchedFraction(video.resumePositionMs, video.durationSeconds, video.isLive)?.let { "${(it * 100).toInt()} percent watched" }).joinToString(", ")

internal fun formatPublishedDate(value: String?, locale: Locale = Locale.getDefault()): String? = value?.let {
    try { java.time.LocalDate.parse(it.take(10)).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)) }
    catch (_: java.time.format.DateTimeParseException) { it }
}
