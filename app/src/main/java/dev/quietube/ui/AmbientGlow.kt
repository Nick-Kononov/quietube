package dev.quietube.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Ambient mode needs blur (API 31), a dark portrait player outside PiP, and no battery saver. */
internal fun ambientAllowed(enabled: Boolean, dark: Boolean, videoOnly: Boolean, sdk: Int, powerSave: Boolean): Boolean =
    enabled && dark && !videoOnly && sdk >= 31 && !powerSave

/**
 * A soft glow of the video's colours behind the player and title: a tiny thumbnail, blurred once and faded out
 * downwards. It never samples video frames.
 */
@Composable
internal fun AmbientGlow(image: String, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 31) return
    val context = LocalContext.current
    val request = remember(image) { ImageRequest.Builder(context).data(image).size(64, 36).crossfade(true).build() }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = modifier.clearAndSetSemantics {}
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = .5f }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Black, .55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
        .blur(56.dp, BlurredEdgeTreatment.Rectangle))
}

@Composable
internal fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saving by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { saving = power.isPowerSaveMode } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        saving = power.isPowerSaveMode
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}
