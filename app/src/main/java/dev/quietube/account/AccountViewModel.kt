package dev.quietube.account

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.data.DefaultVideoRepository
import dev.quietube.data.Outcome
import dev.quietube.data.Profile
import dev.quietube.playback.ResumeDao
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn

data class AccountUiState(val busy: Boolean = false,val message: String? = null,
    val deviceLogin: dev.quietube.account.oauth.DeviceLogin? = null, val signingIn: Boolean = false)
@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: EncryptedSessionStore,
    private val tv: dev.quietube.account.oauth.TvOAuthClient,
    private val client: InnerTubeClient,
    private val source: InnerTubeAccountSource,
    private val repository: DefaultVideoRepository,
    private val positions: ResumeDao,
    private val searchHistory: dev.quietube.ui.SearchHistory,
    private val appearancePreferences: dev.quietube.ui.AppearancePreferences,
    private val actions: dev.quietube.account.actions.YouTubeActions,
) : ViewModel() {
    val appearance = appearancePreferences.settings.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    fun themeMode(mode: dev.quietube.ui.ThemeMode) { viewModelScope.launch { appearancePreferences.mode(mode) } }
    fun wallpaper(enabled: Boolean) { viewModelScope.launch { appearancePreferences.wallpaper(enabled) } }
    val session = sessions.status
    private val mutable = MutableStateFlow(AccountUiState())
    val ui = mutable.asStateFlow()
    private val profileState = MutableStateFlow<Profile?>(null)
    /** Name and avatar of the signed-in account; null while signed out or not yet loaded. */
    val profile = profileState.asStateFlow()
    fun consumeMessage(message: String) { mutable.update { if (it.message == message) it.copy(message = null) else it } }
    private var operation: Job? = null
    private var deviceJob: Job? = null
    init {
        viewModelScope.launch { sessions.initialize() }
        viewModelScope.launch {
            sessions.status.filter { it.ready }.distinctUntilChanged { a, b -> a.revision == b.revision && a.signedIn == b.signedIn }.collectLatest {
                profileState.value = null
                if (it.signedIn) (actions.profile() as? Outcome.Success)?.let { result -> profileState.value = result.value }
            }
        }
    }
    fun beginDeviceLogin() {
        if (mutable.value.busy) return
        operation?.cancel()
        operation = viewModelScope.launch {
            mutable.value = AccountUiState(true,"Requesting a device code…",signingIn = true)
            var stage = "requesting a device code"
            try {
                sessions.initialize()
                val expectedRevision = sessions.status.value.revision
                val login = tv.start()
                mutable.value = AccountUiState(true,"Approve the code in Chrome, then return here. Google may call this YouTube on TV.",login,signingIn = true)
                stage = "waiting for approval"
                var interval = login.intervalSeconds
                var failures = 0
                while (isActive && System.currentTimeMillis() < login.expiresAt) {
                    delay(interval*1000)
                    val response = try { tv.poll(login) } catch (e: java.io.IOException) {
                        if (e is AccountFailure && e.error != dev.quietube.data.VideoError.Network) throw e
                        failures++
                        if (failures >= 3) throw e
                        delay(15_000); continue
                    }
                    failures = 0
                    when (response) {
                        dev.quietube.account.oauth.DevicePoll.Pending -> Unit
                        dev.quietube.account.oauth.DevicePoll.SlowDown -> interval += 5
                        dev.quietube.account.oauth.DevicePoll.Denied -> { mutable.value = AccountUiState(message = "Authorization was declined. No account session was saved."); return@launch }
                        dev.quietube.account.oauth.DevicePoll.Expired -> { mutable.value = AccountUiState(message = "The device code expired. Request a new code."); return@launch }
                        is dev.quietube.account.oauth.DevicePoll.Granted -> {
                            mutable.value = AccountUiState(true,"Google approved. Testing authenticated Home…",signingIn = true)
                            stage = "checking authenticated Home"
                            val probe = tv.probe(response.session)
                            val home = withContext(Dispatchers.Default) { AccountPageParser.parse(probe) }
                            if (home.videos.isEmpty()) {
                                mutable.value = AccountUiState(message = "Google approved sign-in, but the prototype could not read a nonempty Home feed. No replacement session was saved.")
                                return@launch
                            }
                            stage = "checking token refresh"
                            mutable.value = AccountUiState(true,"Home is readable. Checking automatic token refresh…",signingIn = true)
                            delay(1000)
                            val refreshed = tv.refreshCandidate(response.session)
                            delay(1000)
                            val refreshedProbe = tv.probe(refreshed)
                            if (withContext(Dispatchers.Default) { AccountPageParser.parse(refreshedProbe) }.videos.isEmpty()) {
                                mutable.value = AccountUiState(message = "The refreshed token could not read Home. No replacement session was saved.")
                                return@launch
                            }
                            currentCoroutineContext().ensureActive()
                            if (sessions.status.value.revision == expectedRevision) { client.clear(); source.clear() }
                            val saved = sessions.saveDeviceIfRevision(refreshed,expectedRevision)
                            mutable.value = AccountUiState(message = if (saved) "Device sign-in, Home and token-refresh checks succeeded. Open Home to check your recommendations."
                                else "The session changed during sign-in. Please try again.")
                            return@launch
                        }
                    }
                }
                mutable.value = AccountUiState(message = "The device code expired. Request a new code.")
            } catch (e: CancellationException) { throw e }
            catch (e: AccountFailure) { mutable.value = AccountUiState(message = "Device sign-in failed while $stage (${e.error}). Cookie import remains available.") }
            catch (_: Exception) { mutable.value = AccountUiState(message = "Device sign-in did not complete. YouTube may have changed or rejected the TV client flow. Cookie import remains available.") }
        }
        deviceJob = operation
    }
    fun cancelDeviceLogin() {
        // Only device sign-in is cancellable: stopping sign-out halfway would leave caches and progress behind.
        val job = deviceJob?.takeIf { it.isActive } ?: return
        job.cancel(); tv.cancelNetwork()
        mutable.value = AccountUiState(message = "Device sign-in cancelled. No replacement session was saved.")
    }
    fun importFile(uri: Uri) {
        if (mutable.value.busy) return
        operation = viewModelScope.launch {
            mutable.value = AccountUiState(true)
            try {
                val expectedRevision = sessions.status.value.revision
                val cookies = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 512_000) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 512_001 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        buffer.fill(0)
                        val bytes = output.toByteArray()
                        require(bytes.size <= 512_000) { "Cookie file is too large." }
                        try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
                    } ?: throw IllegalArgumentException("Could not open the selected file.")
                    SessionCookies.fromNetscape(text)
                }
                when (val validation = client.validate(cookies)) {
                    is Outcome.Success -> {
                        if (sessions.status.value.revision == expectedRevision) { client.clear(); source.clear() }
                        mutable.value = if (sessions.saveIfRevision(validation.value,expectedRevision))
                            AccountUiState(message = "Signed in. Your feeds are ready.")
                        else AccountUiState(message = "Session changed during import. Please try again.")
                    }
                    is Outcome.Failure -> mutable.value = AccountUiState(message = when(validation.error) {
                        dev.quietube.data.VideoError.AuthExpired -> "This session is expired or not signed in. Export fresh cookies from youtube.com."
                        dev.quietube.data.VideoError.Network -> "Could not reach YouTube. Check your connection and try again."
                        else -> "YouTube could not verify this session. Try a fresh cookies.txt export."
                    })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutable.value = AccountUiState(message = e.message ?: "Invalid cookie file.") }
            catch (_: Exception) { mutable.value = AccountUiState(message = "Could not securely import this session. Please try again.") }
        }
    }
    fun loginResult(success: Boolean) {
        mutable.value = AccountUiState(message = if (success) "Signed in. Your feeds are ready." else "Sign-in was cancelled. If Google blocks the browser, import cookies.txt instead.")
    }
    fun signOut() {
        operation?.cancel()
        operation = viewModelScope.launch {
            mutable.value = AccountUiState(true)
            // Cleanup must not stop halfway, even if the screen goes away mid sign-out.
            try { withContext(NonCancellable) {
                tv.cancelNetwork()
                sessions.clear()
                client.clear(); source.clear(); repository.clearLocalFeed()
                positions.clear()
                withContext(Dispatchers.IO) {
                    coil3.SingletonImageLoader.get(context).memoryCache?.clear()
                    coil3.SingletonImageLoader.get(context).diskCache?.clear()
                }
                clearLoginData(context)
                searchHistory.clear()
                mutable.value = AccountUiState(message = "Signed out. Session, personal feeds and saved progress cleared.")
            } } catch (_: Exception) { mutable.value = AccountUiState(message = "Sign-out cleanup could not finish. Try again.") }
        }
    }
}
