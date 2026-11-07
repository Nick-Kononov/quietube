package dev.quietube.data

/** Source-neutral models: UI code must not depend on extractor or InnerTube types. */
data class Video(
    val url: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    val published: String?,
    val isLive: Boolean = false,
    val resumePositionMs: Long? = null,
    val likeCount: Long? = null,
)

interface Continuation

data class VideoPage(
    val videos: List<Video>,
    val continuation: Continuation? = null,
    val suggestion: String? = null,
    val skippedItems: Boolean = false,
    val playlists: List<PlaylistSummary> = emptyList(),
)

sealed interface VideoError {
    data object ContentUnavailable : VideoError
    data object AgeRestricted : VideoError
    data object AuthExpired : VideoError
    data object ExtractionBroken : VideoError
    data object Network : VideoError
    data object StreamRejected : VideoError
    data object UnsupportedPlayback : VideoError
    data object RateLimited : VideoError
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: VideoError) : Outcome<Nothing>
}

data class PlaylistSummary(val id: String, val title: String, val thumbnailUrl: String?, val countText: String?)

interface VideoRepository : AccountActions {
    suspend fun comments(url: String,continuation: Continuation? = null): Outcome<CommentPage> = Outcome.Failure(VideoError.ContentUnavailable)
    suspend fun feed(feed: AccountFeed, continuation: Continuation? = null): Outcome<VideoPage>
    suspend fun playlist(id: String, continuation: Continuation? = null): Outcome<VideoPage>
    fun resumePosition(url: String): Long? = null
    suspend fun playback(url: String): Outcome<PlaybackDetails>
    /** Forces the next playback() to re-extract, e.g. after the stream URLs were rejected. */
    fun invalidatePlayback(url: String) {}
    /** Videos currently visible in a list; may be resolved ahead of a tap. */
    fun prefetch(urls: List<String>) {}
    suspend fun search(query: String, continuation: Continuation? = null): Outcome<VideoPage>
}

interface AnonymousSource {
    suspend fun comments(url: String,continuation: Continuation? = null): Outcome<CommentPage> = Outcome.Failure(VideoError.ContentUnavailable)
    suspend fun playlist(id: String, continuation: Continuation? = null): Outcome<VideoPage>
    suspend fun playback(url: String): Outcome<PlaybackDetails>
    suspend fun search(query: String, continuation: Continuation? = null): Outcome<VideoPage>
}

/** Credentials never enter AnonymousSource. */
interface AccountSource : AccountActions {
    suspend fun playlist(id: String, continuation: Continuation? = null): Outcome<VideoPage>
    fun resumePosition(url: String): Long? = null
    suspend fun feed(feed: AccountFeed, continuation: Continuation? = null): Outcome<VideoPage>
}
enum class AccountFeed { Home, Subscriptions, History, WatchLater, Liked, Playlists }
