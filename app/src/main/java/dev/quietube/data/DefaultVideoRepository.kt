package dev.quietube.data

import dev.quietube.account.EncryptedSessionStore
import javax.inject.Inject
import javax.inject.Singleton

/** Signed-out home is a small, local feed built from successful anonymous searches. */
@Singleton
class DefaultVideoRepository @Inject constructor(
    private val anonymous: AnonymousSource,
    private val account: AccountSource,
    private val sessions: EncryptedSessionStore,
    private val playbackCache: PlaybackPrefetcher,
) : VideoRepository {
    override suspend fun comments(url: String,continuation: Continuation?) = anonymous.comments(url,continuation)
    override suspend fun engagement(url: String) = account.engagement(url)
    override suspend fun rate(url: String,rating: Rating,previous: Rating) = account.rate(url,rating,previous)
    override suspend fun subscribe(channelId: String,subscribe: Boolean) = account.subscribe(channelId,subscribe)
    override suspend fun startWatch(url: String,position: Double) = account.startWatch(url,position)
    override suspend fun reportWatch(ticket: WatchTicket,segments: List<Pair<Double,Double>>,position: Double,final: Boolean) = account.reportWatch(ticket,segments,position,final)
    private val local = java.util.concurrent.ConcurrentHashMap<String, Video>()
    override suspend fun playback(url: String): Outcome<PlaybackDetails> = playbackCache.get(url)
    override fun invalidatePlayback(url: String) = playbackCache.invalidate(url)
    override fun prefetch(urls: List<String>) = playbackCache.visible(urls)
    override fun invalidateFeeds() = account.invalidateFeeds()
    override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> {
        val result = anonymous.search(query.trim(), continuation)
        if (result is Outcome.Success) { result.value.videos.take(30).forEach { local[it.url] = it }; if (local.size > 100) local.clear() }
        return result
    }
    override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> {
        sessions.initialize()
        if (sessions.status.value.signedIn) return account.feed(feed, continuation)
        return if (feed == AccountFeed.Home) Outcome.Success(VideoPage(local.values.toList())) else Outcome.Failure(VideoError.AuthExpired)
    }
    override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> {
        sessions.initialize()
        return if (sessions.status.value.signedIn) account.playlist(id, continuation) else anonymous.playlist(id, continuation)
    }
    override fun resumePosition(url: String): Long? = if (sessions.status.value.signedIn) account.resumePosition(url) else null
    fun clearLocalFeed() { local.clear() }
}
