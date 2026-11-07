package dev.quietube.data

/** Signed stream URLs stay in memory; never log or persist them. */
data class StreamVariant(
    val url: String,
    val mimeType: String?,
    val height: Int = 0,
    val bitrate: Int = 0,
    val videoOnly: Boolean = false,
    val codec: String = "",
    val delivery: Delivery = Delivery.Progressive,
    val audioRole: AudioRole = AudioRole.Unknown,
    val audioLanguage: String? = null,
)
enum class AudioRole { Original, Unknown, Secondary, Dubbed, Descriptive }
enum class Delivery { Progressive, Dash, Hls }
data class PlaybackDetails(
    val video: Video,
    val videoStreams: List<StreamVariant>,
    val audioStreams: List<StreamVariant>,
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val related: List<Video> = emptyList(),
)
data class StreamSelection(val video: StreamVariant, val audio: StreamVariant? = null)

/** Prefer split adaptive tracks at the best allowed resolution, then muxed tracks. */
object StreamSelector {
    fun select(details: PlaybackDetails, maximumHeight: Int): StreamSelection? {
        if (details.video.isLive && details.hlsUrl != null)
            return StreamSelection(StreamVariant(details.hlsUrl, "application/x-mpegURL", delivery = Delivery.Hls))
        val audio = selectAudio(details)
        // A muxed track may contain a localized dub. Keep explicit original audio
        // when split video is available, even if a muxed stream has higher resolution.
        val originalSplit = audio?.audioRole == AudioRole.Original && details.videoStreams.any { it.videoOnly }
        val playable = details.videoStreams.filter { (!it.videoOnly || audio != null) && (!originalSplit || it.videoOnly) }
        val height = playable.filter { it.height in 1..maximumHeight }.maxOfOrNull { it.height }
            ?: playable.filter { it.height > 0 }.minOfOrNull { it.height }
        val video = playable.filter { it.height == height }.maxWithOrNull(
            compareBy<StreamVariant> { it.videoOnly }
                .thenBy { it.codec.startsWith("avc") || it.codec.startsWith("h264") }
                .thenBy { it.mimeType == "video/mp4" }.thenBy { it.bitrate })
        if (video != null) return StreamSelection(video, audio.takeIf { video.videoOnly })
        details.hlsUrl?.let { return StreamSelection(StreamVariant(it, "application/x-mpegURL", delivery = Delivery.Hls)) }
        details.dashUrl?.let { return StreamSelection(StreamVariant(it, "application/dash+xml", delivery = Delivery.Dash)) }
        return null
    }
    fun selectAudio(details: PlaybackDetails): StreamVariant? = details.audioStreams.maxWithOrNull(
        compareBy<StreamVariant> {
            when (it.audioRole) {
                AudioRole.Original -> 4
                AudioRole.Unknown -> 3 // Legacy streams without multilingual metadata.
                AudioRole.Secondary -> 2
                AudioRole.Dubbed -> 1
                AudioRole.Descriptive -> 0
            }
        }.thenBy { it.mimeType == "audio/mp4" }.thenBy { it.bitrate })

    fun qualities(details: PlaybackDetails): List<Int> {
        val originalSplit = selectAudio(details)?.audioRole == AudioRole.Original && details.videoStreams.any { it.videoOnly }
        return details.videoStreams
        .filter { (!it.videoOnly || details.audioStreams.isNotEmpty()) && (!originalSplit || it.videoOnly) }.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
    }
}
