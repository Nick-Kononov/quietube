package dev.quietube.ui

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window

internal fun smoothRefreshRate(rates: List<Float>): Float {
    val supported = rates.filter { it.isFinite() && it > 0f }
    return supported.filter { it >= 119f }.minOrNull() ?: supported.maxOrNull() ?: 0f
}

/** A foreground-window request, never a change to the user's display settings. */
internal class SmoothRefreshRate(private val window: Window) {
    private val originalRate = window.attributes.preferredRefreshRate
    private val originalBalance = if (Build.VERSION.SDK_INT >= 35) window.isFrameRatePowerSavingsBalanced else false
    private var drawing = false
    // Keep the compositor out of its 60 Hz idle mode; a window preference alone does not hold it.
    private val redraw = object : Runnable {
        override fun run() {
            if (!drawing) return
            window.decorView.invalidate()
            window.decorView.postOnAnimation(this)
        }
    }
    fun active(enabled: Boolean) {
        if (drawing != enabled) {
            drawing = enabled
            if (enabled) window.decorView.postOnAnimation(redraw) else window.decorView.removeCallbacks(redraw)
        }
        val display = window.decorView.display ?: window.context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val current = display?.mode
        val rates = display?.supportedModes?.filter { current != null && it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }?.map { it.refreshRate }.orEmpty()
        val rate = if (enabled) smoothRefreshRate(rates) else originalRate
        val attributes = window.attributes
        if (attributes.preferredRefreshRate != rate) { attributes.preferredRefreshRate = rate; window.attributes = attributes }
        if (Build.VERSION.SDK_INT >= 35) {
            val balanced = if (enabled) false else originalBalance
            if (window.isFrameRatePowerSavingsBalanced != balanced) window.setFrameRatePowerSavingsBalanced(balanced)
        }
    }
}
