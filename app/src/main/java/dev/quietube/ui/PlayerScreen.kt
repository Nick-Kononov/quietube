@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.ui

import android.content.pm.PackageManager
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import dev.quietube.cast.CastState
import dev.quietube.cast.Tv
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import dev.quietube.playback.PlaybackState
import dev.quietube.account.actions.*
import dev.quietube.data.Rating
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(state: PlaybackState, player: Player?, connectionError: Boolean, inPip: Boolean,
    comments: CommentsState, commentActions: CommentActions, engagement: EngagementState, engagementActions: EngagementActions,
    window: PlayerWindow, settings: () -> Unit, quality: (Int?) -> Unit, play: (String) -> Unit, prefetch: (List<String>) -> Unit = {},
    cast: CastState = CastState(), castActions: CastActions? = null, snackbar: SnackbarHostState? = null, active: Boolean = true, thumbnailPrefetch: (List<ThumbnailRequest>) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var showQuality by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // Android 17 asks before an app reaches devices on the local network; TV discovery needs it.
    val localNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) castActions?.open?.invoke() else castActions?.denied?.invoke()
    }
    val openCast: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            localNetwork.launch(LOCAL_NETWORK)
        else castActions?.open?.invoke()
    }
    var detailsExpanded by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    SideEffect { window.sheetVisible(!inPip && (detailsExpanded || showQuality || cast.open)) }
    DisposableEffect(Unit) { onDispose { window.sheetVisible(false) } }
    val videoOnly = inPip || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    // Fullscreen video fills the window, including the area behind transient system bars.
    Column(Modifier.fillMaxSize().background(if (videoOnly) VideoBlack else MaterialTheme.colorScheme.background).then(if (videoOnly) Modifier else Modifier.safeDrawingPadding())) {
        Box(Modifier.fillMaxWidth().background(VideoBlack).then(if (videoOnly) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
            .onGloballyPositioned { coordinates ->
                val r = coordinates.boundsInWindow()
                window.bounds(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            }, contentAlignment = Alignment.Center) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
                setShowNextButton(false); setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // TextureView is unnecessary; SurfaceView lets the decoder render directly.
            } }, modifier = Modifier.fillMaxSize(), update = {
                it.player = player
                it.useController = !inPip
                it.keepScreenOn = state.isPlaying && !inPip
            }, onReset = null, onRelease = { it.player = null })
            if (state.resolving || state.buffering) PlayerLoadingIndicator()
            // Shown and hidden with the player's own controls, like YouTube's overlay.
            if (!inPip) androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                CompositionLocalProvider(LocalContentColor provides OverlayWhite) {
                    Row(Modifier.fillMaxWidth().then(if (videoOnly) Modifier.displayCutoutPadding() else Modifier).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayButton(window.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        if (castActions != null) OverlayButton(openCast, enabled = state.video != null) {
                            Icon(if (cast.playingOn != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, "Play on TV")
                        }
                        OverlayButton(window.pip, enabled = state.video != null && state.error == null && !state.resolving) {
                            Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture")
                        }
                        OverlayButton(window.toggleFullscreen) {
                            Icon(if (videoOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                if (videoOnly) "Exit fullscreen" else "Enter fullscreen")
                        }
                    }
                }
            }
        }
        if (!videoOnly) Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (detailsExpanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button) { detailsExpanded = !detailsExpanded }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(state.video?.title ?: "Choose a video from search",style = MaterialTheme.typography.titleMedium,maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
            }
            RatingButtons(engagement,state.video?.likeCount,engagementActions.rate,signInToRate = { snackbar?.let { scope.launch { it.showSnackbar("Sign in to rate") } } })
            cast.playingOn?.let { tv ->
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playing on ${tv.name}${if (cast.direct) " · direct" else ""}",style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (cast.direct && castActions != null) TextButton(onClick = castActions.stop) { Text("Stop") }
                }
            }
            if (connectionError) Text("Could not connect to playback. Close and reopen YouPoop.",modifier = Modifier.padding(horizontal = 20.dp))
            state.error?.let { error ->
                Text(error.message(),color = MaterialTheme.colorScheme.error,modifier = Modifier.padding(horizontal = 20.dp))
                TextButton(onClick = { state.video?.url?.let(play) }) { Text("Retry playback") }
            }
            key(state.video?.url) {
                PlayerCommunity(comments,commentActions.more,commentActions.refresh,commentActions.pullRefresh,state.related,play,prefetch,Modifier.weight(1f),active = active,thumbnailPrefetch = thumbnailPrefetch)
            }
        }
    }
    if (detailsExpanded && !inPip) DescriptionSheet(state, engagement, engagementActions.subscribe, engagementActions.refresh, snackbar,
        dismiss = { detailsExpanded = false }, quality = { detailsExpanded = false; showQuality = true },
        settings = { detailsExpanded = false; settings() })
    if (cast.open && !inPip && castActions != null) CastSheet(cast, castActions, snackbar)
    if (showQuality && !inPip) QualitySheet(state, snackbar, dismiss = { showQuality = false }) { quality(it); showQuality = false }
    }
}

/** A control drawn over the video; white whatever the theme. */
@Composable
private fun OverlayButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) = IconButton(onClick, enabled = enabled,
    colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), content = content)

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

class CommentActions(val more: () -> Unit, val refresh: () -> Unit, val pullRefresh: () -> Unit)
class EngagementActions(val rate: (Rating) -> Unit, val subscribe: () -> Unit, val refresh: () -> Unit)
/** What the player asks of its window: leaving, picture-in-picture, rotation, where the video sits (for the PiP animation) and whether a sheet covers it. */
class PlayerWindow(val back: () -> Unit, val pip: () -> Unit, val toggleFullscreen: () -> Unit, val bounds: (Rect) -> Unit, val sheetVisible: (Boolean) -> Unit)

class CastActions(val open: () -> Unit, val denied: () -> Unit, val scan: () -> Unit, val dismiss: () -> Unit,
    val play: (Tv, Boolean) -> Unit, val stop: () -> Unit)
