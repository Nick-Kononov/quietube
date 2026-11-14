package dev.quietube.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How far the player layer is open: 1 is the full player, 0 the mini player docked above the navigation. */
@Stable
internal class PlayerSheetState(expanded: Boolean) {
    /** Where the sheet has settled or is heading; drags and back previews change it only when released. */
    var expanded by mutableStateOf(expanded); private set
    var fraction by mutableFloatStateOf(if (expanded) 1f else 0f); private set
    /** Sideways offset of the mini player while it is swiped away. */
    var dismissOffset by mutableFloatStateOf(0f); private set
    var animate = true
    private var motion: Job? = null

    fun drag(delta: Float) { motion?.cancel(); fraction = (fraction + delta).coerceIn(0f, 1f) }
    fun preview(value: Float) { motion?.cancel(); fraction = value.coerceIn(0f, 1f) }
    /** Ends a drag; [velocity] is in fractions per second, positive while opening. */
    fun release(scope: CoroutineScope, velocity: Float) = settle(scope, sheetTarget(fraction, velocity), velocity)
    fun settle(scope: CoroutineScope, open: Boolean, velocity: Float = 0f) {
        expanded = open
        dismissOffset = 0f
        move(scope, fraction, if (open) 1f else 0f, velocity, SheetStiffness, { fraction = it })
    }
    fun show(open: Boolean) { motion?.cancel(); expanded = open; fraction = if (open) 1f else 0f; dismissOffset = 0f }
    fun dismissDrag(delta: Float) { motion?.cancel(); dismissOffset += delta }
    /** [velocity] and [width] are in pixels; a dismissed mini player slides out before [close] runs. */
    fun dismissRelease(scope: CoroutineScope, velocity: Float, width: Float, close: () -> Unit) {
        val gone = dismissed(dismissOffset, velocity, width)
        move(scope, dismissOffset, if (!gone) 0f else if (dismissOffset < 0) -width else width, velocity, Spring.StiffnessMediumLow, { dismissOffset = it }) { if (gone) close() }
    }
    private fun move(scope: CoroutineScope, from: Float, target: Float, velocity: Float, stiffness: Float, set: (Float) -> Unit, done: () -> Unit = {}) {
        motion?.cancel()
        if (!animate) { set(target); done(); return }
        motion = scope.launch {
            animate(from, target, velocity, spring(dampingRatio = 1f, stiffness = stiffness)) { value, _ -> set(value) }
            done()
        }
    }

    companion object {
        /** Spring time scales with 1/√stiffness: four times MediumLow collapses and expands twice as fast. */
        private const val SheetStiffness = Spring.StiffnessMediumLow * 4
        val Saver = Saver<PlayerSheetState, Boolean>(save = { it.expanded }, restore = { PlayerSheetState(it) })
    }
}

/** A release settles towards a fast fling's direction, otherwise towards the nearer end. */
internal fun sheetTarget(fraction: Float, velocity: Float): Boolean = when {
    velocity > 2f -> true
    velocity < -2f -> false
    else -> fraction >= .5f
}

/** The mini player leaves when dragged past 40% of its width, or flung in the direction it was dragged. */
internal fun dismissed(offset: Float, velocity: Float, width: Float): Boolean =
    abs(offset) > width * .4f || (offset != 0f && abs(velocity) > width * 1.5f && (velocity > 0) == (offset > 0))

/** The video in the mini player: 16:9 at the docked rectangle's left edge. */
internal fun miniVideoRect(dock: Rect): Rect = Rect(dock.left, dock.top, dock.left + dock.height * 16f / 9f, dock.bottom)

/** The video in the full player: the whole window in fullscreen or PiP, otherwise full width at 16:9 below [top]. */
internal fun fullVideoRect(window: Size, top: Float, videoOnly: Boolean): Rect =
    if (videoOnly) Rect(0f, 0f, window.width, window.height) else Rect(0f, top, window.width, top + window.width * 9f / 16f)

/** Linear between the docked and full rectangles; two 16:9 ends keep every step 16:9. */
internal fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    start.left + (stop.left - start.left) * fraction, start.top + (stop.top - start.top) * fraction,
    start.right + (stop.right - start.right) * fraction, start.bottom + (stop.bottom - start.bottom) * fraction)
