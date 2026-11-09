package dev.quietube.account

import dev.quietube.data.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AccountCursor(val browseId: String, val token: String, val revision: Long) : Continuation
@Singleton
class InnerTubeAccountSource @Inject constructor(private val client: InnerTubeClient, private val sessions: EncryptedSessionStore, private val actions: dev.quietube.account.actions.YouTubeActions) : AccountSource {
    override suspend fun engagement(url: String) = actions.engagement(url)
    override suspend fun rate(url: String,rating: Rating,previous: Rating) = actions.rate(url,rating,previous).also { if (it is Outcome.Success) clear() }
    override suspend fun subscribe(channelId: String,subscribe: Boolean) = actions.subscribe(channelId,subscribe).also { if (it is Outcome.Success) clear() }
    override suspend fun startWatch(url: String,position: Double) = actions.startWatch(url,position)
    override suspend fun reportWatch(ticket: WatchTicket,segments: List<Pair<Double,Double>>,position: Double,final: Boolean) = actions.reportWatch(ticket,segments,position,final).also { if (it is Outcome.Success) cache.clear() }
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, VideoPage>>()
    private var revision = -1L
    override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> {
        val id = when (feed) {
            AccountFeed.Home -> "FEwhat_to_watch"
            AccountFeed.Subscriptions -> "FEsubscriptions"
            AccountFeed.History -> "FEhistory"
            AccountFeed.WatchLater -> "VLWL"
            AccountFeed.Liked -> "VLLL"
            AccountFeed.Playlists -> "FEplaylist_aggregation"
        }
        if (feed == AccountFeed.Playlists && (continuation as? AccountCursor)?.browseId == "FElibrary")
            return page("FElibrary", continuation)
        if (feed == AccountFeed.Home || feed == AccountFeed.History) return withoutShorts(id, continuation)
        val result = page(id, continuation)
        return if (feed == AccountFeed.Playlists && continuation == null && result is Outcome.Failure && result.error == VideoError.ContentUnavailable)
            page("FElibrary", null) else result
    }
    /** Home and History hide Shorts; a page of only Shorts moves on so paging doesn't stall. */
    private suspend fun withoutShorts(id: String, continuation: Continuation?): Outcome<VideoPage> {
        var result = page(id, continuation)
        for (attempt in 1..5) {
            val value = (result as? Outcome.Success)?.value ?: return result
            val kept = value.copy(videos = value.videos.filterNot(Video::isShort))
            if (kept.videos.isNotEmpty() || kept.playlists.isNotEmpty() || kept.continuation == null || attempt == 5) return Outcome.Success(kept)
            result = page(id, kept.continuation)
        }
        return result
    }
    override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = page("VL$id", continuation)
    override fun resumePosition(url: String): Long? = progress[url]
    private suspend fun page(id: String, continuation: Continuation?): Outcome<VideoPage> = withContext(Dispatchers.IO) {
        try {
            sessions.initialize()
            val current = sessions.status.value
            if (!current.signedIn) return@withContext Outcome.Failure(VideoError.AuthExpired)
            if (revision != current.revision) { clear(); revision = current.revision }
            val cursor = continuation as? AccountCursor
            if (continuation != null && (cursor == null || cursor.browseId != id || cursor.revision != current.revision))
                return@withContext Outcome.Failure(VideoError.AuthExpired)
            cache[id]?.takeIf { continuation == null && System.currentTimeMillis() - it.first < 30_000 }
                ?.let { return@withContext Outcome.Success(it.second) }
            val json = client.browse(if (cursor == null) id else null, cursor?.token)
            val alerts = json.arr("alerts")?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.obj("alertRenderer") }
            if (alerts?.any { it.str("type") == "ERROR" } == true) return@withContext Outcome.Failure(VideoError.ContentUnavailable)
            val parsed = AccountPageParser.parse(json)
            if (sessions.status.value.revision != current.revision) return@withContext Outcome.Failure(VideoError.AuthExpired)
            if (parsed.skipped && parsed.videos.isEmpty() && parsed.playlists.isEmpty())
                return@withContext Outcome.Failure(VideoError.ExtractionBroken)
            parsed.videos.forEach { video -> video.resumePositionMs?.let { progress[video.url] = it } }
            val result = VideoPage(parsed.videos, parsed.token?.let { AccountCursor(id,it,current.revision) },
                skippedItems = parsed.skipped, playlists = parsed.playlists)
            if (continuation == null) cache[id] = System.currentTimeMillis() to result
            Outcome.Success(result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(InnerTubeClient.errorFor(e)) }
    }
    fun clear() { progress.clear(); cache.clear() }
    override fun invalidateFeeds() = cache.clear()
}
