@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(ExperimentalSharedTransitionApi::class)
package dev.quietube.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import dev.quietube.account.actions.*
import dev.quietube.cast.CastState
import dev.quietube.cast.Tv
import dev.quietube.data.Rating
import dev.quietube.data.at
import dev.quietube.playback.PlaybackState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal val MiniPlayerHeight = 67.5.dp // A 120dp-wide 16:9 video.

/** Layout values the gesture callbacks need between frames; written during layout, never read by composition. */
private class LayerGeometry { var travel = 1f; var detailsVisible = 0f }

/**
 * The player as one layer above the app: the full player when [PlayerSheetState.fraction] is 1, the mini player
 * docked at [dock] when it is 0, and every step between while dragging. A single PlayerView moves and resizes with
 * it, so the video never hands over between two surfaces.
 */
@Composable
internal fun PlayerLayer(sheet: PlayerSheetState, dock: Rect?, state: PlaybackState, player: Player?, connectionError: Boolean, inPip: Boolean,
    comments: CommentsState, commentActions: CommentActions, engagement: EngagementState, engagementActions: EngagementActions,
    window: PlayerWindow, appearance: AppearanceSettings, settings: () -> Unit, quality: (Int?) -> Unit, play: (String) -> Unit,
    toggle: () -> Unit, close: () -> Unit, prefetch: (List<String>) -> Unit = {}, cast: CastState = CastState(), castActions: CastActions? = null,
    snackbar: SnackbarHostState? = null, thumbnailPrefetch: (List<ThumbnailRequest>) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val animate = rememberAnimationsEnabled()
    SideEffect { sheet.animate = animate }
    val share = LocalPlayerShare.current
    val videoOnly = inPip || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val open by remember { derivedStateOf { sheet.fraction == 1f } }
    val docked by remember { derivedStateOf { sheet.fraction < .25f } }
    val geometry = remember { LayerGeometry() }
    var showQuality by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    val context = LocalContext.current
    // Android 17 asks before an app reaches devices on the local network; TV discovery needs it.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) castActions?.open?.invoke() else castActions?.denied?.invoke()
    }
    val openCast: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            localNetwork.launch(LOCAL_NETWORK)
        else castActions?.open?.invoke()
    }
    val sheetsShown = open && !inPip
    SideEffect { window.sheetVisible(sheetsShown && (detailsExpanded || showQuality || cast.open)) }
    DisposableEffect(Unit) { onDispose { window.sheetVisible(false) } }

    // Back collapses the full player and follows the gesture while it is held.
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(back, isBackEnabled = sheet.expanded && !inPip,
        onBackCancelled = { sheet.settle(scope, true) }, onBackCompleted = { sheet.settle(scope, false) })
    LaunchedEffect(back) {
        snapshotFlow { back.transitionState }.collect { transition ->
            if (transition is NavigationEventTransitionState.InProgress && sheet.expanded)
                sheet.preview(1f - BackPreview.BackGestureEasing.transform(transition.latestEvent.progress) * .5f)
        }
    }

    val image = state.video?.let { highResThumbnail(it.url) ?: it.thumbnailUrl } ?: share?.opening?.image
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val accents = rememberVideoAccents(image, dark, appearance.videoColours)
    val detailsScheme = accents?.let { MaterialTheme.colorScheme.withAccents(it) } ?: MaterialTheme.colorScheme
    val ambient = image != null && ambientAllowed(appearance.ambient, dark, videoOnly, Build.VERSION.SDK_INT, rememberPowerSaveMode())
    // A video opened from a card fades the player in around the flying thumbnail.
    // Created during composition, so the first frame of an opening is already transparent.
    val appear = remember(share?.opening) { Animatable(if (share?.opening?.fade == true) 0f else 1f) }
    LaunchedEffect(appear, share?.departed) { if (share?.opening == null || share.departed) appear.animateTo(1f, tween(300)) }
    if (share != null) PosterLifecycle(share, state)

    val safe = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val top = safe.getTop(density).toFloat()
    val insetStart = safe.getLeft(density, layoutDirection).toFloat()
    val insetEnd = safe.getRight(density, layoutDirection).toFloat()
    val bottomInset = with(density) { safe.asPaddingValues().calculateBottomPadding() }
    val miniPx = with(density) { MiniPlayerHeight.toPx() }
    val ambientExtra = with(density) { 160.dp.toPx() }
    val miniColour = MaterialTheme.colorScheme.surfaceContainerHigh
    val fullColour = if (videoOnly) VideoBlack else MaterialTheme.colorScheme.background

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Layout(content = {
        // Also stops touches reaching the app underneath.
        Box(Modifier.layoutId("background").graphicsLayer { alpha = appear.value }
            .drawBehind { drawRect(lerp(miniColour, fullColour, sheet.fraction)) }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        if (ambient && image != null) AmbientGlow(image, Modifier.layoutId("ambient").graphicsLayer { alpha = sheet.fraction * appear.value })
        if (!videoOnly) Box(Modifier.layoutId("details")
            .graphicsLayer { alpha = ((sheet.fraction - .5f) * 2f).coerceIn(0f, 1f) * appear.value }
            .drawWithContent { sheet.fraction; clipRect(bottom = geometry.detailsVisible) { this@drawWithContent.drawContent() } }) {
            MaterialExpressiveTheme(colorScheme = detailsScheme, motionScheme = MaterialTheme.motionScheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography) {
                CompositionLocalProvider(LocalContentColor provides detailsScheme.onSurface) {
                    PlayerDetails(state, connectionError, comments, commentActions, engagement, engagementActions, cast, castActions, snackbar,
                        detailsExpanded, { detailsExpanded = !detailsExpanded }, play, prefetch, active = open && sheet.expanded,
                        thumbnailPrefetch = thumbnailPrefetch, modifier = Modifier.padding(bottom = bottomInset))
                }
            }
        }
        Box(Modifier.layoutId("video").onGloballyPositioned { coordinates ->
            if (sheet.fraction == 1f) coordinates.boundsInWindow().let { window.bounds(AndroidRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())) }
        }) {
            PlayerVideo(sheet, state, player, inPip, videoOnly, open, docked, cast, castActions, openCast, window, geometry, share)
        }
        if (!open) MiniCatcher(sheet, state, geometry, close, Modifier.layoutId("catcher"))
        if (docked) MiniControls(state, toggle, close, Modifier.layoutId("mini").graphicsLayer { alpha = (1f - sheet.fraction * 4f).coerceIn(0f, 1f) })
    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val f = if (inPip) 1f else sheet.fraction
        val dockRect = dock?.takeIf { it.width > 0 && it.height > 0 } ?: Rect(0f, h - miniPx, w, h)
        val container = lerpRect(dockRect, Rect(0f, 0f, w, h), f)
        val full = fullVideoRect(Size(w, h), top, videoOnly)
        val video = lerpRect(miniVideoRect(dockRect), full, f)
        geometry.travel = dockRect.top.coerceAtLeast(1f)
        geometry.detailsVisible = (container.bottom - video.bottom).coerceAtLeast(0f)
        fun fixed(width: Float, height: Float) = Constraints.fixed(width.roundToInt().coerceAtLeast(0), height.roundToInt().coerceAtLeast(0))
        fun id(name: String) = measurables.firstOrNull { it.layoutId == name }
        val background = id("background")?.measure(fixed(container.width, container.height))
        val glow = id("ambient")?.takeIf { f > 0f }?.measure(fixed(w, full.bottom + ambientExtra))
        val details = id("details")?.takeIf { f > .5f }?.measure(fixed(w - insetStart - insetEnd, h - full.bottom))
        val videoBox = id("video")?.measure(fixed(video.width, video.height))
        val catcher = id("catcher")?.measure(fixed(dockRect.width, dockRect.height))
        val mini = id("mini")?.measure(fixed(container.right - video.right, video.height))
        val dx = if (f == 0f) sheet.dismissOffset.roundToInt() else 0
        layout(constraints.maxWidth, constraints.maxHeight) {
            background?.place(container.left.roundToInt() + dx, container.top.roundToInt())
            glow?.place(dx, container.top.roundToInt())
            details?.place(insetStart.roundToInt(), video.bottom.roundToInt())
            videoBox?.place(video.left.roundToInt() + dx, video.top.roundToInt())
            // Fixed at the dock, so a drag that starts here keeps steady coordinates.
            catcher?.place(dockRect.left.roundToInt(), dockRect.top.roundToInt())
            mini?.place(video.right.roundToInt() + dx, video.top.roundToInt())
        }
    }
    if (sheetsShown && detailsExpanded) DescriptionSheet(state, engagement, engagementActions.subscribe, engagementActions.refresh, snackbar,
        dismiss = { detailsExpanded = false }, quality = { detailsExpanded = false; showQuality = true },
        settings = { detailsExpanded = false; settings() })
    if (sheetsShown && cast.open && castActions != null) CastSheet(cast, castActions, snackbar)
    if (sheetsShown && showQuality) QualitySheet(state, snackbar, dismiss = { showQuality = false }) { quality(it); showQuality = false }
    }
}

