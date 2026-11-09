package dev.quietube.account.actions

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
private val Context.syncPreferences by preferencesDataStore("sync_preferences")
data class SyncSettings(val history: Boolean = true,val dislikeEstimates: Boolean = false)
@Singleton
class SyncPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val settings = context.syncPreferences.data.catch { emit(emptyPreferences()) }.map { SyncSettings(it[booleanPreferencesKey("history")] ?: true,it[booleanPreferencesKey("dislike_estimates")] ?: false) }
    suspend fun history(enabled: Boolean) { context.syncPreferences.edit { it[booleanPreferencesKey("history")] = enabled } }
    suspend fun dislikes(enabled: Boolean) { context.syncPreferences.edit { it[booleanPreferencesKey("dislike_estimates")] = enabled } }
}
@Singleton
class DislikeEstimates @Inject constructor() {
    private val http = okhttp3.OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).callTimeout(10,java.util.concurrent.TimeUnit.SECONDS).build()
    private val cache = java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Pair<String?,String>>>()
    suspend fun get(id: String): Pair<String?,String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(VIDEO_ID.matches(id))
        cache[id]?.takeIf { System.currentTimeMillis()-it.first < 30*60*1000 }?.let { return@withContext it.second }
        try {
            http.newCall(okhttp3.Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$id").build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // A few hundred bytes are expected; never buffer an arbitrarily large third-party body.
                val body = response.body ?: return@withContext null
                if (body.contentLength() > MAX_BODY) return@withContext null
                val bytes = body.source().apply { request(MAX_BODY + 1) }.buffer
                if (bytes.size > MAX_BODY) return@withContext null
                val json = kotlinx.serialization.json.Json.parseToJsonElement(bytes.readUtf8()) as? kotlinx.serialization.json.JsonObject ?: return@withContext null
                val dislikes = (json["dislikes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 } ?: return@withContext null
                val likes = (json["likes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 }
                val result = likes?.toString() to dislikes.toString()
                if (cache.size > 100) cache.clear()
                cache[id] = System.currentTimeMillis() to result; result
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    private companion object {
        const val MAX_BODY = 16_384L
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
