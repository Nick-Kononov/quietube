@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
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
import dev.quietube.data.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var sessions: dev.quietube.account.EncryptedSessionStore
    @Inject lateinit var repository: VideoRepository
    @Inject lateinit var store: PlaybackStateStore
    @Inject lateinit var positions: ResumeDao
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var history: HistoryReporter
    private lateinit var player: ExoPlayer
    private lateinit var sources: DefaultMediaSourceFactory
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolution: Job? = null
    private var ticker: Job? = null
    private var details: PlaybackDetails? = null
    private var quality: Int? = null
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()
        sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(http).setUserAgent(ExtractorDownloader.USER_AGENT))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    store.mutable.update { it.copy(isPlaying = player.isPlaying, playWhenReady = player.playWhenReady,
                        buffering = player.playbackState == Player.STATE_BUFFERING,
                        actualHeight = player.videoSize.height, positionMs = player.currentPosition.coerceAtLeast(0)) }
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                        || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        savePosition()
                        history.sample(player.currentMediaItem?.mediaId,player.currentPosition,player.isPlaying,
                            events.contains(Player.EVENT_POSITION_DISCONTINUITY),player.playbackParameters.speed)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val blocked = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().any { it.responseCode == 403 || it.responseCode == 410 }
                    // Re-extract once for expired URLs; never retry indefinitely or send account cookies.
                    val url = details?.video?.url
                    if (blocked && retryCount == 0 && url != null) {
                        retryCount++
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
                if (controller.packageName == packageName) builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(PLAY_VIDEO, Bundle.EMPTY)).add(SessionCommand(SET_QUALITY, Bundle.EMPTY)).build())
                return Futures.immediateFuture(builder.build())
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                when (customCommand.customAction) {
                    PLAY_VIDEO -> {
                        val url = args.getString("url") ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        quality = null
                        resolve(url)
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
        history.start(scope)
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                history.sample(player.currentMediaItem?.mediaId,player.currentPosition,player.isPlaying,speed = player.playbackParameters.speed)
                if (++tick % 5 == 0) savePosition()
            }
        }
    }

    private fun resolve(url: String, start: Long? = null, play: Boolean = true, resetRetries: Boolean = true) {
        resolution?.cancel()
        savePosition()
        player.stop()
        player.clearMediaItems()
        if (resetRetries) { retryCount = 0; details = null }
        store.mutable.value = PlaybackState(video = details?.video ?: Video(url, "Loading video…", "", null, -1, -1, null), resolving = true)
        resolution = scope.launch {
            when (val result = repository.playback(url)) {
                is Outcome.Failure -> store.mutable.update { it.copy(resolving = false, error = result.error) }
                is Outcome.Success -> {
                    details = result.value
                    val saved = start ?: withContext(Dispatchers.IO) { positions.position(result.value.video.url) } ?: repository.resumePosition(result.value.video.url) ?: 0L
                    prepare(result.value, if (result.value.video.isLive) 0 else resumablePosition(saved, result.value.video.durationSeconds * 1000), play)
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
        store.mutable.value = PlaybackState(video = value.video, related = value.related,
            qualities = StreamSelector.qualities(value), manualQuality = quality,
            selectedHeight = selected.video.height, buffering = true, playWhenReady = play)
        player.setMediaSource(combined, start.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = play
    }

    private fun defaultQuality(): Int {
        val manager = getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1080 else 720
    }
    private fun savePosition(): Job? {
        val current = details ?: return null
        if (current.video.isLive || player.currentMediaItem?.mediaId != current.video.url || player.playbackState == Player.STATE_IDLE) return null
        val position = resumablePosition(player.currentPosition, player.duration)
        return scope.launch(Dispatchers.IO) {
            positions.save(ResumePosition(current.video.url, position, System.currentTimeMillis()))
            positions.prune()
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        val finalSave = savePosition()
        history.sample(player.currentMediaItem?.mediaId,player.currentPosition,false,speed = player.playbackParameters.speed)
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
    companion object {
        const val PLAY_VIDEO = "dev.quietube.PLAY_VIDEO"
        const val SET_QUALITY = "dev.quietube.SET_QUALITY"
    }
}