/** The thumbnail lands, then stays as a poster until the opened video plays (or fails), at most 20 seconds. */
@Composable
private fun PosterLifecycle(share: PlayerShare, state: PlaybackState) {
    // Runs once this composition has been applied; the next frame comes after the layer's first layout and draw.
    LaunchedEffect(share.opening) { if (share.opening != null && !share.departed) { withFrameNanos {}; share.depart() } }
    LaunchedEffect(share.flying, share.departed) {
        if (!share.flying || !share.departed) return@LaunchedEffect
        withTimeoutOrNull(600) { snapshotFlow { share.scope.isTransitionActive }.first { it } }
        snapshotFlow { share.scope.isTransitionActive }.first { !it }
        share.landed()
    }
    val opening = share.opening
    LaunchedEffect(opening, share.flying, state.video?.url, state.resolving, state.isPlaying, state.error, state.playWhenReady) {
        if (opening == null || share.flying) return@LaunchedEffect
        if (sameVideo(opening.url, state.video?.url) && !state.resolving && (state.isPlaying || state.error != null || !state.playWhenReady)) share.finish()
    }
    LaunchedEffect(opening) { if (opening != null) { delay(20_000); share.finish() } }
}

private class SpeedHold { var normal = 1f; var active = false }

@Composable
private fun PlayerVideo(sheet: PlayerSheetState, state: PlaybackState, player: Player?, inPip: Boolean, videoOnly: Boolean, open: Boolean, docked: Boolean,
    cast: CastState, castActions: CastActions?, openCast: () -> Unit, window: PlayerWindow, geometry: LayerGeometry, share: PlayerShare?) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var view by remember { mutableStateOf<PlayerView?>(null) }
    var controlsVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<SeekFeedback?>(null) }
    LaunchedEffect(feedback?.serial) { if (feedback != null) { delay(800); feedback = null } }
    var zoom by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(videoOnly) { if (!videoOnly) zoom = false }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { delay(1_000); note = null } }
    val hold = remember { SpeedHold() }
    var holding by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var span by remember { mutableStateOf<TimeBarSpan?>(null) }
    val flying = share?.flying == true
    val live = state.video?.isLive == true
    val casting = cast.playingOn != null
    val current by rememberUpdatedState(Triple(player, videoOnly, live || casting))
    val toggleFullscreen by rememberUpdatedState(window.toggleFullscreen)
    val swipe = with(density) { 56.dp.toPx() }
    val gestures = remember(sheet) { object : VideoGestureListener {
        private var dragged = 0f
        override fun tap() { view?.performClick() }
        override fun seek(side: Int, continuing: Boolean) {
            val target = current.first ?: return
            if (!target.isCurrentMediaItemSeekable) return
            feedback = feedback.next(side, continuing)
            target.seekTo(seekTarget(target.currentPosition, target.duration, side * SEEK_STEP_S * 1000L))
        }
        override fun holdStart(): Boolean {
            val target = current.first ?: return false
            if (current.third || hold.active) return false
            hold.normal = target.playbackParameters.speed; hold.active = true; holding = true
            target.setPlaybackSpeed(2f)
            return true
        }
        override fun holdEnd() {
            if (!hold.active) return
            hold.active = false; holding = false
            current.first?.setPlaybackSpeed(hold.normal)
        }
        override fun drag(dy: Float) { dragged += dy; if (!current.second) sheet.drag(-dy / geometry.travel) }
        override fun dragEnd(velocityY: Float) {
            val total = dragged
            dragged = 0f
            when {
                current.second -> if (total > swipe) toggleFullscreen()
                sheet.fraction == 1f && total < -swipe -> toggleFullscreen()
                else -> sheet.release(scope, -velocityY / geometry.travel)
            }
        }
        override fun pinch(zoomIn: Boolean) {
            if (!current.second || zoom == zoomIn) return
            zoom = zoomIn; note = if (zoomIn) "Zoomed to fill" else "Fit to screen"
        }
    } }
    val chapters = state.chapters
    val markers = remember { arrayOfNulls<Any>(1) }
    val seekable = !live && state.video != null && state.error == null
    Box(Modifier.fillMaxSize().background(if (flying) Color.Transparent else VideoBlack).semantics {
        if (open && !inPip) {
            contentDescription = "Video"
            customActions = buildList {
                if (seekable) {
                    add(CustomAccessibilityAction("Seek back $SEEK_STEP_S seconds") { gestures.seek(-1, false); true })
                    add(CustomAccessibilityAction("Seek forward $SEEK_STEP_S seconds") { gestures.seek(1, false); true })
                }
                if (!live && !casting) add(CustomAccessibilityAction(if (holding) "Play at normal speed" else "Play at 2× speed") {
                    if (holding) gestures.holdEnd() else gestures.holdStart(); true
                })
                add(CustomAccessibilityAction(if (videoOnly) "Exit fullscreen" else "Enter fullscreen") { window.toggleFullscreen(); true })
                if (videoOnly) add(CustomAccessibilityAction(if (zoom) "Fit video to screen" else "Zoom video to fill") { zoom = !zoom; true })
                add(CustomAccessibilityAction("Collapse player") { window.collapse(); true })
            }
        }
    }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
            setShowNextButton(false); setShowPreviousButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // TextureView is unnecessary; SurfaceView lets the decoder render directly.
            overlayFrameLayout?.addView(VideoGestureView(context, gestures), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val root = this
            findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
                // Chapter starts reuse the time bar's ad markers, drawn as dark gaps in the track.
                bar.setAdMarkerColor(CHAPTER_GAP); bar.setPlayedAdMarkerColor(CHAPTER_GAP)
                bar.addListener(object : TimeBar.OnScrubListener {
                    override fun onScrubStart(timeBar: TimeBar, position: Long) { scrub = position }
                    override fun onScrubMove(timeBar: TimeBar, position: Long) { scrub = position }
                    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { scrub = null }
                })
                bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> span = timeBarSpan(v, root) }
            }
            view = this
        } }, modifier = Modifier.fillMaxSize(), update = {
            it.player = player
            it.useController = open && !inPip
            it.keepScreenOn = state.isPlaying && !inPip && sheet.expanded
            it.resizeMode = if (videoOnly && zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            // The thumbnail flies in over an empty slot rather than a black surface.
            it.visibility = if (flying) View.INVISIBLE else View.VISIBLE
            if (markers[0] !== chapters) {
                markers[0] = chapters
                val starts = chapters.map { chapter -> chapter.startMs }.filter { start -> start > 0 }
                it.setExtraAdGroupMarkers(starts.toLongArray(), BooleanArray(starts.size))
            }
        }, onReset = null, onRelease = { it.player = null })
        if (share != null) Poster(share)
        if (state.resolving || state.buffering) { if (docked) AppLoadingIndicator(Modifier.size(28.dp)) else PlayerLoadingIndicator() }
        if (open && !inPip) {
            SeekBubble(feedback)
            if (holding) VideoPill("2× ▸▸", Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
            note?.let { VideoPill(it, Modifier.align(Alignment.Center)) }
            val bar = span
            val duration = player?.duration?.takeIf { it > 0 } ?: ((state.video?.durationSeconds ?: 0L) * 1000)
            val scrubbing = scrub
            if (bar != null && scrubbing != null && (state.storyboard != null || chapters.isNotEmpty()))
                ScrubPreview(state.storyboard, chapters, scrubbing, duration, bar)
            else if (bar != null && controlsVisible && chapters.isNotEmpty()) {
                var position by remember { mutableLongStateOf(0L) }
                LaunchedEffect(player) { while (player != null) { position = player.currentPosition; delay(500) } }
                chapters.at(position)?.let { ChapterLabel(it.title, bar) }
            }
            // Shown and hidden with the player's own controls, like YouTube's overlay.
            androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                CompositionLocalProvider(LocalContentColor provides OverlayWhite) {
                    Row(Modifier.fillMaxWidth().then(if (videoOnly) Modifier.displayCutoutPadding() else Modifier).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayButton(window.collapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player") }
                        Spacer(Modifier.weight(1f))
                        if (castActions != null) OverlayButton(openCast, enabled = state.video != null) {
                            Icon(if (cast.playingOn != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, "Play on TV")
                        }
                        OverlayButton(window.pip, enabled = state.video != null && state.error == null && !state.resolving) {
                            Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture")
                        }
                        OverlayButton(window.toggleFullscreen) {
                            Icon(if (videoOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (videoOnly) "Exit fullscreen" else "Enter fullscreen")
                        }
                    }
                }
            }
        }
    }
}

/** The opened card's thumbnail: the shared element's landing spot, then a poster over the not-yet-playing surface. */
@Composable
private fun Poster(share: PlayerShare) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = share.opening != null && share.departed
    var last by remember { mutableStateOf(share.opening) }
    share.opening?.let { last = it }
    AnimatedVisibility(visible, enter = fadeIn(tween(FLIGHT_MS)), exit = fadeOut()) {
        val shown = last ?: return@AnimatedVisibility
        val shared = if (share.flying) with(share.scope) { Modifier.sharedElement(rememberSharedContentState(sharedThumbnailKey(shown.url)), this@AnimatedVisibility, FlightBounds) } else Modifier
        val decoded = shown.decoded
        if (decoded != null) {
            val context = LocalContext.current
            Image(remember(decoded) { decoded.asPainter(context) }, null, Modifier.fillMaxSize().then(shared), contentScale = ContentScale.Crop)
        } else AsyncImage(shown.image, null, Modifier.fillMaxSize().then(shared), contentScale = ContentScale.Crop)
    }
}

