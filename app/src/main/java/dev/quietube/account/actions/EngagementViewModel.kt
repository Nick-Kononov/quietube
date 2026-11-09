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
    val busy: Boolean = false,val message: String? = null,val messageId: Long = 0,val messageAction: String? = null,val settings: SyncSettings = SyncSettings())
@HiltViewModel
class EngagementViewModel internal constructor(private val repository: VideoRepository,private val sessionStatus: StateFlow<dev.quietube.account.SessionStatus>,
    playback: PlaybackStateStore,settings: Flow<SyncSettings>,private val loadEstimates: suspend (String) -> Pair<String?,String>?,
    private val setHistory: suspend (Boolean) -> Unit,private val setEstimates: suspend (Boolean) -> Unit) : ViewModel() {
    @Inject constructor(repository: VideoRepository,sessions: EncryptedSessionStore,playback: PlaybackStateStore,preferences: SyncPreferences,estimates: DislikeEstimates) :
        this(repository,sessions.status,playback,preferences.settings,estimates::get,preferences::history,preferences::dislikes)
    private val mutable = MutableStateFlow(EngagementState())
    val state = mutable.asStateFlow()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val acceptedChanges = changes.asSharedFlow()
    fun consumeMessage(message: String) { mutable.update { if (it.message == message) it.copy(message = null) else it } }
    private var action: Job? = null
    private var generation = 0L
    private var loadedRevision = -1L
    private var noticeId = 0L
    private var offered: Pair<Long,EngagementChange>? = null
    private fun current(epoch: Long,revision: Long) = generation == epoch && sessionStatus.value.revision == revision
    private fun notice(text: String,label: String? = null,change: EngagementChange? = null) {
        val id = ++noticeId
        offered = change?.let { id to it }
        mutable.update { it.copy(message = text,messageId = id,messageAction = label) }
    }
    fun messageAction(id: Long) {
        val change = offered?.takeIf { it.first == id }?.second ?: return
        offered = null
        if (change.revision == sessionStatus.value.revision && change.url == mutable.value.url) apply(change)
    }
    init {
        viewModelScope.launch {
            combine(playback.state.map { it.video?.url }.distinctUntilChanged(),sessionStatus,settings) { url,session,prefs -> Triple(url,session,prefs) }
                .distinctUntilChanged().collectLatest { (url,session,prefs) ->
                    generation++; offered = null; action?.cancel()
                    val epoch = generation
                    loadedRevision = session.revision
                    mutable.value = EngagementState(url,session.signedIn,settings = prefs,busy = url != null)
                    if (url == null) return@collectLatest
                    if (session.signedIn) when(val result = repository.engagement(url)) {
                        is Outcome.Success -> if (current(epoch,session.revision)) mutable.update { it.copy(info = result.value,busy = false) }
                        is Outcome.Failure -> if (current(epoch,session.revision)) mutable.update { it.copy(busy = false,message = "Account details unavailable (${result.error}). Retry below.") }
                    } else mutable.update { it.copy(busy = false) }
                    if (prefs.dislikeEstimates) runCatching { ActionParser.videoId(url) }.getOrNull()?.let { loadEstimates(it) }?.let { (_,dislikes) ->
                        if (current(epoch,session.revision)) mutable.update { it.copy(info = it.info.copy(dislikes = dislikes,estimatedDislikes = true)) }
                    }
                }
        }
    }
    fun refresh(clearMessage: Boolean = true) {
        val current = mutable.value
        if (!current.signedIn || !sessionStatus.value.signedIn || loadedRevision != sessionStatus.value.revision || current.busy || current.url == null) return
        val epoch = generation
        val revision = sessionStatus.value.revision
        offered = null
        mutable.update { it.copy(busy = true,message = if (clearMessage) null else it.message,messageAction = null) }
        action = viewModelScope.launch {
            when(val result = repository.engagement(current.url)) {
                is Outcome.Success -> if (current(epoch,revision)) mutable.update { it.copy(busy = false,info = result.value.copy(dislikes = if (current.info.estimatedDislikes) current.info.dislikes else result.value.dislikes,estimatedDislikes = current.info.estimatedDislikes)) }
                is Outcome.Failure -> if (current(epoch,revision)) mutable.update { it.copy(busy = false,message = "Could not refresh account details (${result.error}).") }
            }
        }
    }
    fun rate(rating: Rating) {
        val state = mutable.value
        val url = state.url ?: return
        val target = if (state.info.rating == rating) Rating.None else rating
        apply(EngagementChange.Rate(url,sessionStatus.value.revision,target))
    }
    fun subscribe() {
        val state = mutable.value
        val url = state.url ?: return
        val channel = state.info.channelId ?: return
        val subscribed = state.info.subscribed ?: return
        apply(EngagementChange.Subscribe(url,sessionStatus.value.revision,channel,!subscribed))
    }
    private fun apply(change: EngagementChange) {
        val before = mutable.value
        if (!before.signedIn || !sessionStatus.value.signedIn || before.busy || before.url != change.url || change.revision != sessionStatus.value.revision || change.revision != loadedRevision) return
        val epoch = generation
        changes.tryEmit(Unit)
        offered = null
        mutable.value = before.copy(busy = true,message = null,messageAction = null,info = change.optimistic(before.info))
        action = viewModelScope.launch {
            val result = when (change) {
                is EngagementChange.Rate -> repository.rate(change.url,change.target,before.info.rating ?: Rating.None)
                is EngagementChange.Subscribe -> repository.subscribe(change.channel,change.target)
            }
            if (!current(epoch,change.revision)) return@launch
            when (result) {
                is Outcome.Success -> {
                    mutable.update { it.copy(busy = false) }
                    if (change is EngagementChange.Subscribe && !change.target)
                        notice("Unsubscribed","Undo",change.copy(target = true))
                }
                is Outcome.Failure -> {
                    mutable.update { it.copy(busy = false,info = before.info) }
                    notice(if (change is EngagementChange.Rate) "Couldn't update rating." else "Couldn't update subscription.","Retry",change)
                }
            }
        }
    }
    fun history(enabled: Boolean) { viewModelScope.launch { setHistory(enabled) } }
    fun estimates(enabled: Boolean) { viewModelScope.launch { setEstimates(enabled) } }
}

internal sealed interface EngagementChange {
    val url: String
    val revision: Long
    data class Rate(override val url: String,override val revision: Long,val target: Rating) : EngagementChange
    data class Subscribe(override val url: String,override val revision: Long,val channel: String,val target: Boolean) : EngagementChange
    fun optimistic(info: Engagement): Engagement = when (this) {
        is Rate -> info.copy(rating = target,likes = info.likes?.toLongOrNull()?.let {
            val delta = (if (target == Rating.Like) 1 else 0) - (if (info.rating == Rating.Like) 1 else 0)
            (if (delta > 0 && it == Long.MAX_VALUE) it else (it + delta).coerceAtLeast(0)).toString()
        } ?: info.likes)
        is Subscribe -> info.copy(subscribed = target)
    }
}
