package dev.quietube.data

import java.net.URI

data class Chapter(val title: String, val startMs: Long)

/** One storyboard level: sprite pages of [columns]×[rows] preview frames, [frameMs] apart. Page URLs are signed; keep them in memory only. */
data class Storyboard(val pages: List<String>, val frameWidth: Int, val frameHeight: Int, val frameCount: Int, val frameMs: Long, val columns: Int, val rows: Int)

data class StoryboardFrame(val page: String, val left: Int, val top: Int, val width: Int, val height: Int)

internal fun Storyboard.frameAt(positionMs: Long): StoryboardFrame? {
    val perPage = columns * rows
    if (perPage <= 0 || frameMs <= 0 || frameCount <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
    val index = (positionMs.coerceAtLeast(0) / frameMs).coerceAtMost(frameCount - 1L).toInt()
    val page = pages.getOrNull(index / perPage) ?: return null
    val cell = index % perPage
    return StoryboardFrame(page, cell % columns * frameWidth, cell / columns * frameHeight, frameWidth, frameHeight)
}

/** The sharpest level no wider than [maxWidth], else the smallest usable one. */
internal fun chooseStoryboard(levels: List<Storyboard>, maxWidth: Int = 180): Storyboard? {
    val usable = levels.filter(::usableStoryboard)
    return usable.filter { it.frameWidth <= maxWidth }.maxByOrNull { it.frameWidth } ?: usable.minByOrNull { it.frameWidth }
}

internal fun usableStoryboard(level: Storyboard): Boolean = with(level) {
    frameWidth in 1..1280 && frameHeight in 1..720 && columns in 1..20 && rows in 1..20 && frameCount > 0 && frameMs > 0 &&
        pages.size in 1..1000 && pages.all(::storyboardImageUrl)
}

/** Storyboard sprites come from YouTube's image hosts; anything else is not loaded. */
internal fun storyboardImageUrl(url: String): Boolean = try {
    val uri = URI(url)
    val host = uri.host?.lowercase()
    uri.scheme == "https" && host != null && (host == "ytimg.com" || host.endsWith(".ytimg.com"))
} catch (_: Exception) { false }

/** Chapters in start order within the video, without blank titles or duplicate starts; fewer than two is no chapter list. */
internal fun normalizeChapters(chapters: List<Chapter>, durationMs: Long): List<Chapter> {
    val sorted = chapters.filter { it.title.isNotBlank() && it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
        .sortedBy { it.startMs }.distinctBy { it.startMs }.map { it.copy(title = it.title.trim().take(200)) }
    return if (sorted.size < 2) emptyList() else sorted
}

internal fun List<Chapter>.at(positionMs: Long): Chapter? = lastOrNull { it.startMs <= positionMs }