/** Taps, vertical drags and sideways swipes on the docked mini player. */
@Composable
private fun MiniCatcher(sheet: PlayerSheetState, state: PlaybackState, geometry: LayerGeometry, close: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val currentClose by rememberUpdatedState(close)
    Box(modifier.semantics {
        contentDescription = listOfNotNull(state.video?.title, state.video?.channel?.takeIf(String::isNotBlank)).joinToString(", ")
        role = Role.Button
        onClick("Expand player") { sheet.settle(scope, true); true }
        customActions = listOf(CustomAccessibilityAction("Close player") { currentClose(); true })
    }.pointerInput(sheet) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var axis = 0 // 1 vertical, 2 sideways
            var moved = Offset.Zero
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    if (axis == 0) sheet.settle(scope, true)
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                if (axis == 0) {
                    moved += delta
                    if (moved.getDistance() < viewConfiguration.touchSlop) continue
                    axis = if (abs(moved.x) > abs(moved.y)) 2 else 1
                }
                change.consume()
                if (axis == 1) sheet.drag(-delta.y / geometry.travel) else sheet.dismissDrag(delta.x)
            }
            if (axis != 0) {
                val velocity = tracker.calculateVelocity()
                if (axis == 1) sheet.release(scope, -velocity.y / geometry.travel)
                else sheet.dismissRelease(scope, velocity.x, size.width.toFloat()) { currentClose() }
            }
        }
    })
}

