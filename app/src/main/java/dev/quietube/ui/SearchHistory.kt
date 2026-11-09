package dev.quietube.ui

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private val Context.searchHistory by preferencesDataStore("search_history")
internal fun recentSearches(previous: List<String>, query: String): List<String> {
    val trimmed = query.trim().take(500)
    return (listOf(trimmed) + previous.map { it.trim().take(500) }).filter(String::isNotBlank).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(20)
}
internal fun decodeSearchHistory(value: String?): List<String> = try {
    (Json.parseToJsonElement(value ?: "[]") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .map { it.trim().take(500) }.filter(String::isNotBlank).distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(20)
} catch (_: IllegalArgumentException) { emptyList() }

interface RecentSearches {
    val queries: Flow<List<String>>
    suspend fun add(query: String)
    suspend fun clear()
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class SearchHistoryModule {
    @dagger.Binds abstract fun recentSearches(history: SearchHistory): RecentSearches
}

@Singleton
class SearchHistory @Inject constructor(@ApplicationContext private val context: Context) : RecentSearches {
    private val key = stringPreferencesKey("queries")
    override val queries = context.searchHistory.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { decodeSearchHistory(it[key]) }
    override suspend fun add(query: String) { context.searchHistory.edit { it[key] = JsonArray(recentSearches(decodeSearchHistory(it[key]), query).map(::JsonPrimitive)).toString() } }
    override suspend fun clear() { context.searchHistory.edit { it.clear() } }
}
