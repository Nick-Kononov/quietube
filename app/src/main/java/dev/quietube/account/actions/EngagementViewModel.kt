package dev.quietube.account.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietube.account.EncryptedSessionStore
import dev.quietube.data.*
import dev.quietube.playback.PlaybackStateStore
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EngagementState(val url: String? = null,val signedIn: Boolean = false,val info: Engagement = Engagement(),
    val busy: Boolean = false,val message: String? = null,val settings: SyncSettings = SyncSettings())
@HiltViewModel
class EngagementViewModel @Inject constructor(private val repository: VideoRepository,private val sessions: EncryptedSessionStore,
    playback: PlaybackStateStore,private val preferences: SyncPreferences,private val estimates: DislikeEstimates) : ViewModel() {
    private val mutable = MutableStateFlow(EngagementState())
    val state = mutable.asStateFlow()
    private var action: Job? = null
    init {
        viewModelScope.launch {
            combine(playback.state.map { it.video?.url }.distinctUntilChanged(),sessions.status,preferences.settings) { url,session,prefs -> Triple(url,session,prefs) }
                .distinctUntilChanged().collectLatest { (url,session,prefs) ->
                    action?.cancel()
                    mutable.value = EngagementState(url,session.signedIn,settings = prefs,busy = url != null)
                    if (url == null) return@collectLatest
                    if (session.signedIn) when(val result = repository.engagement(url)) {
                        is Outcome.Success -> mutable.update { it.copy(info = result.value,busy = false) }
                        is Outcome.Failure -> mutable.update { it.copy(busy = false,message = "Account details unavailable (${result.error}). Retry below.") }
                    } else mutable.update { it.copy(busy = false) }
                    if (prefs.dislikeEstimates) runCatching { ActionParser.videoId(url) }.getOrNull()?.let { estimates.get(it) }?.let { (_,dislikes) ->
                        mutable.update { it.copy(info = it.info.copy(dislikes = dislikes,estimatedDislikes = true)) }
                    }
                }
        }
    }
    fun refresh() {
        val current = mutable.value
        if (!current.signedIn || current.busy || current.url == null) return
        action = viewModelScope.launch {
            mutable.update { it.copy(busy = true,message = null) }
            when(val result = repository.engagement(current.url)) {
                is Outcome.Success -> mutable.update { it.copy(busy = false,info = result.value.copy(dislikes = if (current.info.estimatedDislikes) current.info.dislikes else result.value.dislikes,estimatedDislikes = current.info.estimatedDislikes)) }
                is Outcome.Failure -> mutable.update { it.copy(busy = false,message = "Could not refresh account details (${result.error}).") }
            }
        }
    }
    fun rate(rating: Rating) {
        val current = mutable.value
        if (!current.signedIn || current.busy || current.url == null) return
        val target = if (current.info.rating == rating) Rating.None else rating
        action = viewModelScope.launch {
            mutable.update { it.copy(busy = true,message = null) }
            when(val result = repository.rate(current.url,target,current.info.rating ?: Rating.None)) {
                is Outcome.Success -> { mutable.update { it.copy(busy = false,info = it.info.copy(rating = target),message = "Rating saved to YouTube.") }; refresh() }
                is Outcome.Failure -> mutable.update { it.copy(busy = false,message = "Rating was not confirmed (${result.error}). Refresh before trying again.") }
            }
        }
    }
    fun subscribe() {
        val current = mutable.value
        val channel = current.info.channelId ?: return
        val subscribed = current.info.subscribed ?: return
        if (!current.signedIn || current.busy) return
        action = viewModelScope.launch {
            mutable.update { it.copy(busy = true,message = null) }
            when(val result = repository.subscribe(channel,!subscribed)) {
                is Outcome.Success -> mutable.update { it.copy(busy = false,info = it.info.copy(subscribed = !subscribed),message = if (subscribed) "Unsubscribed on YouTube." else "Subscribed on YouTube.") }
                is Outcome.Failure -> mutable.update { it.copy(busy = false,message = "Subscription change was not confirmed (${result.error}). Refresh before trying again.") }
            }
        }
    }
    fun history(enabled: Boolean) { viewModelScope.launch { preferences.history(enabled) } }
    fun estimates(enabled: Boolean) { viewModelScope.launch { preferences.dislikes(enabled) } }
}
