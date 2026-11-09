package dev.quietube.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun VideoCardPlaceholder() {
    val animate = rememberAnimationsEnabled()
    val offset = if (animate) {
        val transition = rememberInfiniteTransition(label = "Loading")
        val value by transition.animateFloat(-600f, 1200f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "Shimmer")
        value
    } else 0f
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = if (animate) MaterialTheme.colorScheme.surfaceContainerHighest else base
    val brush = Brush.linearGradient(listOf(base, highlight, base), Offset(offset, 0f), Offset(offset + 600f, 300f))
    Column(Modifier.padding(horizontal = 20.dp).semantics { contentDescription = "Loading video" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(brush))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(.75f).height(16.dp).background(brush, MaterialTheme.shapes.extraSmall))
    }
}

@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var animate by remember { mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animate = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return animate
}
