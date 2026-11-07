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
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PlaybackViewModel @Inject constructor(@ApplicationContext context: Context, store: PlaybackStateStore) : ViewModel() {
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
    fun play(url: String) { command(PlaybackService.PLAY_VIDEO, Bundle().apply { putString("url", url) }) }
    fun quality(height: Int?) { command(PlaybackService.SET_QUALITY, Bundle().apply { putInt("height", height ?: 0) }) }
    private fun command(action: String, args: Bundle) {
        viewModelScope.launch {
            try { connected.await().sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableConnectionError.value = true }
        }
    }
    fun toggle() { mutableController.value?.let { if (it.playWhenReady) it.pause() else it.play() } }
    override fun onCleared() {
        MediaController.releaseFuture(future)
        connected.cancel()
        super.onCleared()
    }
}
