package dev.quietube.cast

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietube.account.actions.ActionParser
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CastState(val open: Boolean = false, val scanning: Boolean = false, val tvs: List<Tv> = emptyList(),
    val sending: Tv? = null, val message: String? = null, val playingOn: Tv? = null, val direct: Boolean = false)

@HiltViewModel
class CastViewModel @Inject constructor(private val caster: TvCaster) : ViewModel() {
    private val mutable = MutableStateFlow(CastState())
    val state = mutable.asStateFlow()
    fun consumeMessage(message: String) { mutable.update { if (it.message == message) it.copy(message = null) else it } }
    private var job: Job? = null
    private class DirectSend(val tv: Tv, val url: String)
    private var sendingDirect: DirectSend? = null

    /** Stops the scan or send in progress. A direct send may already be streaming; nothing else would end it. */
    private fun cancelJob() {
        job?.cancel()
        sendingDirect?.let(::abandon)
    }
    private fun abandon(send: DirectSend) {
        sendingDirect = null
        if (caster.abandonDirect(send.tv, send.url)) stopFollowing()
    }
    private fun stopFollowing() {
        seeking?.cancel(); following?.cancel(); clock = null
        mutable.update { it.copy(playingOn = null, direct = false) }
    }

    fun open() { mutable.update { it.copy(open = true, message = null) }; scan() }
    fun dismiss() { cancelJob(); mutable.update { it.copy(open = false, scanning = false, sending = null) } }
    fun denied() = mutable.update { it.copy(open = true, scanning = false, message = "Allow access to devices on your network to find TVs.") }

    fun scan() {
        cancelJob()
        mutable.update { it.copy(scanning = true, sending = null, message = null) }
        job = viewModelScope.launch {
            val tvs = try { caster.discover() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            mutable.update { it.copy(scanning = false, tvs = tvs,
                message = if (tvs.isEmpty()) "No TVs found. Turn the TV on and make sure it is on the same Wi-Fi." else null) }
        }
    }

    /**
     * [direct] streams from this phone to the TV's media player (no ads); otherwise the TV's YouTube app plays it.
     * [started] runs once the TV accepted the video, e.g. to pause playback on the phone.
     */
    fun cast(tv: Tv, url: String, positionMs: Long, direct: Boolean, started: () -> Unit) {
        cancelJob()
        mutable.update { it.copy(sending = tv, message = null) }
        val send = DirectSend(tv, url).takeIf { direct }
        sendingDirect = send
        job = viewModelScope.launch {
            try {
                if (direct) caster.playDirect(tv, url, positionMs)
                else caster.play(tv, ActionParser.videoId(url), positionMs / 1000)
                sendingDirect = null
                started()
                mutable.update { it.copy(open = false, sending = null, playingOn = tv, direct = direct) }
                if (direct) follow(positionMs)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                send?.let(::abandon)
                mutable.update { it.copy(sending = null, message = "Couldn't start the video on ${tv.name}. Check that the TV is on and try again.") }
            }
        }
    }

    private var seeking: Job? = null
    private var following: Job? = null
    private class TvClock(val positionMs: Long, val atMs: Long, val playing: Boolean)
    @Volatile private var clock: TvClock? = null
    @Volatile private var holdUntilMs = 0L

    /** The TV's position (ms) during a direct stream, advanced between reports; null otherwise. */
    fun tvPositionMs(): Long? = clock?.let { if (it.playing) it.positionMs + (SystemClock.elapsedRealtime() - it.atMs) else it.positionMs }

    /** Polls the TV so the phone's controls show where it is. */
    private fun follow(startMs: Long) {
        following?.cancel()
        clock = TvClock(startMs, SystemClock.elapsedRealtime(), true)
        holdUntilMs = SystemClock.elapsedRealtime() + 4_000 // the TV has just jumped to the start position
        following = viewModelScope.launch {
            while (true) {
                delay(1_000)
                // Right after a seek the TV still reports the old position.
                if (SystemClock.elapsedRealtime() < holdUntilMs) continue
                val status = try { caster.directStatus() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                status?.let { clock = TvClock(it.positionMs, SystemClock.elapsedRealtime(), it.playing) }
            }
        }
    }

    /** A seek on the phone's player while the TV plays; brief debounce so scrubbing sends one seek. */
    fun seek(positionMs: Long) {
        if (mutable.value.playingOn == null) return
        clock?.let { clock = TvClock(positionMs, SystemClock.elapsedRealtime(), it.playing) }
        holdUntilMs = SystemClock.elapsedRealtime() + 4_000
        seeking?.cancel()
        seeking = viewModelScope.launch {
            delay(300)
            try { caster.seek(positionMs) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "The TV didn't accept the new position.") } }
        }
    }

    /** The phone took over again (another video or local playback resumed); a direct stream ends with it. */
    fun clearPlaying() {
        val current = mutable.value
        if (current.direct) caster.stopDirect(current.playingOn)
        stopFollowing()
    }
}
