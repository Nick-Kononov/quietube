@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.media3.common.Player
import dev.quietube.account.AccountViewModel
import dev.quietube.account.FeedState
import dev.quietube.account.FeedViewModel
import dev.quietube.account.WebLoginActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint
import dev.quietube.account.actions.EngagementViewModel
import dev.quietube.cast.CastPlayer
import dev.quietube.cast.CastViewModel
import dev.quietube.data.AccountFeed
import dev.quietube.data.VideoRepository
import dev.quietube.links.parseYouTubeLink
import dev.quietube.links.sharedYouTubeLink
import dev.quietube.playback.PlaybackViewModel
import dev.quietube.ui.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var thumbnails: ThumbnailPrefetcher
    @Inject lateinit var videos: VideoRepository
    private var inPip by mutableStateOf(false)
    private var openPlayer by mutableStateOf(false)
    private var linkError by mutableStateOf<String?>(null)
    private var pendingVideo by mutableStateOf<String?>(null)
    private var pipEligible = false
    private var playerBounds: Rect? = null
    private var appliedPip: Pair<Boolean, Rect?>? = null
    private var fullscreen = false
    private var refreshResumed = false
    private val smoothRefresh by lazy { SmoothRefreshRate(window) }

    private val search: SearchViewModel by viewModels()
    private val playback: PlaybackViewModel by viewModels()
    private val account: AccountViewModel by viewModels()
    private val engagement: EngagementViewModel by viewModels()
    private val feeds: FeedViewModel by viewModels()
    private val comments: CommentsViewModel by viewModels()
    private val casting: CastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (theme or locale change) must not replay the launch request.
        if (savedInstanceState == null) readIntent(intent)
        var themeReady = false
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (themeReady) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return themeReady
            }
        })
        setContent {
            val appearance by account.appearance.collectAsStateWithLifecycle()
            // Keep the launch window until DataStore has supplied the saved appearance.
            val settings = appearance ?: return@setContent
            val dark = useDarkTheme(settings.mode, isSystemInDarkTheme())
            QuietubeTheme(settings) {
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    themeReady = true
                    window.setBackgroundDrawable(background.toArgb().toDrawable())
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(settings.mode) {
                    if (Build.VERSION.SDK_INT >= 31) getSystemService(UiModeManager::class.java).setApplicationNightMode(when (settings.mode) {
                        ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                    })
                }
                App(settings)
            }
        }
    }

    /** Navigation, the shared snackbar and the mini player around the screens. */
    @Composable
    private fun App(settings: AppearanceSettings) {
        val nav = rememberNavController()
        val feedPages = rememberSaveableStateHolder()
        val session by account.session.collectAsStateWithLifecycle()
        val feed by feeds.state.collectAsStateWithLifecycle()
        val playing by playback.state.collectAsStateWithLifecycle()
        val controller by playback.controller.collectAsStateWithLifecycle()
        var searchExpanded by remember { mutableStateOf(false) }
        var playerSheetVisible by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        Snackbars(snackbar)
        EngagementHaptics()
        CastFollowsPlayer(controller, playing.isPlaying)
        val entry by nav.currentBackStackEntryAsState()
        val currentRoute = entry?.destination?.route
        val playerVisible = currentRoute == "player"
        var selectedTopRoute by rememberSaveable { mutableStateOf(TopDestination.Home.route) }
        val selectedTop = TopDestination.entries.firstOrNull { it.route == selectedTopRoute } ?: TopDestination.Home
        var reselections by rememberSaveable { mutableStateOf(emptyMap<String, Int>()) }
        LaunchedEffect(currentRoute, feed.initialized, feed.feed, session.ready) {
            routeTop(currentRoute)?.let { selectedTopRoute = it.route }
            if (feed.initialized && session.ready) routeFeed(currentRoute)?.takeIf { it != feed.feed }?.let(feeds::select)
        }
        val windowWidth = LocalWindowInfo.current.containerSize.width
        val wideNavigation = useNavigationRail(with(LocalDensity.current) { windowWidth.toDp().value.toInt() })
        val selectTop: (TopDestination) -> Unit = { destination ->
            if (routeTop(currentRoute) == destination) {
                val route = currentRoute ?: destination.route
                val key = feedPageKey(route, feeds.state.value.playlist?.id.takeIf { routeFeed(route) == feeds.state.value.feed })
                reselections = reselections + (key to ((reselections[key] ?: 0) + 1))
            } else nav.navigate(destination.route) {
                popUpTo("home")
                launchSingleTop = true
            }
        }
        LaunchedEffect(currentRoute) { videos.prefetch(emptyList()); thumbnails.submit(emptyList()) }
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        SideEffect { updateFullscreen(playerVisible && landscape && !inPip) }
        DisposableEffect(playerVisible) {
            onDispose {
                // Leave rotation under the user's system setting outside the player.
                if (playerVisible) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        val goSettings: () -> Unit = { nav.open("settings") }
        val goPlayer: () -> Unit = { nav.open("player") }
        val play: (String) -> Unit = { url -> playback.play(url); goPlayer() }
        LaunchedEffect(openPlayer, pendingVideo) {
            pendingVideo?.let { playback.play(it); pendingVideo = null }
            if (openPlayer) { goPlayer(); openPlayer = false }
        }
        SideEffect { updatePip(playerVisible && playing.playWhenReady && !playing.resolving && playing.error == null) }
        AppNavigation(selectedTop, playerVisible || (currentRoute == "search" && searchExpanded), wideNavigation, selectTop) {
            Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = {
                if (!inPip && !playerSheetVisible) SnackbarHost(snackbar, Modifier.navigationBarsPadding())
            }) { outerInsets ->
                Column(Modifier.fillMaxSize().padding(outerInsets)) {
                    val preview = rememberBackPreview(nav)
                    NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f),
                        enterTransition = ScreenMotion.enter, exitTransition = ScreenMotion.exit,
                        popEnterTransition = ScreenMotion.popEnter, popExitTransition = ScreenMotion.popExit,
                        predictivePopEnterTransition = ScreenMotion.predictivePopEnter, predictivePopExitTransition = ScreenMotion.predictivePopExit) {
                        AccountFeed.entries.forEach { destination ->
                            composable(feedRoute(destination)) { entry -> ScreenCard(entry, preview) {
                                FeedDestination(destination, nav, currentRoute, feedPages, reselections, play)
                            } }
                        }
                        composable("library") { entry -> ScreenCard(entry, preview) {
                            feedPages.SaveableStateProvider("library") { LibraryDestination(nav, reselections["library"] ?: 0) }
                        } }
                        composable("account") { entry -> ScreenCard(entry, preview) { AccountDestination(nav, goSettings) } }
                        composable("settings") { entry -> ScreenCard(entry, preview) { SettingsDestination(nav, settings) } }
                        composable("search") { entry -> ScreenCard(entry, preview) { SearchDestination(nav, play) { searchExpanded = it } } }
                        composable("player") { entry -> ScreenCard(entry, preview) {
                            PlayerDestination(nav, currentRoute, snackbar, landscape, goSettings) { playerSheetVisible = it }
                        } }
                    }
                    if (!playerVisible && !searchExpanded && playing.video != null && !inPip)
                        MiniPlayer(playing, controller, expand = goPlayer, toggle = playback::toggle, close = playback::close, navigationPadding = wideNavigation)
                }
            }
        }
    }

    /** Every view model reports through the one snackbar; each message is acknowledged as it is queued. */
    @Composable
    private fun Snackbars(snackbar: SnackbarHostState) {
        val accountUi by account.ui.collectAsStateWithLifecycle()
        val engagementState by engagement.state.collectAsStateWithLifecycle()
        val playing by playback.state.collectAsStateWithLifecycle()
        val castState by casting.state.collectAsStateWithLifecycle()
        ConsumeSnackbar(accountUi.message, snackbar, account::consumeMessage)
        ConsumeSnackbar(engagementState.message, snackbar, engagement::consumeMessage, engagementState.messageAction,
            engagementState.messageId.let { id -> { engagement.messageAction(id) } }, eventKey = engagementState.messageId)
        ConsumeSnackbar(playing.syncMessage, snackbar, playback::consumeMessage)
        ConsumeSnackbar(castState.message, snackbar, casting::consumeMessage)
        ConsumeSnackbar(linkError, snackbar, consume = { linkError = null })
    }

    @Composable
    private fun EngagementHaptics() {
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(engagement) { engagement.acceptedChanges.collect { haptics.performHapticFeedback(HapticFeedbackType.Confirm) } }
    }

    @Composable
    private fun CastFollowsPlayer(controller: Player?, isPlaying: Boolean) {
        // Local playback resuming means the phone is the player again.
        LaunchedEffect(isPlaying) { if (isPlaying) casting.clearPlaying() }
        // While the TV's YouTube app plays, seeking on the phone's (paused) player moves the TV too; direct streams seek through CastPlayer.
        DisposableEffect(controller) {
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) casting.seek(new.positionMs)
                }
            }
            controller?.addListener(listener)
            onDispose { controller?.removeListener(listener) }
        }
    }

    @Composable
    private fun FeedDestination(destination: AccountFeed, nav: NavController, currentRoute: String?, pages: SaveableStateHolder,
        reselections: Map<String, Int>, play: (String) -> Unit) {
        val route = feedRoute(destination)
        val session by account.session.collectAsStateWithLifecycle()
        val profile by account.profile.collectAsStateWithLifecycle()
        val feed by feeds.state.collectAsStateWithLifecycle()
        val snapshots by feeds.cached.collectAsStateWithLifecycle()
        val shown = if (feed.feed == destination) feed else snapshots[destination] ?: FeedState(feed = destination, initialized = false)
        val pageKey = feedPageKey(route, shown.playlist?.id)
        // A feed that is animating away must not act on the one now on top.
        val onTop = { nav.currentDestination?.route == route }
        pages.SaveableStateProvider(pageKey) {
            FeedScreen(shown, session,
                search = { nav.open("search") }, account = { nav.open("account") },
                refresh = { if (onTop()) feeds.refresh() },
                more = { if (onTop() && feeds.state.value.feed == destination) feeds.more() }, play = play,
                playlist = { if (onTop()) feeds.open(it) },
                back = { if (shown.playlist != null) feeds.back() else nav.popBackStack() },
                prefetch = { if (onTop()) videos.prefetch(it) },
                pullRefresh = { if (onTop()) feeds.pullRefresh() }, retry = { if (onTop()) feeds.retry() }, profile = profile,
                reselect = reselections[pageKey] ?: 0, active = currentRoute == route && feed.feed == destination,
                thumbnailPrefetch = { if (onTop()) thumbnails.submit(it) })
        }
    }

    @Composable
    private fun LibraryDestination(nav: NavController, reselect: Int) {
        val session by account.session.collectAsStateWithLifecycle()
        val profile by account.profile.collectAsStateWithLifecycle()
        LibraryScreen(session.signedIn, profile,
            open = { destination -> feeds.select(destination); nav.open(feedRoute(destination)) },
            search = { nav.open("search") }, account = { nav.open("account") }, reselect = reselect)
    }

    @Composable
    private fun AccountDestination(nav: NavController, goSettings: () -> Unit) {
        val session by account.session.collectAsStateWithLifecycle()
        val accountUi by account.ui.collectAsStateWithLifecycle()
        val profile by account.profile.collectAsStateWithLifecycle()
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(account::importFile) }
        val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> account.loginResult(result.resultCode == Activity.RESULT_OK) }
        AccountScreen(session, accountUi, back = { nav.popBackStack() },
            login = { loginLauncher.launch(Intent(this, WebLoginActivity::class.java)) },
            import = { filePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, signOut = account::signOut,
            deviceLogin = account::beginDeviceLogin, cancelDeviceLogin = account::cancelDeviceLogin, profile = profile, settings = goSettings)
    }

    @Composable
    private fun SettingsDestination(nav: NavController, settings: AppearanceSettings) {
        val session by account.session.collectAsStateWithLifecycle()
        val engagementState by engagement.state.collectAsStateWithLifecycle()
        SettingsScreen(settings, engagementState.settings, session.signedIn,
            version = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() },
            back = { nav.popBackStack() }, themeMode = account::themeMode, wallpaper = account::wallpaper,
            history = engagement::history, estimates = engagement::estimates)
    }

    @Composable
    private fun SearchDestination(nav: NavController, play: (String) -> Unit, expandedChange: (Boolean) -> Unit) {
        val state by search.state.collectAsStateWithLifecycle()
        val recent by search.recent.collectAsStateWithLifecycle()
        val onTop = { nav.currentDestination?.route == "search" }
        SearchScreen(state, recent, search::edit, search::search, search::more, search::clearHistory, play,
            back = { nav.popBackStack() }, expandedChange = expandedChange, prefetch = { if (onTop()) videos.prefetch(it) },
            thumbnailPrefetch = { if (onTop()) thumbnails.submit(it) })
    }

    @Composable
    private fun PlayerDestination(nav: NavController, currentRoute: String?, snackbar: SnackbarHostState, landscape: Boolean,
        goSettings: () -> Unit, sheetVisible: (Boolean) -> Unit) {
        val playing by playback.state.collectAsStateWithLifecycle()
        val controller by playback.controller.collectAsStateWithLifecycle()
        val connectionError by playback.connectionError.collectAsStateWithLifecycle()
        val commentState by comments.state.collectAsStateWithLifecycle()
        val engagementState by engagement.state.collectAsStateWithLifecycle()
        val castState by casting.state.collectAsStateWithLifecycle()
        val shownPlayer = remember(controller) { controller?.let { CastPlayer(it, casting::tvPositionMs, casting::seek) } }
        val castActions = remember(casting) {
            CastActions(open = casting::open, denied = casting::denied, scan = casting::scan, dismiss = casting::dismiss,
                stop = {
                    // The phone picks up where the TV was.
                    casting.tvPositionMs()?.let { playback.controller.value?.seekTo(it) }
                    casting.clearPlaying()
                }, play = { tv, direct ->
                    val current = playback.controller.value
                    playback.state.value.video?.url?.let { url -> casting.cast(tv, url, current?.currentPosition ?: 0L, direct) { current?.pause() } }
                })
        }
        val commentActions = remember(comments) { CommentActions(comments::more, comments::refresh, comments::pullRefresh) }
        val engagementActions = remember(engagement) { EngagementActions(engagement::rate, engagement::subscribe, refresh = { engagement.refresh() }) }
        val window = remember(nav, landscape, sheetVisible) {
            PlayerWindow(back = { nav.popBackStack() }, pip = ::enterPip,
                toggleFullscreen = {
                    requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                },
                bounds = { if (it != playerBounds) { playerBounds = Rect(it); updatePip(pipEligible) } }, sheetVisible = sheetVisible)
        }
        val onTop = { nav.currentDestination?.route == "player" }
        PlayerScreen(playing, shownPlayer, connectionError, inPip, commentState, commentActions, engagementState, engagementActions,
            window, settings = goSettings, quality = playback::quality, play = playback::play, prefetch = { if (onTop()) videos.prefetch(it) },
            cast = castState, castActions = castActions, snackbar = snackbar, active = currentRoute == "player",
            thumbnailPrefetch = { if (onTop()) thumbnails.submit(it) })
    }

    private fun NavController.open(route: String) = navigate(route) { launchSingleTop = true }

    override fun onResume() {
        super.onResume()
        refreshResumed = true
        smoothRefresh.active(!inPip)
    }

    override fun onPause() {
        refreshResumed = false
        smoothRefresh.active(false)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        smoothRefresh.active(refreshResumed && !inPip)
    }

    private fun updateFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        applySystemBars()
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = if (fullscreen)
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-enter immersive playback after a dialog or another app returns focus.
        if (hasFocus && fullscreen) applySystemBars()
        if (hasFocus) smoothRefresh.active(refreshResumed && !inPip)
    }

    private fun readIntent(value: Intent) {
        if (value.action == Intent.ACTION_VIEW || value.action == Intent.ACTION_SEND) {
            val url = if (value.action == Intent.ACTION_VIEW) value.dataString?.let { parseYouTubeLink(it) }
                else if (value.type == "text/plain") value.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedYouTubeLink(it) } else null
            if (url == null) linkError = "Not a YouTube video link"
            else { pendingVideo = url; openPlayer = true }
            return
        }
        openPlayer = value.getBooleanExtra("open_player", false)
        // Explicit watch URL extra makes manual device smoke testing repeatable; debug builds only, since the
        // launcher activity is exported and other apps could otherwise start playback (and history reporting).
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val url = value.getStringExtra("video_url")?.takeIf { debuggable }
        if (url != null) {
            val parsed = Uri.parse(url)
            if (parsed.scheme == "https" && parsed.host in setOf("www.youtube.com", "youtube.com", "youtu.be")) {
                pendingVideo = url
                openPlayer = true
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    private fun updatePip(eligible: Boolean) {
        pipEligible = eligible
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Called on every recomposition and layout pass; only cross into system_server when params change.
        val requested = eligible to playerBounds
        if (requested == appliedPip) return
        appliedPip = requested
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        playerBounds?.takeIf { !it.isEmpty }?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
        setPictureInPictureParams(builder.build())
    }
    private fun enterPip() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            catch (_: IllegalStateException) { /* Activity may already be transitioning. */ }
        }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT < 31 && pipEligible) enterPip() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        smoothRefresh.active(refreshResumed && !inPip)
    }
}
