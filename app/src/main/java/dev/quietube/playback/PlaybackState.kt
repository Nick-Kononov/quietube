package dev.quietube.playback

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
    val positionMs: Long = 0,
    val syncMessage: String? = null,
)
@Singleton
class PlaybackStateStore @Inject constructor() {
    internal val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
}
