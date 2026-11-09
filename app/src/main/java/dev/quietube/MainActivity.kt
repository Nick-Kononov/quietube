@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.quietube.account.AccountViewModel
import dev.quietube.account.FeedViewModel
import dev.quietube.account.WebLoginActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint
import dev.quietube.playback.PlaybackViewModel
import dev.quietube.ui.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var thumbnails: ThumbnailPrefetcher
    @javax.inject.Inject lateinit var videos: dev.quietube.data.VideoRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (theme or locale change) must not replay the launch request.
        if (savedInstanceState == null) readIntent(intent)
        val search = ViewModelProvider(this)[SearchViewModel::class.java]
        val playback = ViewModelProvider(this)[PlaybackViewModel::class.java]
        val account = ViewModelProvider(this)[AccountViewModel::class.java]
        val engagement = ViewModelProvider(this)[dev.quietube.account.actions.EngagementViewModel::class.java]
        val feeds = ViewModelProvider(this)[FeedViewModel::class.java]
        val comments = ViewModelProvider(this)[CommentsViewModel::class.java]
        val casting = ViewModelProvider(this)[dev.quietube.cast.CastViewModel::class.java]
        var themeReady = false
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (themeReady) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return themeReady
            }
        })
        setContent {
            val appearance by account.appearance.collectAsStateWithLifecycle()
            // Keep the launch window until DataStore has supplied the saved appearance.
            if (appearance == null) return@setContent
            val settings = appearance!!
            val dark = useDarkTheme(settings.mode, androidx.compose.foundation.isSystemInDarkTheme())
            QuietubeTheme(settings) {
                val background = androidx.compose.material3.MaterialTheme.colorScheme.background
                SideEffect {
                    themeReady = true
                    window.setBackgroundDrawable(background.toArgb().toDrawable())
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(settings.mode) {
                    if (Build.VERSION.SDK_INT >= 31) getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(when (settings.mode) {
                        ThemeMode.System -> android.app.UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.Light -> android.app.UiModeManager.MODE_NIGHT_NO
                        ThemeMode.Dark -> android.app.UiModeManager.MODE_NIGHT_YES
                    })
                }
                val nav = rememberNavController()
                val feedPages = rememberSaveableStateHolder()
                val session by account.session.collectAsStateWithLifecycle()
                val accountUi by account.ui.collectAsStateWithLifecycle()
                val profile by account.profile.collectAsStateWithLifecycle()
                val feed by feeds.state.collectAsStateWithLifecycle()
                val feedSnapshots by feeds.cached.collectAsStateWithLifecycle()
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(account::importFile) }
                val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> account.loginResult(result.resultCode == android.app.Activity.RESULT_OK) }
                val playing by playback.state.collectAsStateWithLifecycle()
                val commentState by comments.state.collectAsStateWithLifecycle()
                val engagementState by engagement.state.collectAsStateWithLifecycle()
                val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                LaunchedEffect(engagement) {
                    engagement.acceptedChanges.collect { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }
                }
                val controller by playback.controller.collectAsStateWithLifecycle()
                val connectionError by playback.connectionError.collectAsStateWithLifecycle()
                val castState by casting.state.collectAsStateWithLifecycle()
                var searchExpanded by remember { mutableStateOf(false) }
                var playerSheetVisible by remember { mutableStateOf(false) }
                val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
                ConsumeSnackbar(accountUi.message, snackbar, account::consumeMessage)
                ConsumeSnackbar(engagementState.message, snackbar, engagement::consumeMessage, engagementState.messageAction,
                    engagementState.messageId.let { id -> { engagement.messageAction(id) } },eventKey = engagementState.messageId)
                ConsumeSnackbar(playing.syncMessage, snackbar, playback::consumeMessage)
                ConsumeSnackbar(castState.message, snackbar, casting::consumeMessage)
                ConsumeSnackbar(linkError, snackbar, consume = { linkError = null })
                // Local playback resuming means the phone is the player again.
                LaunchedEffect(playing.isPlaying) { if (playing.isPlaying) casting.clearPlaying() }
                // While the TV's YouTube app plays, seeking on the phone's (paused) player moves the TV too; direct streams seek through CastPlayer.
                DisposableEffect(controller) {
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onPositionDiscontinuity(old: androidx.media3.common.Player.PositionInfo,
                            new: androidx.media3.common.Player.PositionInfo, reason: Int) {
                            if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) casting.seek(new.positionMs)
                        }
                    }
                    controller?.addListener(listener)
                    onDispose { controller?.removeListener(listener) }
                }
                val shownPlayer = remember(controller) { controller?.let { dev.quietube.cast.CastPlayer(it, casting::tvPositionMs, casting::seek) } }
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
                val entry by nav.currentBackStackEntryAsState()
                val playerVisible = entry?.destination?.route == "player"
                var selectedTopRoute by rememberSaveable { mutableStateOf(TopDestination.Home.route) }
                val selectedTop = TopDestination.entries.firstOrNull { it.route == selectedTopRoute } ?: TopDestination.Home
                var reselections by rememberSaveable { mutableStateOf(emptyMap<String, Int>()) }
                val currentRoute = entry?.destination?.route
                LaunchedEffect(currentRoute, feed.initialized, feed.feed, session.ready) {
                    routeTop(currentRoute)?.let { selectedTopRoute = it.route }
                    if (feed.initialized && session.ready) routeFeed(currentRoute)?.takeIf { it != feed.feed }?.let(feeds::select)
                }
                val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
                val wideNavigation = useNavigationRail(with(androidx.compose.ui.platform.LocalDensity.current) { windowWidth.toDp().value.toInt() })
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
                val goSettings: () -> Unit = { nav.navigate("settings") { launchSingleTop = true } }
                val goPlayer: () -> Unit = { nav.navigate("player") { launchSingleTop = true } }
                val play: (String) -> Unit = { url -> playback.play(url); goPlayer() }
                LaunchedEffect(openPlayer, pendingVideo) {
                    pendingVideo?.let { playback.play(it); pendingVideo = null }
                    if (openPlayer) { goPlayer(); openPlayer = false }
                }
                SideEffect { updatePip(playerVisible && playing.playWhenReady && !playing.resolving && playing.error == null) }
                AppNavigation(selectedTop, playerVisible || (currentRoute == "search" && searchExpanded), wideNavigation, selectTop) {
                androidx.compose.material3.Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = {
                    if (!inPip && !playerSheetVisible) androidx.compose.material3.SnackbarHost(snackbar, Modifier.navigationBarsPadding())
                }) { outerInsets ->
                Column(Modifier.fillMaxSize().padding(outerInsets)) {
                    val preview = rememberBackPreview(nav)
                    NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f),
                        enterTransition = ScreenMotion.enter, exitTransition = ScreenMotion.exit,
                        popEnterTransition = ScreenMotion.popEnter, popExitTransition = ScreenMotion.popExit,
                        predictivePopEnterTransition = ScreenMotion.predictivePopEnter, predictivePopExitTransition = ScreenMotion.predictivePopExit) {
                        dev.quietube.data.AccountFeed.entries.forEach { destination ->
                            val route = feedRoute(destination)
                            composable(route) { entry ->
                                ScreenCard(entry, preview) {
                                val shown = if (feed.feed == destination) feed else feedSnapshots[destination] ?: dev.quietube.account.FeedState(feed = destination, initialized = false)
                                feedPages.SaveableStateProvider(feedPageKey(route, shown.playlist?.id)) {
                                FeedScreen(shown, session,
                                    search = { nav.navigate("search") { launchSingleTop = true } },
                                    account = { nav.navigate("account") { launchSingleTop = true } },
                                    refresh = { if (nav.currentDestination?.route == route) feeds.refresh() },
                                    more = { if (nav.currentDestination?.route == route && feeds.state.value.feed == destination) feeds.more() }, play = play,
                                    playlist = { if (nav.currentDestination?.route == route) feeds.open(it) },
                                    back = { if (shown.playlist != null) feeds.back() else nav.popBackStack() }, prefetch = { if (nav.currentDestination?.route == route) videos.prefetch(it) },
                                    pullRefresh = { if (nav.currentDestination?.route == route) feeds.pullRefresh() },
                                    retry = { if (nav.currentDestination?.route == route) feeds.retry() }, profile = profile,
                                    reselect = reselections[feedPageKey(route, shown.playlist?.id)] ?: 0, active = currentRoute == route && feed.feed == destination,
                                    thumbnailPrefetch = { if (nav.currentDestination?.route == route) thumbnails.submit(it) })
                                }
                            } }
                        }
                        composable("library") { entry -> ScreenCard(entry, preview) {
                            feedPages.SaveableStateProvider("library") {
                            LibraryScreen(session.signedIn, profile,
                                open = { destination -> feeds.select(destination); nav.navigate(feedRoute(destination)) { launchSingleTop = true } },
                                search = { nav.navigate("search") { launchSingleTop = true } },
                                account = { nav.navigate("account") { launchSingleTop = true } }, reselect = reselections["library"] ?: 0)
                            }
                        } }
                        composable("account") { entry -> ScreenCard(entry, preview) {
                            AccountScreen(session, accountUi, back = { nav.popBackStack() },
                                login = { loginLauncher.launch(Intent(this@MainActivity, WebLoginActivity::class.java)) },
                                import = { filePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, signOut = account::signOut,deviceLogin = account::beginDeviceLogin,cancelDeviceLogin = account::cancelDeviceLogin,profile = profile,
                                settings = goSettings)
                        } }
                        composable("settings") { entry -> ScreenCard(entry, preview) {
                            SettingsScreen(settings, engagementState.settings, session.signedIn,
                                version = remember { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() },
                                back = { nav.popBackStack() }, themeMode = account::themeMode, wallpaper = account::wallpaper,
                                history = engagement::history, estimates = engagement::estimates)
                        } }
                        composable("search") { entry -> ScreenCard(entry, preview) {
                            val state by search.state.collectAsStateWithLifecycle()
                            val recent by search.recent.collectAsStateWithLifecycle()
                            SearchScreen(state, recent, search::edit, search::search, search::more, search::clearHistory, play,
                                back = { nav.popBackStack() }, expandedChange = { searchExpanded = it }, prefetch = { if (nav.currentDestination?.route == "search") videos.prefetch(it) },
                                thumbnailPrefetch = { if (nav.currentDestination?.route == "search") thumbnails.submit(it) })
                        } }
                        composable("player") { entry -> ScreenCard(entry, preview) {
                            PlayerScreen(playing, shownPlayer, connectionError, inPip,
                                comments = commentState,moreComments = comments::more,refreshComments = comments::refresh,pullRefreshComments = comments::pullRefresh,
                                engagement = engagementState,rate = engagement::rate,subscribe = engagement::subscribe,refreshAccount = { engagement.refresh() }, settings = goSettings,
                                back = { nav.popBackStack() }, pip = { enterPip() },
                                toggleFullscreen = {
                                    requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                },
                                bounds = { if (it != playerBounds) { playerBounds = Rect(it); updatePip(pipEligible) } },
                                quality = playback::quality, play = playback::play, prefetch = { if (nav.currentDestination?.route == "player") videos.prefetch(it) },
                                active = currentRoute == "player",thumbnailPrefetch = { if (nav.currentDestination?.route == "player") thumbnails.submit(it) },
                                cast = castState, castActions = castActions, snackbar = snackbar, sheetVisible = { playerSheetVisible = it })
                        } }
                    }
                    if (!playerVisible && !searchExpanded && playing.video != null && !inPip)
                        MiniPlayer(playing, controller, expand = goPlayer, toggle = playback::toggle, close = playback::close, navigationPadding = wideNavigation)
                }
                }
                }
            }
        }
    }
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
            val url = if (value.action == Intent.ACTION_VIEW) value.dataString?.let { dev.quietube.links.parseYouTubeLink(it) }
                else if (value.type == "text/plain") value.getStringExtra(Intent.EXTRA_TEXT)?.let { dev.quietube.links.sharedYouTubeLink(it) } else null
            if (url == null) linkError = "Not a YouTube video link"
            else { pendingVideo = url; openPlayer = true }
            return
        }
        openPlayer = value.getBooleanExtra("open_player", false)
        // Explicit watch URL extra makes manual device smoke testing repeatable; debug builds only, since the
        // launcher activity is exported and other apps could otherwise start playback (and history reporting).
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val url = value.getStringExtra("video_url")?.takeIf { debuggable }
        if (url != null) {
            val parsed = android.net.Uri.parse(url)
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