@Composable
private fun MiniControls(state: PlaybackState, toggle: () -> Unit, close: () -> Unit, modifier: Modifier) {
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).clearAndSetSemantics {}) {
            Text(state.video?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.video?.channel.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = toggle) {
            Icon(if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playWhenReady) "Pause" else "Play")
        }
        IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close player") }
    }
}

@Composable
private fun PlayerDetails(state: PlaybackState, connectionError: Boolean, comments: CommentsState, commentActions: CommentActions,
    engagement: EngagementState, engagementActions: EngagementActions, cast: CastState, castActions: CastActions?, snackbar: SnackbarHostState?,
    detailsExpanded: Boolean, toggleDetails: () -> Unit, play: (String) -> Unit, prefetch: (List<String>) -> Unit, active: Boolean,
    thumbnailPrefetch: (List<ThumbnailRequest>) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (detailsExpanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button, onClick = toggleDetails).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(state.video?.title ?: "Choose a video from search", style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        RatingButtons(engagement, state.video?.likeCount, engagementActions.rate, signInToRate = { snackbar?.let { scope.launch { it.showSnackbar("Sign in to rate") } } })
        cast.playingOn?.let { tv ->
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Playing on ${tv.name}${if (cast.direct) " · direct" else ""}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (cast.direct && castActions != null) TextButton(onClick = castActions.stop) { Text("Stop") }
            }
        }
        if (connectionError) Text("Could not connect to playback. Close and reopen YouPoop.", modifier = Modifier.padding(horizontal = 20.dp))
        state.error?.let { error ->
            Text(error.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton(onClick = { state.video?.url?.let(play) }) { Text("Retry playback") }
        }
        key(state.video?.url) {
            PlayerCommunity(comments, commentActions.more, commentActions.refresh, commentActions.pullRefresh, state.related, play, prefetch, Modifier.weight(1f), active = active, thumbnailPrefetch = thumbnailPrefetch)
        }
    }
}

