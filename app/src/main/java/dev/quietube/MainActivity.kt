@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint
import dev.quietube.playback.PlaybackViewModel
import dev.quietube.ui.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private var openPlayer by mutableStateOf(false)
    private var pendingVideo by mutableStateOf<String?>(null)
    private var pipEligible = false
    private var playerBounds: Rect? = null
    private var appliedPip: Pair<Boolean, Rect?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        val search = ViewModelProvider(this)[SearchViewModel::class.java]
        val playback = ViewModelProvider(this)[PlaybackViewModel::class.java]
        val account = ViewModelProvider(this)[AccountViewModel::class.java]
        val engagement = ViewModelProvider(this)[dev.quietube.account.actions.EngagementViewModel::class.java]
        val feeds = ViewModelProvider(this)[FeedViewModel::class.java]
        val comments = ViewModelProvider(this)[CommentsViewModel::class.java]
        setContent {
            QuietubeTheme {
                val nav = rememberNavController()
                val session by account.session.collectAsStateWithLifecycle()
                val accountUi by account.ui.collectAsStateWithLifecycle()
                val feed by feeds.state.collectAsStateWithLifecycle()
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(account::importFile) }
                val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> account.loginResult(result.resultCode == android.app.Activity.RESULT_OK) }
                val playing by playback.state.collectAsStateWithLifecycle()
                val commentState by comments.state.collectAsStateWithLifecycle()
                val engagementState by engagement.state.collectAsStateWithLifecycle()
                val controller by playback.controller.collectAsStateWithLifecycle()
                val connectionError by playback.connectionError.collectAsStateWithLifecycle()
                val entry by nav.currentBackStackEntryAsState()
                val playerVisible = entry?.destination?.route == "player"
                val goPlayer: () -> Unit = { nav.navigate("player") { launchSingleTop = true } }
                val play: (String) -> Unit = { url -> playback.play(url); goPlayer() }
                LaunchedEffect(openPlayer, pendingVideo) {
                    pendingVideo?.let { playback.play(it); pendingVideo = null }
                    if (openPlayer) { goPlayer(); openPlayer = false }
                }
                SideEffect { updatePip(playerVisible && playing.playWhenReady && !playing.resolving && playing.error == null) }
                Column(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                        composable("home") {
                            FeedScreen(feed, session, feeds::select,
                                search = { nav.navigate("search") { launchSingleTop = true } },
                                account = { nav.navigate("account") { launchSingleTop = true } },
                                refresh = feeds::refresh, more = feeds::more, play = play, playlist = feeds::open, back = feeds::back)
                        }
                        composable("account") {
                            AccountScreen(session, accountUi, back = { nav.popBackStack() },
                                login = { loginLauncher.launch(Intent(this@MainActivity, WebLoginActivity::class.java)) },
                                import = { filePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, signOut = account::signOut,deviceLogin = account::beginDeviceLogin,cancelDeviceLogin = account::cancelDeviceLogin)
                        }
                        composable("search") {
                            val state by search.state.collectAsStateWithLifecycle()
                            SearchScreen(state, search::edit, search::search, search::more, play, session.signedIn,
                                account = { nav.navigate("account") { launchSingleTop = true } },
                                home = { nav.popBackStack("home", false); feeds.refresh() })
                        }
                        composable("player") {
                            PlayerScreen(playing, controller, connectionError, inPip,
                                comments = commentState,moreComments = comments::more,refreshComments = comments::refresh,
                                engagement = engagementState,rate = engagement::rate,subscribe = engagement::subscribe,refreshAccount = engagement::refresh,history = engagement::history,estimates = engagement::estimates,
                                back = { nav.popBackStack() }, pip = { enterPip() },
                                bounds = { if (it != playerBounds) { playerBounds = Rect(it); updatePip(pipEligible) } },
                                quality = playback::quality, play = playback::play)
                        }
                    }
                    if (!playerVisible && playing.video != null && !inPip) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
                            TextButton(onClick = goPlayer, modifier = Modifier.weight(1f)) {
                                Text(playing.video?.title.orEmpty(), maxLines = 1)
                            }
                            TextButton(onClick = playback::toggle) { Text(if (playing.isPlaying) "Pause" else "Play") }
                        }
                    }
                }
            }
        }
    }
    private fun readIntent(value: Intent) {
        openPlayer = value.getBooleanExtra("open_player", false)
        // Explicit watch URL extra also makes manual device smoke testing repeatable.
        val url = value.getStringExtra("video_url")
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
    }
}
