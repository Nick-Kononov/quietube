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
    private val positions: dev.quietube.playback.ResumeDao,
    private val playbackCache: PlaybackPrefetcher,
) : VideoRepository {
    override suspend fun comments(url: String,continuation: Continuation?) = anonymous.comments(url,continuation)
    override suspend fun engagement(url: String) = account.engagement(url)
    override suspend fun rate(url: String,rating: Rating,previous: Rating) = account.rate(url,rating,previous)
    override suspend fun subscribe(channelId: String,subscribe: Boolean) = account.subscribe(channelId,subscribe)
    override suspend fun startWatch(url: String,position: Double) = account.startWatch(url,position)
    override suspend fun reportWatch(ticket: WatchTicket,segments: List<Pair<Double,Double>>,position: Double,final: Boolean) = account.reportWatch(ticket,segments,position,final)
    /** Most recent search results first; the oldest fall out past 100 instead of the feed emptying. */
    private val local = object : LinkedHashMap<String, Video>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Video>) = size > 100
    }
    override suspend fun playback(url: String): Outcome<PlaybackDetails> = when (val result = playbackCache.get(url)) {
        is Outcome.Success -> Outcome.Success(result.value.copy(related = withProgress(result.value.related)))
        is Outcome.Failure -> result
    }
    private suspend fun withProgress(videos: List<Video>): List<Video> {
        val saved = videos.map { it.url }.chunked(500).flatMap { positions.positions(it) }.associate { it.videoUrl to it.positionMs }
        return videos.map { withWatchProgress(it, saved[it.url]) }
    }
    private suspend fun withProgress(result: Outcome<VideoPage>): Outcome<VideoPage> = when (result) {
        is Outcome.Success -> Outcome.Success(result.value.copy(videos = withProgress(result.value.videos)))
        is Outcome.Failure -> result
    }
    override fun invalidatePlayback(url: String) = playbackCache.invalidate(url)
    override fun prefetch(urls: List<String>) = playbackCache.visible(urls)
    override fun invalidateFeeds() = account.invalidateFeeds()
    override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> {
        val result = anonymous.search(query.trim(), continuation)
        if (result is Outcome.Success) synchronized(local) {
            result.value.videos.filterNot(Video::isShort).take(30).forEach { local.remove(it.url); local[it.url] = it }
        }
        return withProgress(result)
    }
    override suspend fun feed(feed: AccountFeed, continuation: Continuation?): Outcome<VideoPage> {
        sessions.initialize()
        if (sessions.status.value.signedIn) return withProgress(account.feed(feed, continuation))
        return if (feed == AccountFeed.Home) withProgress(Outcome.Success(VideoPage(synchronized(local) { local.values.reversed() }))) else Outcome.Failure(VideoError.AuthExpired)
    }
    override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> {
        sessions.initialize()
        return withProgress(if (sessions.status.value.signedIn) account.playlist(id, continuation) else anonymous.playlist(id, continuation))
    }
    override fun resumePosition(url: String): Long? = if (sessions.status.value.signedIn) account.resumePosition(url) else null
    fun clearLocalFeed() = synchronized(local) { local.clear() }
}

internal fun withWatchProgress(video: Video, localPosition: Long?): Video =
    if (localPosition == null) video else video.copy(resumePositionMs = localPosition)
