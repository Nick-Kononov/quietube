package dev.quietube.account

import dev.quietube.data.*
import dev.quietube.links.PLAYLIST_ID
import dev.quietube.links.VIDEO_ID
import kotlinx.serialization.json.*

class ParsedAccountPage(val videos: List<Video>, val playlists: List<PlaylistSummary>,
    val token: String?, val skipped: Boolean)

/** Traverse known content renderers; ignore unfamiliar UI and isolate malformed items. */
object AccountPageParser {
    private val countText = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([KMB])?", RegexOption.IGNORE_CASE)
    private val adKeys = setOf("adSlotRenderer", "promotedSparklesWebRenderer", "displayAdRenderer", "promotedVideoRenderer", "playerAds", "adPlacements")
    private val playlistLockups = setOf("LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST")
    fun parse(root: JsonObject): ParsedAccountPage {
        val tabs = root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")?.arr("tabs")
        val selected = tabs?.mapNotNull { (it as? JsonObject)?.obj("tabRenderer") }
            ?.firstOrNull { it["selected"] == JsonPrimitive(true) }?.get("content")
        // TV continuation pages also repeat a stub "contents"; the new items are only in continuationContents.
        val base = root["continuationContents"] ?: selected ?: root["contents"] ?: root
        val queue = ArrayDeque<Pair<JsonElement, Int>>()
        queue.add(base to 0)
        val videos = linkedMapOf<String, Video>(); val playlists = linkedMapOf<String, PlaylistSummary>()
        var token: String? = null; var skipped = false
        while (queue.isNotEmpty()) {
            val (element, depth) = queue.removeFirst()
            if (depth > 80) { skipped = true; continue }
            when (element) {
                is JsonArray -> element.forEach { queue.add(it to depth + 1) }
                is JsonObject -> element.forEach { (key, child) ->
                    if (key in adKeys) return@forEach
                    val renderer = child as? JsonObject
                    try {
                        when (key) {
                            "tileRenderer" -> if (renderer != null) {
                                val video = tileVideo(renderer)
                                // TV navigation tiles may have no video/playlist; these are expected UI.
                                if (video != null) videos.putIfAbsent(video.url, video)
                                else tilePlaylist(renderer)?.let { playlists.putIfAbsent(it.id, it) }
                            }
                            "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> {
                                val video = renderer?.let(::legacyVideo)
                                if (video != null) videos.putIfAbsent(video.url, video) else skipped = true
                            }
                            "playlistRenderer", "gridPlaylistRenderer" -> {
                                val playlist = renderer?.let(::legacyPlaylist)
                                if (playlist != null) playlists.putIfAbsent(playlist.id, playlist) else skipped = true
                            }
                            "lockupViewModel" -> if (renderer != null) {
                                val video = lockupVideo(renderer)
                                val playlist = if (video == null) lockupPlaylist(renderer) else null
                                when {
                                    video != null -> videos.putIfAbsent(video.url, video)
                                    playlist != null -> playlists.putIfAbsent(playlist.id, playlist)
                                    renderer.str("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" -> skipped = true
                                }
                            }
                            "continuationItemRenderer" -> {
                                token = token ?: renderer?.obj("continuationEndpoint")?.obj("continuationCommand")?.str("token")
                            }
                            "nextContinuationData", "reloadContinuationData" -> token = token ?: renderer?.str("continuation")
                            else -> queue.add(child to depth + 1)
                        }
                    } catch (_: Exception) { skipped = true }
                }
                else -> Unit
            }
        }
        return ParsedAccountPage(videos.values.toList(), playlists.values.toList(), token, skipped)
    }
    private fun tileTitle(renderer: JsonObject): String =
        text(renderer.obj("metadata")?.obj("tileMetadataRenderer")?.get("title") ?: renderer.obj("header")?.obj("tileHeaderRenderer")?.get("title"))
    private fun tileVideo(renderer: JsonObject): Video? {
        val command = renderer.obj("onSelectCommand")
        // TV lists Shorts with reelWatchEndpoint; they are ordinary watch URLs, flagged so feeds can hide them.
        val id = (command?.obj("watchEndpoint") ?: command?.obj("reelWatchEndpoint"))?.str("videoId")?.takeIf(VIDEO_ID::matches) ?: return null
        val title = tileTitle(renderer).takeIf { it.isNotBlank() } ?: return null
        val header = renderer.obj("header")?.obj("tileHeaderRenderer")
        val lines = values(renderer.obj("metadata")?.obj("tileMetadataRenderer")?.get("lines"), "lineItemRenderer")
            .mapNotNull { (it as? JsonObject)?.get("text") }.map(::text).filter { it.isNotBlank() }
        val seconds = duration(values(header, "thumbnailOverlayTimeStatusRenderer")
            .map { text((it as? JsonObject)?.get("text")) }.firstOrNull { duration(it) >= 0 })
        return Video(watchUrl(id), title,
            lines.firstOrNull { !it.contains("view", true) && !it.contains("ago", true) }.orEmpty(),
            thumbnail(header?.get("thumbnail")), seconds, count(lines.firstOrNull { it.contains("view", true) }),
            lines.firstOrNull { it.contains("ago", true) }, isLive(header, "style"),
            resumePosition(header?.let(::findPercent), seconds), isShort = isShort(renderer))
    }
    /** TV library tiles open playlists by browsing "VL<id>" rather than watching. */
    private fun tilePlaylist(renderer: JsonObject): PlaylistSummary? {
        val command = renderer.obj("onSelectCommand")
        val id = command?.obj("watchEndpoint")?.str("playlistId") ?: command?.obj("watchPlaylistEndpoint")?.str("playlistId")
            ?: command?.obj("browseEndpoint")?.str("browseId")
                ?.takeIf { renderer.str("contentType") == "TILE_CONTENT_TYPE_PLAYLIST" && it.startsWith("VL") }?.removePrefix("VL")
        val title = tileTitle(renderer)
        if (id == null || !PLAYLIST_ID.matches(id) || title.isBlank()) return null
        val header = renderer.obj("header")?.obj("tileHeaderRenderer")
        val size = values(header, "thumbnailOverlayTimeStatusRenderer")
            .map { text((it as? JsonObject)?.get("text")) }.firstOrNull { it.contains("video", true) }
        return PlaylistSummary(id, title, thumbnail(header), size)
    }
    private fun lockupTitle(renderer: JsonObject): String = text(renderer.obj("metadata")?.obj("lockupMetadataViewModel")?.get("title"))
    private fun lockupVideo(renderer: JsonObject): Video? {
        if (renderer.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = renderer.str("contentId")?.takeIf(VIDEO_ID::matches) ?: return null
        val title = lockupTitle(renderer).takeIf { it.isNotBlank() } ?: return null
        val parts = values(renderer.obj("metadata")?.obj("lockupMetadataViewModel")?.get("metadata"), "metadataParts")
            .flatMap { it as? JsonArray ?: JsonArray(emptyList()) }
            .mapNotNull { (it as? JsonObject)?.get("text") }.map(::text).filter { it.isNotBlank() }
        val seconds = duration(values(renderer["contentImage"], "thumbnailBadgeViewModel")
            .mapNotNull { (it as? JsonObject)?.str("text") }.firstOrNull { duration(it) >= 0 })
        return Video(watchUrl(id), title, parts.firstOrNull().orEmpty(),
            thumbnail(renderer["contentImage"]), seconds, count(parts.firstOrNull { it.contains("view", true) }),
            parts.firstOrNull { it.contains("ago", true) || it.contains("streamed", true) }, isLive(renderer, "badgeStyle"),
            resumePosition(findPercent(renderer), seconds), isShort = isShort(renderer))
    }
    private fun lockupPlaylist(renderer: JsonObject): PlaylistSummary? {
        val id = renderer.str("contentId")?.takeIf(PLAYLIST_ID::matches) ?: return null
        val title = lockupTitle(renderer)
        if (renderer.str("contentType") !in playlistLockups || title.isBlank()) return null
        return PlaylistSummary(id, title, thumbnail(renderer["contentImage"]), null)
    }
    private fun isLive(element: JsonElement?, styleKey: String) = values(element, styleKey).any { (it as? JsonPrimitive)?.content?.contains("LIVE") == true }
    private fun resumePosition(percent: Long?, seconds: Long): Long? = percent?.takeIf { seconds > 0 }?.let { seconds * 1000 * it / 100 }
    private fun legacyVideo(renderer: JsonObject): Video? {
        val id = renderer.str("videoId")?.takeIf(VIDEO_ID::matches) ?: return null
        val title = text(renderer["title"]).takeIf { it.isNotBlank() } ?: return null
        val seconds = duration(text(renderer["lengthText"]).ifBlank {
            values(renderer["thumbnailOverlays"], "thumbnailOverlayTimeStatusRenderer")
                .map { text((it as? JsonObject)?.get("text")) }.firstOrNull().orEmpty()
        })
        return Video(watchUrl(id), title, text(renderer["ownerText"] ?: renderer["shortBylineText"] ?: renderer["longBylineText"]),
            thumbnail(renderer["thumbnail"]), seconds, count(text(renderer["viewCountText"] ?: renderer["shortViewCountText"])),
            text(renderer["publishedTimeText"]).takeIf { it.isNotBlank() },
            isLive(renderer, "style"), resumePosition(findPercent(renderer), seconds), isShort = isShort(renderer))
    }
    /** Shorts open through reelWatchEndpoint or a /shorts/ URL, or carry the SHORTS overlay style; one walk checks all three. */
    private fun isShort(renderer: JsonObject): Boolean {
        fun walk(e: JsonElement?, depth: Int): Boolean = depth <= 30 && when (e) {
            is JsonArray -> e.any { walk(it, depth + 1) }
            is JsonObject -> e.any { (key, value) -> when (key) {
                "reelWatchEndpoint" -> true
                "url" -> (value as? JsonPrimitive)?.contentOrNull?.startsWith("/shorts/") == true
                "style" -> (value as? JsonPrimitive)?.contentOrNull == "SHORTS"
                else -> walk(value, depth + 1)
            } }
            else -> false
        }
        return walk(renderer, 0)
    }
    private fun legacyPlaylist(renderer: JsonObject): PlaylistSummary? {
        val id = renderer.str("playlistId")?.takeIf(PLAYLIST_ID::matches) ?: return null
        val title = text(renderer["title"]).takeIf { it.isNotBlank() } ?: return null
        return PlaylistSummary(id, title, thumbnail(renderer["thumbnail"] ?: renderer["thumbnails"]),
            text(renderer["videoCountText"]).ifBlank { renderer.str("videoCount").orEmpty() }.takeIf { it.isNotBlank() })
    }
    internal fun text(element: JsonElement?): String = when(element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> element.str("simpleText") ?: element.str("content")
            ?: element.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }.orEmpty()
        else -> ""
    }
    private fun thumbnail(element: JsonElement?): String? {
        val candidates = mutableListOf<JsonObject>()
        fun walk(e: JsonElement?, depth: Int) {
            if (depth > 20) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> { if (e.str("url")?.startsWith("https://") == true) candidates += e; e.values.forEach { walk(it, depth + 1) } }
                else -> Unit
            }
        }
        walk(element, 0)
        return candidates.maxByOrNull { it.str("width")?.toIntOrNull() ?: 0 }?.str("url")
    }
    /** Every value stored under [key] anywhere below [element]. */
    internal fun values(element: JsonElement?, key: String): List<JsonElement> {
        val found = mutableListOf<JsonElement>()
        fun walk(e: JsonElement?, depth: Int) {
            if (depth > 30) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> e.forEach { (k,v) -> if (k == key) found += v else walk(v, depth + 1) }
                else -> Unit
            }
        }
        walk(element, 0); return found
    }
    private fun findPercent(element: JsonElement): Long? = values(element, "percentDurationWatched")
        .firstNotNullOfOrNull { (it as? JsonPrimitive)?.longOrNull }?.coerceIn(0,100)
    internal fun duration(value: String?): Long {
        val parts = value?.trim()?.split(':') ?: return -1
        if (parts.size !in 2..3 || parts.any { it.toLongOrNull() == null }) return -1
        return parts.fold(0L) { result, part -> result * 60 + part.toLong() }.coerceAtMost(31_536_000)
    }
    private fun count(value: String?): Long {
        val match = countText.find(value?.replace(",", "").orEmpty()) ?: return -1
        val multiplier = when (match.groupValues[2].uppercase()) { "K" -> 1_000; "M" -> 1_000_000; "B" -> 1_000_000_000; else -> 1 }
        return (match.groupValues[1].toDouble() * multiplier).toLong()
    }
    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
}
