@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.ui

import android.graphics.Rect
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
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

@Composable
fun PlayerScreen(state: PlaybackState, player: androidx.media3.common.Player?, connectionError: Boolean, inPip: Boolean,
    comments: CommentsState, moreComments: () -> Unit, refreshComments: () -> Unit, pullRefreshComments: () -> Unit,
    engagement: EngagementState, rate: (Rating) -> Unit, subscribe: () -> Unit, refreshAccount: () -> Unit, settings: () -> Unit,
    back: () -> Unit, pip: () -> Unit, toggleFullscreen: () -> Unit, bounds: (Rect) -> Unit, quality: (Int?) -> Unit, play: (String) -> Unit, prefetch: (List<String>) -> Unit = {},
    cast: CastState = CastState(), castActions: CastActions? = null, snackbar: SnackbarHostState? = null, sheetVisible: (Boolean) -> Unit = {}) {
    var showQuality by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Android 17 asks before an app reaches devices on the local network; TV discovery needs it.
    val localNetwork = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) castActions?.open?.invoke() else castActions?.denied?.invoke()
    }
    val openCast: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            localNetwork.launch(LOCAL_NETWORK)
        else castActions?.open?.invoke()
    }
    var detailsExpanded by rememberSaveable(state.video?.url) { mutableStateOf(false) }
    SideEffect { sheetVisible(!inPip && (detailsExpanded || showQuality || cast.open)) }
    DisposableEffect(Unit) { onDispose { sheetVisible(false) } }
    val videoOnly = inPip || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    // Fullscreen video fills the window, including the area behind transient system bars.
    Column(Modifier.fillMaxSize().background(if (videoOnly) VideoBlack else MaterialTheme.colorScheme.background).then(if (videoOnly) Modifier else Modifier.safeDrawingPadding())) {
        Box(Modifier.fillMaxWidth().background(VideoBlack).then(if (videoOnly) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
            .onGloballyPositioned { coordinates ->
                val r = coordinates.boundsInWindow()
                bounds(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            }, contentAlignment = Alignment.Center) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                setShowNextButton(false); setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // TextureView is unnecessary; SurfaceView lets the decoder render directly.
            } }, modifier = Modifier.fillMaxSize(), update = {
                it.player = player
                it.useController = !inPip
                it.keepScreenOn = state.isPlaying && !inPip
            }, onReset = null, onRelease = { it.player = null })
            if (state.resolving || state.buffering) CircularProgressIndicator()
            // Shown and hidden with the player's own controls, like YouTube's overlay.
            if (!inPip) androidx.compose.animation.AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                CompositionLocalProvider(LocalContentColor provides OverlayWhite) {
                    Row(Modifier.fillMaxWidth().then(if (videoOnly) Modifier.displayCutoutPadding() else Modifier).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        if (castActions != null) IconButton(colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), onClick = openCast, enabled = state.video != null) {
                            Icon(if (cast.playingOn != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, "Play on TV")
                        }
                        IconButton(colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), onClick = pip, enabled = state.video != null && state.error == null && !state.resolving) {
                            Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture")
                        }
                        IconButton(colors = IconButtonDefaults.iconButtonColors(contentColor = OverlayWhite, disabledContentColor = OverlayWhite.copy(alpha = .38f)), onClick = toggleFullscreen) {
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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
            }
            RatingButtons(engagement,state.video?.likeCount,rate)
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
                PlayerCommunity(comments,moreComments,refreshComments,pullRefreshComments,state.related,play,prefetch,Modifier.weight(1f))
            }
        }
    }
    if (detailsExpanded && !inPip) DescriptionSheet(state, engagement, subscribe, refreshAccount, snackbar,
        dismiss = { detailsExpanded = false }, quality = { detailsExpanded = false; showQuality = true },
        settings = { detailsExpanded = false; settings() })
    if (cast.open && !inPip && castActions != null) CastSheet(cast, castActions, snackbar)
    if (showQuality && !inPip) QualitySheet(state, snackbar, dismiss = { showQuality = false }) { quality(it); showQuality = false }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

class CastActions(val open: () -> Unit, val denied: () -> Unit, val scan: () -> Unit, val dismiss: () -> Unit,
    val play: (Tv, Boolean) -> Unit, val stop: () -> Unit)

/** Always visible under the title; rating needs a signed-in session, counts show either way. */
@Composable
private fun RatingButtons(engagement: EngagementState,publicLikes: Long?,rate: (Rating) -> Unit) {
    val enabled = engagement.signedIn && !engagement.busy && engagement.url != null
    val liked = engagement.info.rating == Rating.Like
    val disliked = engagement.info.rating == Rating.Dislike
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = liked,onClick = { rate(Rating.Like) },enabled = enabled,
            leadingIcon = { Icon(if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,null,Modifier.size(18.dp)) },
            label = { Text(displayLikeCount(engagement.info.likes,publicLikes)) },
            modifier = Modifier.semantics { contentDescription = if (liked) "Remove like" else "Like" })
        FilterChip(selected = disliked,onClick = { rate(Rating.Dislike) },enabled = enabled,
            leadingIcon = { Icon(if (disliked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown,null,Modifier.size(18.dp)) },
            label = { Text(displayDislikeCount(engagement.info.dislikes,engagement.info.estimatedDislikes)) },
            modifier = Modifier.semantics { contentDescription = if (disliked) "Remove dislike" else "Dislike" })
        if (!engagement.signedIn) Text("Sign in to rate",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
