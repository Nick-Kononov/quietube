package dev.quietube.account.oauth

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.quietube.account.EncryptedSessionStore
import dev.quietube.account.AccountFailure
import dev.quietube.account.InnerTubeClient
import dev.quietube.account.await
import dev.quietube.data.VideoError
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TvHttp
@Module @InstallIn(SingletonComponent::class)
object TvNetworkModule {
    @Provides @Singleton @TvHttp fun client(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request(); val u = request.url
            if (!allowedTvDestination(u.scheme,u.host,u.encodedPath) || request.header("Cookie") != null ||
                (request.header("Authorization") != null && !u.encodedPath.startsWith("/youtubei/v1/") && u.encodedPath !in STATS_PATHS))
                throw IOException("TV account destination blocked.")
            chain.proceed(request)
        }.build()
}
internal fun allowedTvDestination(scheme: String,host: String,path: String): Boolean = scheme == "https" && host == "www.youtube.com" &&
    (path == "/tv" || path.startsWith("/s/") || path in setOf("/o/oauth2/device/code","/o/oauth2/token") || path.startsWith("/youtubei/v1/") || path in STATS_PATHS)
internal val STATS_PATHS = setOf("/api/stats/playback","/api/stats/watchtime")

@Singleton
class TvOAuthClient @Inject constructor(@TvHttp private val http: OkHttpClient,private val sessions: EncryptedSessionStore) {
    private val browseMutex = Mutex()
    private var lastBrowse = 0L
    private var contextAt = 0L
    private var contextVersion: String? = null
    private var contextVisitor: String? = null
    suspend fun start(): DeviceLogin = withContext(Dispatchers.IO) {
        val html = get("$ORIGIN/tv")
        val config = TvConfigurationParser.parse(html,get(TvConfigurationParser.scriptPath(html)))
        val response = post("/o/oauth2/device/code",buildJsonObject {
            put("client_id",config.clientId); put("device_id",UUID.randomUUID().toString()); put("model_name","ytlr::")
            put("scope","http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content")
        })
        DeviceCodeParser.login(response,config,System.currentTimeMillis())
    }
    suspend fun poll(login: DeviceLogin): DevicePoll = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() >= login.expiresAt) return@withContext DevicePoll.Expired
        val result = post("/o/oauth2/token",buildJsonObject {
            put("code",login.deviceCode); put("client_id",login.configuration.clientId); put("client_secret",login.configuration.clientSecret)
            put("grant_type","http://oauth.net/grant_type/device/1.0")
        },allowOAuthError = true)
        DeviceCodeParser.poll(result,login.configuration,System.currentTimeMillis())
    }
    /** Successful OAuth alone is insufficient: require nonempty authenticated TV Home. */
    suspend fun probe(session: DeviceSession): JsonObject = withContext(Dispatchers.IO) {
        requestBrowse("default",null,session)
    }
    suspend fun browse(browseId: String?,continuation: String?): JsonObject {
        // TV serves FEplaylist_aggregation (the owner's playlists) itself; FEmy_youtube is a mixed overview page.
        val id = if (browseId == "FEwhat_to_watch") "default" else browseId
        return request("browse",buildJsonObject { if (continuation != null) put("continuation",continuation) else put("browseId",id ?: "default") })
    }
    /** Watch-history pings only count toward the account's history when they carry its bearer token. */
    suspend fun track(url: HttpUrl): Unit = withContext(Dispatchers.IO) {
        val (session,revision) = browseMutex.withLock { currentSession() }
        val request = Request.Builder().url(url.newBuilder().host("www.youtube.com").build()).header("User-Agent",USER_AGENT)
            .header("Authorization","Bearer ${session.accessToken}").build()
        execute(request,false)
        if (sessions.status.value.revision != revision) throw AccountFailure(VideoError.AuthExpired)
    }
    suspend fun request(endpoint: String,payload: JsonObject): JsonObject = withContext(Dispatchers.IO) { browseMutex.withLock {
        val (session,revision) = currentSession()
        delay((1000-(android.os.SystemClock.elapsedRealtime()-lastBrowse)).coerceAtLeast(0))
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        lastBrowse = android.os.SystemClock.elapsedRealtime()
        val result = requestApi(endpoint,payload,session)
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        result
    } }
    /** Refreshes an expiring token and the client version; call with browseMutex held. */
    private suspend fun currentSession(): Pair<DeviceSession,Long> {
        val snapshot = sessions.deviceSnapshot() ?: throw AccountFailure(VideoError.AuthExpired)
        var session = snapshot.session
        if (session.expiresAt <= System.currentTimeMillis()+60_000) {
            session = refreshCandidate(session)
            if (!sessions.refreshDeviceIfRevision(session,snapshot.revision)) throw AccountFailure(VideoError.AuthExpired)
        }
        val now = System.currentTimeMillis()
        if (contextAt == 0L || now-contextAt > 6*60*60*1000L) {
            try {
                val fresh = TvConfigurationParser.refresh(get("$ORIGIN/tv"),session.configuration)
                contextVersion = fresh.version; contextVisitor = fresh.visitor; contextAt = now
            } catch (e: CancellationException) { throw e }
            // Network or a changed /tv page: keep the stored configuration and retry discovery in five minutes.
            catch (_: Exception) { contextAt = now-6*60*60*1000L+5*60*1000L }
        }
        if (contextVersion != null && (session.configuration.version != contextVersion || session.configuration.visitor != contextVisitor)) {
            session = DeviceSession(session.accessToken,session.refreshToken,session.expiresAt,
                TvClientConfiguration(session.configuration.clientId,session.configuration.clientSecret,contextVersion!!,contextVisitor!!))
            if (!sessions.refreshDeviceIfRevision(session,snapshot.revision)) throw AccountFailure(VideoError.AuthExpired)
        }
        return session to snapshot.revision
    }
    suspend fun refreshCandidate(session: DeviceSession): DeviceSession = withContext(Dispatchers.IO) {
        val result = post("/o/oauth2/token",buildJsonObject {
            put("client_id",session.configuration.clientId); put("client_secret",session.configuration.clientSecret)
            put("refresh_token",session.refreshToken); put("grant_type","refresh_token")
        },allowOAuthError = true)
        if (result.string("error") != null) throw AccountFailure(VideoError.AuthExpired)
        (DeviceCodeParser.poll(result,session.configuration,System.currentTimeMillis(),session.refreshToken) as? DevicePoll.Granted)?.session
            ?: throw AccountFailure(VideoError.AuthExpired)
    }
    private suspend fun requestBrowse(id: String?,continuation: String?,session: DeviceSession): JsonObject {
        return requestApi("browse",buildJsonObject { if (continuation != null) put("continuation",continuation) else put("browseId",id ?: "default") },session)
    }
    private suspend fun requestApi(endpoint: String,payload: JsonObject,session: DeviceSession): JsonObject {
        require(endpoint in InnerTubeClient.ENDPOINTS)
        val body = buildJsonObject {
            payload.forEach { (key,value) -> put(key,value) }
            put("context",buildJsonObject {
                put("client",buildJsonObject {
                    put("clientName","TVHTML5"); put("clientVersion",session.configuration.version)
                    put("visitorData",session.configuration.visitor); put("userAgent",USER_AGENT); put("clientScreen","WATCH")
                    put("hl","en"); put("gl","US")
                    put("tvAppInfo",buildJsonObject { put("appQuality","TV_APP_QUALITY_FULL_ANIMATION"); put("zylonLeftNav",true) })
                }); put("user",buildJsonObject { put("enableSafetyMode",false); put("lockedSafetyMode",false) })
            })
            put("racyCheckOk",true); put("contentCheckOk",true)
        }
        val result = post("/youtubei/v1/$endpoint?prettyPrint=false",body,session.accessToken)
        if ((result["responseContext"] as? JsonObject)?.get("mainAppWebResponseContext")?.jsonObject?.get("loggedOut") == JsonPrimitive(true))
            throw AccountFailure(VideoError.AuthExpired)
        if (result["error"] != null) throw AccountFailure(VideoError.ContentUnavailable)
        return result
    }
    private suspend fun get(url: String): String = execute(Request.Builder().url(url).header("User-Agent",USER_AGENT).build(),false)
    private suspend fun post(path: String,body: JsonObject,bearer: String? = null,allowOAuthError: Boolean = false): JsonObject {
        val builder = Request.Builder().url(ORIGIN+path).header("User-Agent",USER_AGENT).header("Origin",ORIGIN).header("Referer","$ORIGIN/tv")
        bearer?.let {
            builder.header("Authorization","Bearer $it").header("X-Youtube-Client-Name","7").header("X-Goog-AuthUser","0")
            val client = (body["context"] as? JsonObject)?.get("client") as? JsonObject
            client?.string("clientVersion")?.let { version -> builder.header("X-Youtube-Client-Version",version) }
            client?.string("visitorData")?.let { visitor -> builder.header("X-Goog-Visitor-Id",visitor) }
        }
        return oauthJson.parseToJsonElement(execute(builder.post(body.toString().toRequestBody("application/json".toMediaType())).build(),allowOAuthError)).jsonObject
    }
    private suspend fun execute(request: Request,allowOAuthError: Boolean): String = http.newCall(request).await().use { response ->
        currentCoroutineContext().ensureActive()
        if (!response.isSuccessful && !(allowOAuthError && response.code in setOf(400,403,428))) throw AccountFailure(when(response.code) {
            401,403 -> VideoError.AuthExpired; 429 -> VideoError.RateLimited; else -> VideoError.Network
        })
        response.body?.string() ?: throw AccountFailure(VideoError.ExtractionBroken)
    }
    fun cancelNetwork() { http.dispatcher.cancelAll() }
    companion object {
        private const val ORIGIN = "https://www.youtube.com"
        // Same consistent TV browser profile for config discovery, OAuth and browse. Streams remain anonymous.
        const val USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/3.0 Chrome/94.0.4606.31 TV Safari/537.36"
    }
}
