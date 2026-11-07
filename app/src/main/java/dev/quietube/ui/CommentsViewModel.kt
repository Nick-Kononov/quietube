package dev.quietube.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietube.data.*
import dev.quietube.playback.PlaybackStateStore
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CommentsState(val url: String? = null,val comments: List<Comment> = emptyList(),val loading: Boolean = false,
    val loadingMore: Boolean = false,val hasMore: Boolean = false,val disabled: Boolean = false,val total: Int? = null,
    val error: VideoError? = null,val partial: Boolean = false)
@HiltViewModel
class CommentsViewModel @Inject constructor(private val repository: VideoRepository,playback: PlaybackStateStore) : ViewModel() {
    private val mutable = MutableStateFlow(CommentsState())
    val state = mutable.asStateFlow()
    private var cursor: Continuation? = null
    private var request: Job? = null
    init {
        viewModelScope.launch {
            var requested = false
            playback.state.map { it.video?.url to it.resolving }.distinctUntilChanged().collect { (url,resolving) ->
                if (url != mutable.value.url) {
                    request?.cancel(); cursor = null; requested = false
                    mutable.value = CommentsState(url = url,loading = url != null)
                }
                // Wait for stream resolution so comment extraction doesn't compete with playback startup.
                if (url != null && !resolving && !requested) { requested = true; refresh() }
            }
        }
    }
    fun refresh() {
        val url = mutable.value.url ?: return
        request?.cancel(); cursor = null
        mutable.value = CommentsState(url = url,loading = true)
        load(url,null)
    }
    fun more() {
        val state = mutable.value
        val next = cursor ?: return
        if (state.loading || state.loadingMore) return
        mutable.update { it.copy(loadingMore = true,error = null) }
        load(state.url ?: return,next)
    }
    private fun load(url: String,next: Continuation?) {
        request = viewModelScope.launch {
            val result = repository.comments(url,next)
            currentCoroutineContext().ensureActive()
            if (mutable.value.url != url) return@launch
            when(result) {
                is Outcome.Success -> {
                    cursor = result.value.continuation
                    mutable.update { it.copy(comments = ((if (next == null) emptyList() else it.comments)+result.value.comments).distinctBy(Comment::id),
                        loading = false,loadingMore = false,disabled = result.value.disabled,total = result.value.total ?: it.total,
                        hasMore = cursor != null,partial = it.partial || result.value.skippedItems,error = null) }
                }
                is Outcome.Failure -> mutable.update { it.copy(loading = false,loadingMore = false,error = result.error) }
            }
        }
    }
}
