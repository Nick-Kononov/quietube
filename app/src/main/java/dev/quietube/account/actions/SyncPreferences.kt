package dev.quietube.account.actions

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.account.accountJson
import dev.quietube.links.VIDEO_ID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

private val Context.syncPreferences by preferencesDataStore("sync_preferences")
private val HISTORY = booleanPreferencesKey("history")
private val DISLIKE_ESTIMATES = booleanPreferencesKey("dislike_estimates")
data class SyncSettings(val history: Boolean = true, val dislikeEstimates: Boolean = false)
@Singleton
class SyncPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val settings = context.syncPreferences.data.catch { emit(emptyPreferences()) }.map { SyncSettings(it[HISTORY] ?: true, it[DISLIKE_ESTIMATES] ?: false) }
    suspend fun history(enabled: Boolean) { context.syncPreferences.edit { it[HISTORY] = enabled } }
    suspend fun dislikes(enabled: Boolean) { context.syncPreferences.edit { it[DISLIKE_ESTIMATES] = enabled } }
}
/** Dislike counts from Return YouTube Dislike; only the video ID is sent, with no cookies or account data. */
@Singleton
class DislikeEstimates @Inject constructor() {
    private class Cached(val at: Long, val dislikes: String)
    @Serializable private class Votes(val dislikes: Long? = null)
    private val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Cached>()
    suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        require(VIDEO_ID.matches(id))
        cache[id]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return@withContext it.dislikes }
        try {
            http.newCall(Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$id").build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // A few hundred bytes are expected; never buffer an arbitrarily large third-party body.
                val body = response.body
                if (body.contentLength() > MAX_BODY) return@withContext null
                val bytes = body.source().apply { request(MAX_BODY + 1) }.buffer
                if (bytes.size > MAX_BODY) return@withContext null
                val dislikes = accountJson.decodeFromString(Votes.serializer(), bytes.readUtf8()).dislikes?.takeIf { it >= 0 }?.toString() ?: return@withContext null
                if (cache.size > 100) cache.clear()
                cache[id] = Cached(System.currentTimeMillis(), dislikes); dislikes
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    private companion object {
        const val MAX_BODY = 16_384L
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
