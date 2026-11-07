package dev.quietube.account

import dev.quietube.data.*
import kotlinx.serialization.json.*

class ParsedAccountPage(val videos: List<Video>, val playlists: List<PlaylistSummary>,
    val token: String?, val skipped: Boolean)

/** Traverse known content renderers; ignore unfamiliar UI and isolate malformed items. */
object AccountPageParser {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val playlistId = Regex("[A-Za-z0-9_-]{2,150}")
    fun parse(root: JsonObject): ParsedAccountPage {
        val tabs = root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")?.arr("tabs")
        val selected = tabs?.mapNotNull { (it as? JsonObject)?.obj("tabRenderer") }
            ?.firstOrNull { it["selected"] == JsonPrimitive(true) }?.get("content")
        val base = selected ?: root["contents"] ?: root
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
                    if (key in setOf("adSlotRenderer", "promotedSparklesWebRenderer", "displayAdRenderer", "promotedVideoRenderer", "playerAds", "adPlacements")) return@forEach
                    val renderer = child as? JsonObject
                    try {
                        when (key) {
                            "tileRenderer" -> {
                                if (renderer != null) {
                                    val command = renderer.obj("onSelectCommand")
                                    val id = command?.obj("watchEndpoint")?.str("videoId")
                                    val meta = renderer.obj("metadata")?.obj("tileMetadataRenderer")
                                    val header = renderer.obj("header")?.obj("tileHeaderRenderer")
                                    val title = text(meta?.get("title") ?: header?.get("title"))
                                    if (id != null && videoId.matches(id) && title.isNotBlank()) {
                                        val lines = objects(meta?.get("lines"),"lineItemRenderer")
                                            .mapNotNull { (it as? JsonObject)?.get("text") }.map(::text).filter { it.isNotBlank() }
                                        val seconds = duration(objects(header,"thumbnailOverlayTimeStatusRenderer")
                                            .map { text((it as? JsonObject)?.get("text")) }.firstOrNull { duration(it) >= 0 })
                                        val percent = header?.let(::findPercent)
                                        videos.putIfAbsent(watchUrl(id),Video(watchUrl(id),title,
                                            lines.firstOrNull { !it.contains("view",true) && !it.contains("ago",true) }.orEmpty(),
                                            thumbnail(header?.get("thumbnail")),seconds,count(lines.firstOrNull { it.contains("view",true) }),
                                            lines.firstOrNull { it.contains("ago",true) },
                                            objects(header,"style").any { (it as? JsonPrimitive)?.content?.contains("LIVE") == true },
                                            percent?.let { if (seconds > 0) seconds*1000*it/100 else null }))
                                    } else {
                                        val playlist = command?.obj("watchEndpoint")?.str("playlistId") ?: command?.obj("watchPlaylistEndpoint")?.str("playlistId")
                                        if (playlist != null && playlistId.matches(playlist) && title.isNotBlank())
                                            playlists.putIfAbsent(playlist,PlaylistSummary(playlist,title,thumbnail(header),null))
                                        // TV navigation tiles may have no video/playlist; these are expected UI.
                                    }
                                }
                            }
                            "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> {
                                val video = renderer?.let(::legacyVideo)
                                if (video != null) videos.putIfAbsent(video.url, video) else skipped = true
                            }
                            "playlistRenderer", "gridPlaylistRenderer" -> {
                                val playlist = renderer?.let(::legacyPlaylist)
                                if (playlist != null) playlists.putIfAbsent(playlist.id, playlist) else skipped = true
                            }
                            "lockupViewModel" -> {
                                if (renderer != null) {
                                    val id = renderer.str("contentId")
                                    val type = renderer.str("contentType")
                                    val metadata = renderer.obj("metadata")?.obj("lockupMetadataViewModel")
                                    val title = text(metadata?.get("title"))
                                    if (type == "LOCKUP_CONTENT_TYPE_VIDEO" && id != null && videoId.matches(id) && title.isNotBlank()) {
                                        val parts = objects(metadata?.get("metadata"), "metadataParts").flatMap { it as? JsonArray ?: JsonArray(emptyList()) }
                                            .mapNotNull { (it as? JsonObject)?.get("text") }.map(::text).filter { it.isNotBlank() }
                                        val durationText = objects(renderer["contentImage"], "thumbnailBadgeViewModel")
                                            .mapNotNull { (it as? JsonObject)?.str("text") }.firstOrNull { duration(it) >= 0 }
                                        val seconds = duration(durationText)
                                        val percent = findPercent(renderer)
                                        val live = objects(renderer, "badgeStyle").any { (it as? JsonPrimitive)?.content?.contains("LIVE") == true }
                                        videos.putIfAbsent(watchUrl(id), Video(watchUrl(id), title, parts.firstOrNull().orEmpty(),
                                            thumbnail(renderer["contentImage"]), seconds, count(parts.firstOrNull { it.contains("view", true) }),
                                            parts.firstOrNull { it.contains("ago", true) || it.contains("streamed", true) }, live,
                                            resumePositionMs = percent?.let { if (seconds > 0) seconds * 1000 * it / 100 else null }))
                                    } else if (type in setOf("LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST") && id != null && playlistId.matches(id) && title.isNotBlank()) {
                                        playlists.putIfAbsent(id, PlaylistSummary(id, title, thumbnail(renderer["contentImage"]), null))
                                    } else if (type == "LOCKUP_CONTENT_TYPE_VIDEO") skipped = true
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
    private fun legacyVideo(renderer: JsonObject): Video? {
        val id = renderer.str("videoId")?.takeIf(videoId::matches) ?: return null
        val title = text(renderer["title"]).takeIf { it.isNotBlank() } ?: return null
        val seconds = duration(text(renderer["lengthText"]).ifBlank {
            objects(renderer["thumbnailOverlays"], "thumbnailOverlayTimeStatusRenderer")
                .map { text((it as? JsonObject)?.get("text")) }.firstOrNull().orEmpty()
        })
        val percent = findPercent(renderer)
        return Video(watchUrl(id), title, text(renderer["ownerText"] ?: renderer["shortBylineText"] ?: renderer["longBylineText"]),
            thumbnail(renderer["thumbnail"]), seconds, count(text(renderer["viewCountText"] ?: renderer["shortViewCountText"])),
            text(renderer["publishedTimeText"]).takeIf { it.isNotBlank() },
            objects(renderer, "style").any { (it as? JsonPrimitive)?.content?.contains("LIVE") == true },
            percent?.let { if (seconds > 0) seconds * 1000 * it / 100 else null })
    }
    private fun legacyPlaylist(renderer: JsonObject): PlaylistSummary? {
        val id = renderer.str("playlistId")?.takeIf(playlistId::matches) ?: return null
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
    internal fun objects(element: JsonElement?, key: String): List<JsonElement> {
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
    private fun findPercent(element: JsonElement): Long? = objects(element, "percentDurationWatched")
        .firstNotNullOfOrNull { (it as? JsonPrimitive)?.longOrNull }?.coerceIn(0,100)
    internal fun duration(value: String?): Long {
        val parts = value?.trim()?.split(':') ?: return -1
        if (parts.size !in 2..3 || parts.any { it.toLongOrNull() == null }) return -1
        return parts.fold(0L) { result, part -> result * 60 + part.toLong() }.coerceAtMost(31_536_000)
    }
    private fun count(value: String?): Long {
        val match = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([KMB])?", RegexOption.IGNORE_CASE)
            .find(value?.replace(",", "").orEmpty()) ?: return -1
        val multiplier = when (match.groupValues[2].uppercase()) { "K" -> 1_000; "M" -> 1_000_000; "B" -> 1_000_000_000; else -> 1 }
        return (match.groupValues[1].toDouble() * multiplier).toLong()
    }
    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
}
