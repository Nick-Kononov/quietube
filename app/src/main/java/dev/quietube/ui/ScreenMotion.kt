package dev.quietube.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/** NavHost transitions for button navigation: a pushed screen slides in over the previous one, which shifts left and dims. */
internal object ScreenMotion {
    private const val DURATION = 400
    private const val DIM = 0.6f
    /**
     * The back gesture is drawn by [BackPreview]; NavHost only has to keep both screens composed while it plays,
     * so its predictive transitions are visually neutral holds.
     */
    private const val HOLD = 1000

    val enter: Enter = { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } }
    val exit: Exit = { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } +
        fadeOut(tween(DURATION), targetAlpha = DIM) }
    val popEnter: Enter = { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } +
        fadeIn(tween(DURATION), initialAlpha = DIM) }
    val popExit: Exit = { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } }
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition =
        { fadeIn(tween(HOLD, easing = LinearEasing), initialAlpha = 0.99f) }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition =
        { fadeOut(tween(HOLD, easing = LinearEasing), targetAlpha = 0.99f) }
}

/**
 * Android's cross-activity back animation (what Settings shows), reproduced for NavHost screens. Values follow
 * WM Shell's DefaultCrossActivityBackAnimation: while dragging, the closing screen scales to 90% and, for a
 * left-edge swipe, moves to 8dp from the right edge; the previous screen sits 96dp to the left, scales with it and
 * is covered by an 80% black scrim. After release the closing screen fades out within the first fifth of a 450ms
 * emphasized animation while drifting on, and the previous screen grows to full size as the scrim clears.
 */
@Stable
internal class BackPreview(private val nav: NavController) {
    var closingId by mutableStateOf<String?>(null); private set
    var hiddenEntries by mutableStateOf(emptySet<String>()); private set
    var enteringId by mutableStateOf<String?>(null); private set
    var edge by mutableIntStateOf(NavigationEvent.EDGE_LEFT); private set
    var committed by mutableStateOf(false); private set
    /** Gesture progress after the system's back-gesture interpolator. */
    val gesture = Animatable(0f)
    /** Linear progress of the post-commit animation. */
    val commit = Animatable(0f)
    private var settling: Job? = null

    suspend fun follow(dispatcher: NavigationEventDispatcher, scope: CoroutineScope) {
        scope.launch {
            nav.visibleEntries.collect { entries -> hiddenEntries = hiddenEntries.intersect(entries.map { it.id }.toSet()) }
        }
        dispatcher.transitionState.collect { state ->
            when (state) {
                is NavigationEventTransitionState.InProgress -> {
                    if (committed && nav.currentBackStackEntry?.id != closingId) {
                        closingId?.let { hiddenEntries = hiddenEntries + it }
                        settling?.cancel(); reset()
                    }
                    if (closingId == null) {
                        // From the start destination the system's back-to-home animation runs instead.
                        val closing = nav.currentBackStackEntry ?: return@collect
                        val entering = nav.previousBackStackEntry ?: return@collect
                        settling?.cancel(); reset()
                        closingId = closing.id; enteringId = entering.id; edge = state.latestEvent.swipeEdge
                    }
                    gesture.snapTo(BackGestureEasing.transform(state.latestEvent.progress))
                }
                NavigationEventTransitionState.Idle -> if (closingId != null && settling?.isActive != true) settling = scope.launch {
                    withFrameNanos { } // NavHost pops on completion; let that land before checking.
                    if (nav.currentBackStackEntry?.id != closingId) {
                        committed = true
                        commit.animateTo(1f, tween(POST_COMMIT_MS, easing = LinearEasing))
                        // NavHost may retain its outgoing layer beyond our animation; never reveal it on reset.
                        closingId?.let { hiddenEntries = hiddenEntries + it }
                    } else gesture.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    reset()
                }
            }
        }
    }

    private suspend fun reset() {
        closingId = null; enteringId = null; committed = false
        gesture.snapTo(0f); commit.snapTo(0f)
    }

    companion object {
        const val SCALE = 0.9f
        const val SCRIM = 0.8f
        const val POST_COMMIT_MS = 450
        val MARGIN = 8.dp
        val ENTERING_OFFSET = 96.dp
        val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
        /** Android's Interpolators.EMPHASIZED (fast_out_extra_slow_in). */
        val EmphasizedEasing = PathEasing(Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        })
    }
}

@Composable
internal fun rememberBackPreview(nav: NavController): BackPreview {
    val preview = remember(nav) { BackPreview(nav) }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(preview, dispatcher) { dispatcher?.let { preview.follow(it, this) } }
    return preview
}

/** Hosts one NavHost screen: draws the back preview for it and rounds it like a window while it moves. */
@Composable
internal fun AnimatedVisibilityScope.ScreenCard(entry: NavBackStackEntry, preview: BackPreview, content: @Composable () -> Unit) {
    val radius = displayCornerRadius()
    val moving by transition.animateDp(transitionSpec = {
        if (targetState == EnterExitState.Visible) tween(100, delayMillis = 300) else tween(100)
    }, label = "screen corners") { if (it == EnterExitState.Visible) 0.dp else radius }
    val density = LocalDensity.current
    val margin = with(density) { BackPreview.MARGIN.toPx() }
    val offset = with(density) { BackPreview.ENTERING_OFFSET.toPx() }
    val radiusPx = with(density) { radius.toPx() }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val closing = preview.closingId == entry.id
        val entering = preview.enteringId == entry.id
        val width = size.width
        val p = preview.gesture.value
        val e = BackPreview.EmphasizedEasing.transform(preview.commit.value)
        // Scaling is about the centre, so translationX = target left edge − (width − scaled width) / 2.
        fun place(scale: Float, left: Float) { scaleX = scale; scaleY = scale; translationX = left - width * (1 - scale) / 2 }
        val dragScale = lerp(1f, BackPreview.SCALE, p)
        when {
            entry.id in preview.hiddenEntries -> { scaleX = 1f; scaleY = 1f; translationX = 0f; alpha = 0f }
            closing -> {
                val shift = if (preview.edge == NavigationEvent.EDGE_RIGHT) 0f else width * (1 - BackPreview.SCALE) / 2 - margin
                val dragLeft = width * (1 - dragScale) / 2 + shift * p
                if (preview.committed) {
                    place(lerp(dragScale, 1f, e), lerp(dragLeft, dragLeft + offset, e))
                    alpha = max(1f - preview.commit.value * 5, 0f)
                } else { place(dragScale, dragLeft); alpha = 1f }
            }
            entering -> {
                val dragLeft = -offset + width * (1 - dragScale) / 2
                if (preview.committed) place(lerp(dragScale, 1f, e), lerp(dragLeft, 0f, e)) else place(dragScale, dragLeft)
                alpha = 1f
            }
            else -> { scaleX = 1f; scaleY = 1f; translationX = 0f; alpha = 1f }
        }
        val corner = if (closing || entering) radiusPx else moving.toPx()
        shape = screenCorner(corner)
        clip = corner > 0f
    }.drawWithContent {
        drawContent()
        if (preview.enteringId == entry.id) {
            val scrim = if (preview.committed) BackPreview.SCRIM * (1 - preview.commit.value) else BackPreview.SCRIM
            drawRect(Color.Black, alpha = scrim)
        }
    }) { content() }
}

@Composable
private fun displayCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val px = if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius else null
        px?.takeIf { it > 0 }?.let { with(density) { it.toDp() } } ?: 28.dp
    }
}
