package dev.quietube.ui

import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeRecentSearches : RecentSearches {
    override val queries = MutableStateFlow(emptyList<String>())
    override suspend fun add(query: String) { queries.value = recentSearches(queries.value, query) }
    override suspend fun clear() { queries.value = emptyList() }
}
