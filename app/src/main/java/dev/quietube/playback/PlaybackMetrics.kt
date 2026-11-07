@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.quietube.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Debug builds only: startup and stall timings under logcat tag QuietubePerf. Never logs URLs or video IDs. */
internal class PlaybackMetrics : AnalyticsListener, TransferListener {
    private class Transfer(val startedAt: Long) { var bytes = 0L }
    private val transfers = ConcurrentHashMap<DataSource, Transfer>()
    private var requestedAt = 0L
    private var firstFrame = false
    private var ready = false
    private var stalledAt = 0L
    private var stalls = 0
    private var stalledMs = 0L
    private var dropped = 0
    private var bandwidth = 0L

    fun requested() {
        requestedAt = SystemClock.elapsedRealtime()
        firstFrame = false; ready = false; stalledAt = 0; stalls = 0; stalledMs = 0; dropped = 0
    }
    fun resolved() = log("resolve_ms=${SystemClock.elapsedRealtime() - requestedAt}")
    fun summary(height: Int) = log("summary height=$height stalls=$stalls stalled_ms=$stalledMs dropped_frames=$dropped bandwidth_mbps=${bandwidth / 1_000_000}")

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (!firstFrame) { firstFrame = true; log("first_frame_ms=${SystemClock.elapsedRealtime() - requestedAt}") }
    }
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (state == Player.STATE_BUFFERING && ready && stalledAt == 0L) { stalls++; stalledAt = now }
        if (state == Player.STATE_READY) {
            if (stalledAt != 0L) { stalledMs += now - stalledAt; log("stall_ms=${now - stalledAt}"); stalledAt = 0 }
            ready = true
        }
    }
    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo, reason: Int) {
        // Seeks rebuffer by design; count only stalls during continuous playback.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) ready = false
    }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames }
    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidth = bitrateEstimate
    }
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData,
        error: IOException, wasCanceled: Boolean) {
        val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        log("load_error track=${mediaLoadData.trackType} error=${error.javaClass.simpleName} code=$code cause=${error.cause?.javaClass?.simpleName}")
    }

    // Network transfers run on loader threads; one transfer at a time per data source.
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) transfers[source] = Transfer(SystemClock.elapsedRealtime())
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) transfers[source]?.let { it.bytes += bytesTransferred }
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val transfer = transfers.remove(source) ?: return
        val ms = (SystemClock.elapsedRealtime() - transfer.startedAt).coerceAtLeast(1)
        val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "open" else dataSpec.length.toString()
        log("transfer offset=${dataSpec.position} requested=$requested bytes=${transfer.bytes} ms=$ms mbps=${transfer.bytes * 8 / ms / 1000}")
    }
    private fun log(message: String) = Log.i("QuietubePerf", message)
}
