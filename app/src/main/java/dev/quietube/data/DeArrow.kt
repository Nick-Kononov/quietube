package dev.quietube.data

import dev.quietube.links.VIDEO_ID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Read-only DeArrow client for crowd-sourced titles only. Thumbnails are left out: DeArrow renders them on a server
 * that receives the full video ID, while titles need only a 4-character hash prefix.
 */
@Singleton
class DeArrow internal constructor(private val base: HttpUrl) {
    @Inject constructor() : this("https://sponsor.ajay.app/".toHttpUrl())
    private val http = ThirdPartyHttp(MAX_BODY)
    private val cache = AnswerCache<String?>(CACHE_MS, capacity = 500)
    // A feed page asks for many titles at once; a few at a time is plenty and polite to a volunteer-run service.
    private val requests = Semaphore(3)

    /** The community title for [videoId], or null when there's no trusted one (or the service couldn't be reached). */
    suspend fun title(videoId: String): String? = withContext(Dispatchers.IO) {
        require(VIDEO_ID.matches(videoId))
        cache.get(videoId)?.let { return@withContext it.value }
        requests.withPermit {
            cache.get(videoId)?.let { return@withPermit it.value }
            try {
                val url = base.newBuilder().addPathSegments("api/branding").addPathSegment(hashPrefix(videoId)).build()
                val title = http.get(url)?.let { parseBrandingTitle(it, videoId) }
                cache.put(videoId, title)
                title
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }

    private companion object {
        const val MAX_BODY = 512 * 1024L
        const val CACHE_MS = 60 * 60 * 1000L
    }
}

/**
 * [videoId]'s best title from a branding response, following DeArrow's API rules: the first entry is the best, and is
 * trusted only when locked or not voted down. An `original` entry means the video's own title won.
 */
internal fun parseBrandingTitle(json: String, videoId: String): String? {
    val entry = Json.parseToJsonElement(json).jsonObject[videoId] as? JsonObject ?: return null
    val best = (entry["titles"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    fun field(name: String) = best[name] as? JsonPrimitive
    if (field("original")?.booleanOrNull == true) return null
    val trusted = field("locked")?.booleanOrNull == true || (field("votes")?.intOrNull ?: -1) >= 0
    return field("title")?.takeIf { it.isString && trusted }?.content?.let(::cleanTitle)
}

/** Drops DeArrow's ">" keep-formatting markers (there's no auto-formatter here) and anything that can't be a title. */
internal fun cleanTitle(raw: String): String? =
    raw.replace(Regex("(^|\\s)>(?=\\S)"), "$1").filterNot { it.isISOControl() }.trim().take(300).takeIf { it.isNotBlank() }
