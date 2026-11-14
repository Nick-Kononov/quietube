package dev.quietube.data

import dev.quietube.links.VIDEO_ID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class SegmentMode { Skip, Show, Off }

/** SponsorBlock's skippable categories. Filler marks a lot; its docs advise clients not to use it by default. */
enum class SegmentCategory(val key: String, val label: String, val detail: String, val default: SegmentMode) {
    Sponsor("sponsor", "Sponsor", "Paid promotions, paid referrals and direct ads.", SegmentMode.Skip),
    SelfPromo("selfpromo", "Self-promotion", "Merch, donations and the creator's other projects.", SegmentMode.Show),
    Interaction("interaction", "Interaction reminder", "Reminders to like, subscribe or follow.", SegmentMode.Show),
    Intro("intro", "Intro", "Intro animations and pauses with no content.", SegmentMode.Show),
    Outro("outro", "Endcards and credits", "Credits and end screens.", SegmentMode.Show),
    Preview("preview", "Preview or recap", "Clips of what's coming up or what came before.", SegmentMode.Show),
    Filler("filler", "Filler tangent", "Tangents and jokes not needed for the main content. Marks a lot.", SegmentMode.Off),
}

data class SkipSegment(val category: SegmentCategory, val startMs: Long, val endMs: Long)

/** Read-only SponsorBlock client: sends a 4-character hash prefix of the video ID, never submits or votes. */
@Singleton
class SponsorBlock internal constructor(private val base: HttpUrl) {
    @Inject constructor() : this("https://sponsor.ajay.app/".toHttpUrl())
    private val http = ThirdPartyHttp(MAX_BODY)
    private val cache = AnswerCache<List<SkipSegment>>(CACHE_MS, capacity = 100)

    /** Every category's skip segments for [videoId], filtered locally by the viewer's choices; null if unreachable. */
    suspend fun segments(videoId: String): List<SkipSegment>? = withContext(Dispatchers.IO) {
        require(VIDEO_ID.matches(videoId))
        cache.get(videoId)?.let { return@withContext it.value }
        try {
            // All categories every time, so the request says nothing about which ones this viewer skips.
            val url = base.newBuilder().addPathSegments("api/skipSegments").addPathSegment(hashPrefix(videoId)).apply {
                SegmentCategory.entries.forEach { addQueryParameter("category", it.key) }
                addQueryParameter("actionType", "skip")
            }.build()
            val segments = http.get(url)?.let { parseSegments(it, videoId) } ?: emptyList()
            cache.put(videoId, segments)
            segments
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private companion object {
        // A prefix bucket holds a few dozen videos' segments; far below this.
        const val MAX_BODY = 512 * 1024L
        const val CACHE_MS = 30 * 60 * 1000L
    }
}

private val serviceJson = Json { ignoreUnknownKeys = true }
@Serializable private class SegmentsEntry(val videoID: String = "", val segments: List<RawSegment> = emptyList())
@Serializable private class RawSegment(val segment: List<Double> = emptyList(), val category: String = "", val actionType: String = "")

/** Keeps only [videoId]'s well-formed skip segments in known categories, in order. */
internal fun parseSegments(json: String, videoId: String): List<SkipSegment> =
    serviceJson.decodeFromString(ListSerializer(SegmentsEntry.serializer()), json).filter { it.videoID == videoId }
        .flatMap { it.segments }.mapNotNull { raw ->
            val category = SegmentCategory.entries.firstOrNull { it.key == raw.category }
            val start = raw.segment.getOrNull(0)
            val end = raw.segment.getOrNull(1)
            if (category == null || raw.actionType != "skip" || raw.segment.size != 2 || start == null || end == null || start < 0 || end - start < .5) null
            else SkipSegment(category, (start * 1000).toLong(), (end * 1000).toLong())
        }.distinct().sortedBy { it.startMs }.take(100)

/**
 * The segment to skip now: playback has just run into its start, between the previous check at [previousMs] and
 * [positionMs]. After a seek the caller resets [previousMs] to the new position, so seeking into a segment plays it.
 */
internal fun segmentToSkip(segments: List<SkipSegment>, skip: Set<SegmentCategory>, previousMs: Long, positionMs: Long, ignored: Set<SkipSegment>): SkipSegment? =
    segments.firstOrNull { it.category in skip && it !in ignored && previousMs <= it.startMs && positionMs >= it.startMs && positionMs < it.endMs - 300 }

/** Where a skip of [segment] lands: past any further skipped segments that overlap or touch it. */
internal fun skipEnd(segment: SkipSegment, segments: List<SkipSegment>, skip: Set<SegmentCategory>, ignored: Set<SkipSegment>): Long {
    var end = segment.endMs
    while (true) end = segments.filter { it.category in skip && it !in ignored && it.startMs <= end + 200 && it.endMs > end }.maxOfOrNull { it.endMs } ?: return end
}

/** The show-only segment playing at [positionMs], offered as a manual Skip. */
internal fun manualSegment(segments: List<SkipSegment>, skip: Set<SegmentCategory>, positionMs: Long): SkipSegment? =
    segments.firstOrNull { it.category !in skip && positionMs >= it.startMs && positionMs < it.endMs - 1000 }