private fun timeBarSpan(bar: View, root: View): TimeBarSpan {
    // Layout positions, not window positions: the controller slides the bar away while hiding.
    var x = 0f
    var y = 0f
    var view: View? = bar
    while (view != null && view !== root) { x += view.left; y += view.top; view = view.parent as? View }
    // DefaultTimeBar insets its track by half the dragged scrubber (16dp).
    val inset = 8 * bar.resources.displayMetrics.density
    return TimeBarSpan(x + bar.paddingLeft + inset, x + bar.width - bar.paddingRight - inset, y)
}

/** A control drawn over the video; white whatever the theme. */
@Composable
private fun OverlayButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) = IconButton(onClick, enabled = enabled,
    colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), content = content)

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val CHAPTER_GAP = 0x99000000.toInt()

class CommentActions(val more: () -> Unit, val refresh: () -> Unit, val pullRefresh: () -> Unit)
class EngagementActions(val rate: (Rating) -> Unit, val subscribe: () -> Unit, val refresh: () -> Unit)
/** What the player asks of its window: collapsing, picture-in-picture, rotation, where the video sits (for the PiP animation) and whether a sheet covers it. */
class PlayerWindow(val collapse: () -> Unit, val pip: () -> Unit, val toggleFullscreen: () -> Unit, val bounds: (AndroidRect) -> Unit, val sheetVisible: (Boolean) -> Unit)

class CastActions(val open: () -> Unit, val denied: () -> Unit, val scan: () -> Unit, val dismiss: () -> Unit,
    val play: (Tv, Boolean) -> Unit, val stop: () -> Unit)
