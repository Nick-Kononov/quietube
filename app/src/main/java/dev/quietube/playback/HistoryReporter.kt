package dev.quietube.playback

import dev.quietube.account.EncryptedSessionStore
import dev.quietube.account.actions.SyncPreferences
import dev.quietube.data.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update

/** Uses media progress and monotonic time; seeks, pauses and buffering add no watched interval. */
data class WatchSample(val url: String?,val position: Double,val playing: Boolean,val elapsed: Long,val revision: Long,
    val enabled: Boolean,val discontinuity: Boolean = false,val speed: Float = 1f)
class WatchedIntervals {
    private var previous: WatchSample? = null
    private val intervals = mutableListOf<Pair<Double,Double>>()
    fun sample(value: WatchSample) {
        previous?.let { old ->
            val delta = value.position-old.position
            val wall = (value.elapsed-old.elapsed).coerceAtLeast(0)/1000.0
            if (old.url == value.url && old.revision == value.revision && old.playing && old.enabled && value.enabled && !value.discontinuity &&
                delta > 0 && delta <= wall*old.speed+2.0 && value.position.isFinite()) {
                val last = intervals.lastOrNull()
                if (last != null && kotlin.math.abs(last.second-old.position) < 0.05) intervals[intervals.lastIndex] = last.first to value.position
                else intervals.add(old.position to value.position)
            }
        }
        previous = value
    }
    fun drain(): List<Pair<Double,Double>> = intervals.toList().also { intervals.clear() }
    fun clear() { previous = null; intervals.clear() }
}
class HistoryReporter @Inject constructor(private val repository: VideoRepository,private val sessions: EncryptedSessionStore,
    private val preferences: SyncPreferences,private val store: PlaybackStateStore) {
    private val samples = Channel<WatchSample>(128)
    @Volatile private var enabled = true
    private var job: Job? = null
    fun start(scope: CoroutineScope) {
        scope.launch { preferences.settings.collect { enabled = it.history } }
        job = scope.launch {
            var ticket: WatchTicket? = null
            var current: WatchSample? = null
            var sentAt = 0L
            var retryAt = 0L
            val watched = WatchedIntervals()
            suspend fun flush(sample: WatchSample) {
                val segments = watched.drain()
                if (ticket == null || segments.isEmpty()) return
                when (val result = repository.reportWatch(ticket!!,segments,sample.position,!sample.playing)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> message(sample,"Watch sync not confirmed (${result.error}); playback continues.")
                }
                sentAt = sample.elapsed
            }
            for (sample in samples) {
                if (!enabled || !sample.enabled || sample.revision != sessions.status.value.revision || !sessions.status.value.signedIn) {
                    ticket = null; current = null; watched.clear(); continue
                }
                val old = current
                if (old == null || old.url != sample.url || old.revision != sample.revision) {
                    if (old != null) flush(old.copy(playing = false))
                    ticket = null; watched.clear(); retryAt = 0; sentAt = sample.elapsed
                }
                current = sample
                if (sample.url == null) continue
                watched.sample(sample)
                if (ticket == null && sample.playing && sample.elapsed >= retryAt) {
                    when (val result = repository.startWatch(sample.url,sample.position)) {
                        is Outcome.Success -> { ticket = result.value }
                        is Outcome.Failure -> { retryAt = sample.elapsed+60_000; message(sample,"Watch sync unavailable (${result.error}); playback continues.") }
                    }
                }
                if (ticket != null && (sample.elapsed-sentAt >= 30_000 || !sample.playing || sample.discontinuity)) flush(sample)
            }
            current?.let { if (enabled && it.revision == sessions.status.value.revision) flush(it.copy(playing = false)) }
        }
    }
    private fun message(sample: WatchSample,text: String) {
        store.mutable.update { if (it.video?.url == sample.url) it.copy(syncMessage = text) else it }
    }
    fun sample(url: String?,position: Long,playing: Boolean,discontinuity: Boolean = false,speed: Float = 1f) {
        val session = sessions.status.value
        samples.trySend(WatchSample(url,(position.coerceAtLeast(0)/1000.0),playing,android.os.SystemClock.elapsedRealtime(),session.revision,enabled && session.signedIn,discontinuity,speed))
    }
    fun finish(): Job? { samples.close(); return job }
}
