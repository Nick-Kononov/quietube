@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.quietube.MainActivity
import dev.quietube.account.EncryptedSessionStore
import dev.quietube.data.*
import dev.quietube.links.videoId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var sessions: EncryptedSessionStore
    @Inject lateinit var repository: VideoRepository
    @Inject lateinit var store: PlaybackStateStore
    @Inject lateinit var positions: ResumeDao
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var history: HistoryReporter
    @Inject lateinit var network: NetworkStatus
    @Inject lateinit var sponsorBlock: SponsorBlock
    @Inject lateinit var thirdParty: ThirdPartyPreferences
    @Inject lateinit var calm: CalmPreferences
    @Inject lateinit var continueShortcut: ContinueShortcut
    private lateinit var player: ExoPlayer
    private lateinit var sources: DefaultMediaSourceFactory
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolution: Job? = null
    private var ticker: Job? = null
    private var details: PlaybackDetails? = null
    private var quality: Int? = null
    private var retryCount = 0
    private var services = ThirdPartySettings()
    private var segments = SegmentsFor("", emptyList())
    private var segmentsJob: Job? = null
    private var skipper: Job? = null
    /** Segments the viewer undid a skip for; they play through for the rest of this video. */
    private val unskipped = mutableSetOf<SkipSegment>()
    private var previousMs = 0L
    private var skipSerial = 0
    private var autoplay = false
    /** The rest of the playlist this video was opened from, with titles; null when not playing from a playlist. */
    private var queue: List<Pair<String, String?>>? = null
    private var nextJob: Job? = null
    /** One writer keeps resume saves in order; parallel IO could commit an older position last. */
    private val positionWriter = Dispatchers.IO.limitedParallelism(1)
    private val metrics: PlaybackMetrics? by lazy {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) PlaybackMetrics() else null
    }

    override fun onCreate() {
        super.onCreate()
        // Chunks are megabytes long: keep the connect/read timeouts but drop the whole-call limit, which would cut slow links mid-chunk.
        val streaming = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
        sources = DefaultMediaSourceFactory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(streaming).setUserAgent(ExtractorDownloader.USER_AGENT).setTransferListener(metrics)))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            metrics?.let(::addAnalyticsListener)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    // Re-arm the one-time re-extraction once fresh URLs play, so later expiry can recover too.
                    if (player.playbackState == Player.STATE_READY) retryCount = 0
                    store.mutable.update { it.copy(isPlaying = player.isPlaying, playWhenReady = player.playWhenReady,
                        buffering = player.playbackState == Player.STATE_BUFFERING,
                        actualHeight = player.videoSize.height) }
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) watchSkips()
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                        || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        savePosition()
                        history.sample(player.currentMediaItem?.mediaId, player.currentPosition, player.isPlaying,
                            events.contains(Player.EVENT_POSITION_DISCONTINUITY), player.playbackParameters.speed)
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) scheduleNext() else if (state != Player.STATE_IDLE) cancelNext()
                }
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    // A seek starts afresh: seeking into a segment plays it, only running into one skips it.
                    previousMs = new.positionMs
                }
                override fun onPlayerError(error: PlaybackException) {
                    val blocked = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().any { it.responseCode == 403 || it.responseCode == 410 }
                    // Re-extract once for expired URLs; never retry indefinitely or send account cookies.
                    val url = details?.video?.url
                    if (blocked && retryCount == 0 && url != null) {
                        retryCount++
                        repository.invalidatePlayback(url)
                        resolve(url, player.currentPosition, player.playWhenReady, resetRetries = false)
                    } else store.mutable.update { it.copy(resolving = false, buffering = false,
                        error = if (blocked) VideoError.StreamRejected else if (error.errorCode in 4000..4999) VideoError.UnsupportedPlayback else VideoError.Network) }
                }
            })
        }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("open_player", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                if (controller.uid == Process.myUid()) builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(PLAY_VIDEO, Bundle.EMPTY)).add(SessionCommand(SET_QUALITY, Bundle.EMPTY))
                        .add(SessionCommand(CLOSE, Bundle.EMPTY)).add(SessionCommand(UNDO_SKIP, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_NEXT, Bundle.EMPTY)).add(SessionCommand(CANCEL_NEXT, Bundle.EMPTY)).build())
                return Futures.immediateFuture(builder.build())
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                when (customCommand.customAction) {
                    PLAY_VIDEO -> {
                        val url = args.getString("url") ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        quality = null
                        val urls = args.getStringArrayList("queue")
                        val titles = args.getStringArrayList("queue_titles")
                        queue = urls?.take(MAX_QUEUE)?.mapIndexed { i, next -> next to titles?.getOrNull(i) }
                        resolve(url, args.takeIf { it.containsKey("start_ms") }?.getLong("start_ms")?.coerceAtLeast(0))
                    }
                    SET_QUALITY -> {
                        val selected = args.getInt("height", 0).takeIf { it > 0 }
                        val current = details
                        if (current != null && (selected == null || selected in StreamSelector.qualities(current))) {
                            quality = selected
                            savePosition()
                            prepare(current, player.currentPosition, player.playWhenReady)
                        }
                    }
                    CLOSE -> close()
                    UNDO_SKIP -> undoSkip(args.getInt("serial"))
                    PLAY_NEXT -> playNext()
                    CANCEL_NEXT -> cancelNext()
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        scope.launch {
            var wasSignedIn = false
            sessions.status.collect { status ->
                if (status.ready && wasSignedIn && !status.signedIn) {
                    resolution?.cancel(); details = null; player.stop(); player.clearMediaItems()
                    store.mutable.value = PlaybackState()
                }
                if (status.ready) wasSignedIn = status.signedIn
            }
        }
        scope.launch { calm.settings.collect { autoplay = it.autoplay; if (!autoplay) cancelNext() } }
        scope.launch {
            thirdParty.settings.collect { settings ->
                val wasOn = services.sponsorBlock
                services = settings
                if (settings.sponsorBlock && !wasOn) loadSegments() else publishSegments()
            }
        }
        history.start(scope)
        // A save adds at most one row per video, so trimming old rows once per service start keeps the table bounded.
        scope.launch(positionWriter) { positions.prune() }
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                history.sample(player.currentMediaItem?.mediaId, player.currentPosition, player.isPlaying, speed = player.playbackParameters.speed)
                if (++tick % 5 == 0) savePosition()
                if (tick % 15 == 0 && player.isPlaying) metrics?.summary(player.videoSize.height)
            }
        }
    }

    private fun resolve(url: String, start: Long? = null, play: Boolean = true, resetRetries: Boolean = true) {
        resolution?.cancel()
        nextJob?.cancel(); nextJob = null
        savePosition()
        player.stop()
        player.clearMediaItems()
        if (resetRetries) { retryCount = 0; details = null; metrics?.requested() }
        if (segments.url != url) { segmentsJob?.cancel(); segments = SegmentsFor(url, emptyList()); unskipped.clear() }
        store.mutable.value = PlaybackState(video = details?.video ?: Video(url, "Loading video…", "", null, -1, -1, null), resolving = true)
        resolution = scope.launch {
            when (val result = repository.playback(url)) {
                is Outcome.Failure -> store.mutable.update { it.copy(resolving = false, error = result.error) }
                is Outcome.Success -> {
                    details = result.value
                    metrics?.resolved()
                    val saved = start ?: withContext(Dispatchers.IO) { positions.position(result.value.video.url) } ?: repository.resumePosition(result.value.video.url) ?: 0L
                    prepare(result.value, playbackStart(start, saved, result.value.video.durationSeconds, result.value.video.isLive), play)
                    loadSegments()
                }
            }
        }
    }

    private fun prepare(value: PlaybackDetails, start: Long, play: Boolean) {
        val height = quality ?: defaultQuality()
        val selected = StreamSelector.select(value, height)
        if (selected == null) {
            store.mutable.update { it.copy(resolving = false, error = VideoError.ContentUnavailable) }
            return
        }
        val metadata = MediaMetadata.Builder().setTitle(value.video.title).setArtist(value.video.channel)
            .setArtworkUri(value.video.thumbnailUrl?.let(Uri::parse)).build()
        fun source(variant: StreamVariant, id: String): MediaSource = sources.createMediaSource(
            MediaItem.Builder().setMediaId(id).setUri(variant.url).setMimeType(variant.mimeType)
                .setMediaMetadata(metadata).build())
        val video = source(selected.video, value.video.url)
        val combined = selected.audio?.let { MergingMediaSource(true, video, source(it, value.video.url + "#audio")) } ?: video
        val originalLanguage = StreamSelector.selectAudio(value)
            ?.takeIf { it.audioRole == AudioRole.Original }?.audioLanguage
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*listOfNotNull(originalLanguage).toTypedArray())
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .setMaxVideoSize(Int.MAX_VALUE, height).setForceHighestSupportedBitrate(true).build()
        store.mutable.value = PlaybackState(video = value.video, related = value.related, description = value.description,
            chapters = value.chapters, storyboard = value.storyboard, segments = markedSegments(), autoSkip = services.skipped, qualities = StreamSelector.qualities(value), manualQuality = quality,
            selectedHeight = selected.video.height, buffering = true, playWhenReady = play)
        previousMs = start.coerceAtLeast(0)
        player.setMediaSource(combined, start.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = play
    }

    /** Mini-player close: keep resume progress and send final watch time, then drop the video entirely. */
    private fun close() {
        resolution?.cancel()
        savePosition()
        history.sample(player.currentMediaItem?.mediaId, player.currentPosition, false, speed = player.playbackParameters.speed)
        details = null; quality = null; queue = null
        nextJob?.cancel(); nextJob = null
        segmentsJob?.cancel(); segments = SegmentsFor("", emptyList()); unskipped.clear()
        player.stop(); player.clearMediaItems()
        store.mutable.value = PlaybackState()
    }
    /** Fetches SponsorBlock segments for the current video, only while SponsorBlock is on; never for live streams. */
    private fun loadSegments() {
        val video = details?.video ?: return
        val id = videoId(video.url)
        if (!services.sponsorBlock || video.isLive || id == null || (segments.url == video.url && segments.loaded)) { publishSegments(); return }
        segmentsJob?.cancel()
        segmentsJob = scope.launch {
            val found = sponsorBlock.segments(id) ?: return@launch
            if (details?.video?.url == video.url) { segments = SegmentsFor(video.url, found, loaded = true); publishSegments() }
        }
    }
    private fun markedSegments(): List<SkipSegment> =
        if (details?.video?.url == segments.url) segments.list.filter { it.category in services.marked } else emptyList()
    private fun publishSegments() {
        store.mutable.update { it.copy(segments = markedSegments(), autoSkip = services.skipped) }
        watchSkips()
    }
    /** Checks a few times a second while playing with segments to skip; fine-grained enough at 2× speed. */
    private fun watchSkips() {
        val active = player.isPlaying && markedSegments().any { it.category in services.skipped }
        if (!active) { skipper?.cancel(); skipper = null; return }
        if (skipper?.isActive == true) return
        skipper = scope.launch {
            while (isActive) {
                val position = player.currentPosition
                val list = markedSegments()
                val skip = segmentToSkip(list, services.skipped, previousMs, position, unskipped)
                previousMs = position
                if (skip != null) {
                    player.seekTo(skipEnd(skip, list, services.skipped, unskipped).coerceAtMost(player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                    store.mutable.update { it.copy(skipped = SkipNotice(++skipSerial, skip, android.os.SystemClock.elapsedRealtime())) }
                }
                delay(200)
            }
        }
    }
    private fun undoSkip(serial: Int) {
        val notice = store.mutable.value.skipped?.takeIf { it.serial == serial } ?: return
        unskipped += notice.segment
        player.seekTo(notice.segment.startMs)
        store.mutable.update { it.copy(skipped = null) }
    }
    /** Autoplay: after a short pause the next video plays, unless cancelled; a finished playlist stops there. */
    private fun scheduleNext() {
        val current = details ?: return
        if (!autoplay || current.video.isLive || nextJob?.isActive == true) return
        val url = autoplayNext(queue?.map { it.first }, current.related, current.video.url) ?: return
        val title = queue?.firstOrNull()?.second ?: current.related.firstOrNull { it.url == url }?.title
        store.mutable.update { it.copy(upNext = UpNext(url, title, android.os.SystemClock.elapsedRealtime() + AUTOPLAY_DELAY_MS)) }
        nextJob = scope.launch { delay(AUTOPLAY_DELAY_MS); playNext() }
    }
    private fun playNext() {
        val next = store.mutable.value.upNext ?: return
        queue = queue?.drop(1)
        quality = null
        resolve(next.url)
    }
    private fun cancelNext() {
        nextJob?.cancel(); nextJob = null
        if (store.mutable.value.upNext != null) store.mutable.update { it.copy(upNext = null) }
    }
    private fun defaultQuality(): Int = if (network.onUnmeteredWifi()) 1080 else 720
    private fun savePosition(): Job? {
        val current = details ?: return null
        if (current.video.isLive || player.currentMediaItem?.mediaId != current.video.url || player.playbackState == Player.STATE_IDLE) return null
        val position = resumablePosition(player.currentPosition, player.duration)
        continueShortcut.saved(current.video.url, current.video.title, position)
        return scope.launch(positionWriter) {
            positions.save(ResumePosition(current.video.url, position, System.currentTimeMillis()))
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        val finalSave = savePosition()
        history.sample(player.currentMediaItem?.mediaId, player.currentPosition, false, speed = player.playbackParameters.speed)
        val finalReport = history.finish()
        ticker?.cancel()
        resolution?.cancel()
        session?.release()
        player.release()
        store.mutable.update { it.copy(isPlaying = false, playWhenReady = false, buffering = false) }
        // Complete the final local write before disposing the service coroutine scope.
        scope.launch { finalSave?.join(); withTimeoutOrNull(25_000) { finalReport?.join() }; scope.cancel() }
        super.onDestroy()
    }
    private class SegmentsFor(val url: String, val list: List<SkipSegment>, val loaded: Boolean = false)
    companion object {
        const val PLAY_VIDEO = "dev.quietube.PLAY_VIDEO"
        const val SET_QUALITY = "dev.quietube.SET_QUALITY"
        const val CLOSE = "dev.quietube.CLOSE"
        const val UNDO_SKIP = "dev.quietube.UNDO_SKIP"
        const val PLAY_NEXT = "dev.quietube.PLAY_NEXT"
        const val CANCEL_NEXT = "dev.quietube.CANCEL_NEXT"
        private const val AUTOPLAY_DELAY_MS = 5_000L
        private const val MAX_QUEUE = 200
    }
}
