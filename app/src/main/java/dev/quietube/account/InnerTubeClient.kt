package dev.quietube.account

import dev.quietube.data.Outcome
import dev.quietube.data.VideoError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AccountHttp
internal class AccountFailure(val error: VideoError) : IOException()
@Singleton
class InnerTubeClient @Inject constructor(@AccountHttp private val http: OkHttpClient, private val sessions: EncryptedSessionStore, private val tv: dev.quietube.account.oauth.TvOAuthClient) {
    private val mutex = Mutex()
    private var cached: WebConfiguration? = null
    private var configuredAt = 0L
    private var revision = -1L
    private var lastRequest = 0L
    suspend fun validate(cookies: SessionCookies): Outcome<SessionCookies> = withContext(Dispatchers.IO) {
        try {
            val page = loadConfiguration(cookies)
            if (page.first.loggedIn != true || !page.second.usable()) Outcome.Failure(VideoError.AuthExpired) else Outcome.Success(page.second)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(errorFor(e)) }
    }
    suspend fun browse(browseId: String?, continuation: String?): JsonObject {
        sessions.initialize()
        if (sessions.status.value.method == SessionMethod.DeviceCode) return tv.browse(browseId,continuation)
        return request("browse",buildJsonObject {
            if (continuation != null) put("continuation",continuation) else put("browseId",browseId ?: error("Missing browse ID"))
        })
    }
    suspend fun request(endpoint: String,payload: JsonObject): JsonObject {
        sessions.initialize()
        if (sessions.status.value.method == SessionMethod.DeviceCode) return tv.request(endpoint,payload)
        return requestCookies(endpoint,payload)
    }
    private suspend fun requestCookies(endpoint: String,payload: JsonObject): JsonObject = withContext(Dispatchers.IO) { mutex.withLock {
        require(endpoint in setOf("browse","next","player","like/like","like/dislike","like/removelike","like/removedislike","subscription/subscribe","subscription/unsubscribe"))
        val snapshot = sessions.snapshot() ?: throw AccountFailure(VideoError.AuthExpired)
        var cookies = snapshot.cookies
        val currentRevision = snapshot.revision
        if (revision != currentRevision || System.currentTimeMillis() - configuredAt > 6 * 60 * 60 * 1000L || cached == null) {
            val page = loadConfiguration(cookies,currentRevision)
            cached = page.first; cookies = page.second
            configuredAt = System.currentTimeMillis(); revision = currentRevision
        }
        val config = cached!!
        if (config.loggedIn != true) throw AccountFailure(if (config.loggedIn == false) VideoError.AuthExpired else VideoError.ExtractionBroken)
        delay((1_000L - (android.os.SystemClock.elapsedRealtime() - lastRequest)).coerceAtLeast(0))
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != currentRevision) throw AccountFailure(VideoError.AuthExpired)
        val client = JsonObject((config.context.obj("client") ?: JsonObject(emptyMap())).toMutableMap().apply {
            put("clientName", JsonPrimitive("WEB")); put("clientVersion", JsonPrimitive(config.version))
            put("visitorData", JsonPrimitive(config.visitor)); put("userAgent", JsonPrimitive(USER_AGENT))
        })
        val body = buildJsonObject {
            put("context", JsonObject(config.context.toMutableMap().apply { put("client", client) }))
            payload.forEach { (key,value) -> put(key,value) }
        }
        val request = authenticated(Request.Builder().url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false"), cookies, config)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        lastRequest = android.os.SystemClock.elapsedRealtime()
        val result = execute(request,cookies,currentRevision).first
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != currentRevision) throw AccountFailure(VideoError.AuthExpired)
        val json = accountJson.parseToJsonElement(result).jsonObject
        if (json.obj("responseContext")?.obj("mainAppWebResponseContext")?.get("loggedOut") == JsonPrimitive(true))
            throw AccountFailure(VideoError.AuthExpired)
        val code = json.obj("error")?.str("code")?.toIntOrNull()
        if (code != null) throw AccountFailure(if (code == 401 || code == 403) VideoError.AuthExpired else VideoError.ContentUnavailable)
        json
    } }
    private suspend fun loadConfiguration(cookies: SessionCookies, expectedRevision: Long? = null): Pair<WebConfiguration,SessionCookies> {
        val request = Request.Builder().url("$ORIGIN/").header("User-Agent", USER_AGENT)
            .header("Cookie", cookies.header()).header("Accept-Language", "en-US,en;q=0.9").build()
        val page = execute(request,cookies,expectedRevision)
        return WebConfigurationParser.parse(page.first) to page.second
    }
    private fun authenticated(builder: Request.Builder, cookies: SessionCookies, config: WebConfiguration): Request.Builder {
        builder.header("Cookie", cookies.header()).header("User-Agent", USER_AGENT)
            .header("Authorization", YouTubeAuthorization.generate(cookies, System.currentTimeMillis() / 1000, config.userSessionId))
            .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0").header("X-Goog-Visitor-Id", config.visitor)
            .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", config.version)
            .header("X-Youtube-Bootstrap-Logged-In", "true")
        config.delegatedSessionId?.let { builder.header("X-Goog-PageId", it) }
        return builder
    }
    private suspend fun execute(request: Request, cookies: SessionCookies, expectedRevision: Long? = null): Pair<String,SessionCookies> = http.newCall(request).execute().use { response ->
        currentCoroutineContext().ensureActive()
        val updated = if (expectedRevision == null) cookies.updated(response.headers.values("Set-Cookie"))
            else sessions.applyResponseCookies(expectedRevision,response.headers.values("Set-Cookie")) ?: throw AccountFailure(VideoError.AuthExpired)
        when {
            response.code in listOf(301,302,303,307,308,401,403) -> throw AccountFailure(VideoError.AuthExpired)
            response.code == 429 -> throw AccountFailure(VideoError.RateLimited)
            response.code == 404 -> throw AccountFailure(VideoError.ContentUnavailable)
            !response.isSuccessful -> throw AccountFailure(VideoError.Network)
        }
        (response.body?.string() ?: throw AccountFailure(VideoError.ExtractionBroken)) to updated
    }
    fun clear() { http.dispatcher.cancelAll(); cached = null; configuredAt = 0; revision = -1 }
    companion object {
        const val ORIGIN = YouTubeAuthorization.ORIGIN
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
        fun errorFor(e: Exception): VideoError = when(e) {
            is AccountFailure -> e.error
            is IOException -> VideoError.Network
            else -> VideoError.ExtractionBroken
        }
    }
}
