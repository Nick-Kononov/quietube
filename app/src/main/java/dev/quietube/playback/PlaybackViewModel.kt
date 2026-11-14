@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.data.Video
import dev.quietube.links.linkStartMs
import dev.quietube.links.parseYouTubeLink
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PlaybackViewModel @Inject constructor(@ApplicationContext context: Context, private val store: PlaybackStateStore) : ViewModel() {
    val state = store.state
    private val mutableController = MutableStateFlow<MediaController?>(null)
    val controller = mutableController.asStateFlow()
    private val mutableConnectionError = MutableStateFlow(false)
    val connectionError = mutableConnectionError.asStateFlow()
    private val connected = CompletableDeferred<MediaController>()
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    init {
        future.addListener({
            try {
                val controller = future.get()
                mutableController.value = controller
                connected.complete(controller)
            } catch (e: Exception) {
                mutableConnectionError.value = true
                connected.completeExceptionally(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    /** The rest of the playlist the next [play] comes from, for autoplay; consumed by that call. */
    private var pendingQueue: List<Video>? = null
    fun queueNext(videos: List<Video>) { pendingQueue = videos }
    fun play(url: String) { command(PlaybackService.PLAY_VIDEO, Bundle().apply {
        pendingQueue?.let { queue ->
            putStringArrayList("queue", ArrayList(queue.map { it.url }))
            putStringArrayList("queue_titles", ArrayList(queue.map { it.title }))
        }
        pendingQueue = null
        val canonical = parseYouTubeLink(url)
        putString("url", canonical?.substringBefore("&t=") ?: url)
        canonical?.let { linkStartMs(it) }?.let { putLong("start_ms", it) }
    }) }
    fun consumeMessage(message: String) { store.mutable.update { if (it.syncMessage == message) it.copy(syncMessage = null) else it } }
    fun quality(height: Int?) { command(PlaybackService.SET_QUALITY, Bundle().apply { putInt("height", height ?: 0) }) }
    private fun command(action: String, args: Bundle) {
        viewModelScope.launch {
            try { connected.await().sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableConnectionError.value = true }
        }
    }
    fun close() { command(PlaybackService.CLOSE, Bundle.EMPTY) }
    fun playNext() { command(PlaybackService.PLAY_NEXT, Bundle.EMPTY) }
    fun cancelNext() { command(PlaybackService.CANCEL_NEXT, Bundle.EMPTY) }
    fun undoSkip(serial: Int) { command(PlaybackService.UNDO_SKIP, Bundle().apply { putInt("serial", serial) }) }
    fun toggle() { mutableController.value?.let { if (it.playWhenReady) it.pause() else it.play() } }
    override fun onCleared() {
        MediaController.releaseFuture(future)
        connected.cancel()
        super.onCleared()
    }
}
