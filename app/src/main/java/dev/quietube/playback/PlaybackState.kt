package dev.quietube.playback

import dev.quietube.data.Chapter
import dev.quietube.data.SegmentCategory
import dev.quietube.data.SkipSegment
import dev.quietube.data.Storyboard
import dev.quietube.data.Video
import dev.quietube.data.VideoError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val video: Video? = null,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val qualities: List<Int> = emptyList(),
    val manualQuality: Int? = null,
    val selectedHeight: Int = 0,
    val actualHeight: Int = 0,
    val related: List<Video> = emptyList(),
    val error: VideoError? = null,
    val description: String? = null,
    val syncMessage: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val storyboard: Storyboard? = null,
    /** SponsorBlock segments to mark; those in [autoSkip] are skipped, the rest offer a manual Skip. */
    val segments: List<SkipSegment> = emptyList(),
    val autoSkip: Set<SegmentCategory> = emptySet(),
    /** The latest automatic skip, for the "Skipped · Undo" notice. */
    val skipped: SkipNotice? = null,
    /** Autoplay's next video, playing at [UpNext.atMs] (elapsed realtime) unless cancelled. */
    val upNext: UpNext? = null,
)
data class UpNext(val url: String, val title: String?, val atMs: Long)
/** [atMs] is elapsed realtime, so a notice from while the app was in the background isn't shown late. */
data class SkipNotice(val serial: Int, val segment: SkipSegment, val atMs: Long)
@Singleton
class PlaybackStateStore @Inject constructor() {
    internal val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
}
