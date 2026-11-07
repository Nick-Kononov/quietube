package dev.quietube.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietube.data.Continuation
import dev.quietube.data.Outcome
import dev.quietube.data.Video
import dev.quietube.data.VideoError
import dev.quietube.data.VideoRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val draft: String = "",
    val query: String = "",
    val videos: List<Video> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
    val error: VideoError? = null,
    val suggestion: String? = null,
    val partial: Boolean = false,
    val hasMore: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: VideoRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchState(draft = savedState["query"] ?: ""))
    val state = mutableState.asStateFlow()
    private var cursor: Continuation? = null
    private var request: Job? = null
    init { if (mutableState.value.draft.isNotBlank()) search() }

    fun edit(value: String) { mutableState.update { it.copy(draft = value) } }
    fun search(value: String = mutableState.value.draft) {
        val query = value.trim().take(500)
        if (query.isEmpty()) return
        request?.cancel()
        cursor = null
        savedState["query"] = query
        mutableState.value = SearchState(draft = query, query = query, loading = true, searched = true)
        request = viewModelScope.launch {
            when (val result = repository.search(query)) {
                is Outcome.Success -> {
                    cursor = result.value.continuation
                    mutableState.update { it.copy(videos = result.value.videos.distinctBy(Video::url), loading = false,
                        suggestion = result.value.suggestion, partial = result.value.skippedItems, hasMore = cursor != null) }
                }
                is Outcome.Failure -> mutableState.update { it.copy(loading = false, error = result.error) }
            }
        }
    }
    fun more() {
        val current = mutableState.value
        val next = cursor ?: return
        if (current.loading || current.loadingMore) return
        mutableState.update { it.copy(loadingMore = true, error = null) }
        request = viewModelScope.launch {
            when (val result = repository.search(current.query, next)) {
                is Outcome.Success -> {
                    cursor = result.value.continuation
                    mutableState.update { it.copy(videos = (it.videos + result.value.videos).distinctBy(Video::url),
                        loadingMore = false, hasMore = cursor != null, partial = it.partial || result.value.skippedItems) }
                }
                is Outcome.Failure -> mutableState.update { it.copy(loadingMore = false, error = result.error) }
            }
        }
    }
}
