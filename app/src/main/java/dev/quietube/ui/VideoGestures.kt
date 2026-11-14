package dev.quietube.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What touches on the video ask for; the player layer decides what each means in its current state. */
internal interface VideoGestureListener {
    fun tap()
    /** A double tap, or a further tap while seeking; [side] is -1 for the left third and 1 for the right. */
    fun seek(side: Int, continuing: Boolean)
    /** Returns false when holding can't speed playback up (live, casting). */
    fun holdStart(): Boolean
    fun holdEnd()
    fun drag(dy: Float)
    fun dragEnd(velocityY: Float)
    fun pinch(zoomIn: Boolean)
}

/**
 * Touch handling for the video. It sits in PlayerView's overlay frame, below Media3's controller, so the
 * controller's buttons and time bar keep their own touches and everything else lands here.
 */
@SuppressLint("ViewConstructor")
internal class VideoGestureView(context: Context, private val listener: VideoGestureListener) : View(context) {
    private enum class Mode { None, Drag, Hold, SeekTap, Ignore }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = Mode.None
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var seekSide = 0
    private var seekUntil = 0L
    private var velocity: VelocityTracker? = null
    private var scaleTotal = 1f

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val side = videoThird(e.x, width.toFloat())
            if (side != 0) { seekSide = side; seekUntil = e.eventTime + SEEK_WINDOW_MS; listener.seek(side, false) }
            return true
        }
        override fun onLongPress(e: MotionEvent) { if (mode == Mode.None && listener.holdStart()) mode = Mode.Hold }
    })
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaleTotal = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { scaleTotal *= detector.scaleFactor; return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { if (scaleTotal > 1.08f) listener.pinch(true) else if (scaleTotal < .92f) listener.pinch(false) }
    })

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun performClick(): Boolean { super.performClick(); listener.tap(); return true }

    @SuppressLint("ClickableViewAccessibility") // Taps go through performClick; the player exposes every gesture as an accessibility action.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
        // The video moves with the sheet while dragging, so track the finger in screen coordinates.
        val screen = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        velocity?.addMovement(screen)
        screen.recycle()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; lastY = event.rawY
                mode = if (event.eventTime < seekUntil && videoThird(event.x, width.toFloat()) != 0) Mode.SeekTap else Mode.None
                if (mode == Mode.None) taps.onTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.None) cancelTaps(event)
                finish(cancelled = true)
                mode = Mode.Ignore
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.None -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (event.pointerCount == 1 && abs(dy) > slop && abs(dy) > abs(dx)) {
                        cancelTaps(event)
                        mode = Mode.Drag
                        lastY = event.rawY
                    } else taps.onTouchEvent(event)
                }
                Mode.Drag -> { listener.drag(event.rawY - lastY); lastY = event.rawY }
                Mode.SeekTap -> if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) mode = Mode.Ignore
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.SeekTap) {
                    val side = videoThird(event.x, width.toFloat())
                    if (side != 0) { listener.seek(side, side == seekSide); seekSide = side; seekUntil = event.eventTime + SEEK_WINDOW_MS }
                } else if (mode == Mode.None || mode == Mode.Hold) taps.onTouchEvent(event)
                finish(cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> { cancelTaps(event); finish(cancelled = true) }
        }
        return true
    }

    private fun cancelTaps(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        taps.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun finish(cancelled: Boolean) {
        when (mode) {
            Mode.Drag -> listener.dragEnd(if (cancelled) 0f else velocity?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f)
            Mode.Hold -> listener.holdEnd()
            else -> Unit
        }
        mode = Mode.None
        velocity?.recycle(); velocity = null
    }

    override fun onDetachedFromWindow() { if (mode == Mode.Hold) listener.holdEnd(); mode = Mode.None; super.onDetachedFromWindow() }

    companion object { const val SEEK_WINDOW_MS = 700L }
}

/** -1 for the left third of the video, 1 for the right third, 0 for the middle. */
internal fun videoThird(x: Float, width: Float): Int = when {
    width <= 0f -> 0
    x < width / 3f -> -1
    x > width * 2f / 3f -> 1
    else -> 0
}

/** The running total shown while double-tap seeking: more taps on the same side add up; the other side starts again. */
internal data class SeekFeedback(val side: Int, val seconds: Int, val serial: Int)
internal fun SeekFeedback?.next(side: Int, continuing: Boolean): SeekFeedback =
    if (this != null && continuing && this.side == side) copy(seconds = seconds + SEEK_STEP_S, serial = serial + 1)
    else SeekFeedback(side, SEEK_STEP_S, (this?.serial ?: 0) + 1)
internal fun seekTarget(position: Long, duration: Long, delta: Long): Long =
    (position + delta).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
internal const val SEEK_STEP_S = 10

@Composable
internal fun SeekBubble(feedback: SeekFeedback?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val third = maxWidth / 3
        listOf(-1, 1).forEach { side ->
            AnimatedVisibility(feedback?.side == side, Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd),
                enter = fadeIn(), exit = fadeOut()) {
                val shape = if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                Column(Modifier.width(third).fillMaxHeight().background(Color.White.copy(alpha = .16f), shape),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, null, tint = OverlayWhite)
                    Text("${if (side < 0) "−" else "+"}${feedback?.takeIf { it.side == side }?.seconds ?: SEEK_STEP_S}s",
                        color = OverlayWhite, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A short label over the video, such as "2×" while holding or the zoom mode after a pinch. */
@Composable
internal fun VideoPill(text: String, modifier: Modifier = Modifier) {
    Text(text, color = OverlayWhite, style = MaterialTheme.typography.labelLarge,
        modifier = modifier.clearAndSetSemantics {}.background(Color.Black.copy(alpha = .6f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
}
