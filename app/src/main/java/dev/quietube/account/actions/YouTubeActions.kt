package dev.quietube.account.actions

import android.os.SystemClock
import dev.quietube.account.*
import dev.quietube.account.oauth.TvOAuthClient
import dev.quietube.data.*
import dev.quietube.links.CHANNEL_ID
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*

private class Ticket(val url: HttpUrl, val cpn: String, val revision: Long) : WatchTicket {
    override fun toString() = "WatchTicket([redacted])"
}
private class PlayerResponse(val videoId: String, val revision: Long, val at: Long, val json: JsonObject) {
    /** Tracking URLs belong to one playback; a second watch of the same video fetches its own. */
    var watchStarted = false
}
@Singleton
class YouTubeActions @Inject constructor(private val client: InnerTubeClient, private val sessions: EncryptedSessionStore,
    private val tv: TvOAuthClient) : AccountActions {
    // Cookie sessions send tracking pings without credentials; device-code sessions attach the bearer token (TvOAuthClient.track).
    private val trackingHttp = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val playerLock = Mutex()
    @Volatile private var lastPlayer: PlayerResponse? = null
    private suspend fun <T> guarded(block: suspend (Long) -> T): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            sessions.initialize(); val status = sessions.status.value
            if (!status.signedIn) return@withContext Outcome.Failure(VideoError.AuthExpired)
            val result = block(status.revision)
            if (sessions.status.value.revision != status.revision) Outcome.Failure(VideoError.AuthExpired) else Outcome.Success(result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(InnerTubeClient.errorFor(e)) }
    }
    /**
     * Opening a video needs its `player` response twice, for the channel and for history tracking. Account requests
     * are spaced a second apart, so the two callers share one response instead of each waiting for their own.
     */
    private suspend fun player(id: String, revision: Long, forWatch: Boolean): JsonObject = playerLock.withLock {
        val now = SystemClock.elapsedRealtime()
        val reusable = lastPlayer?.takeIf { it.videoId == id && it.revision == revision && now - it.at < PLAYER_REUSE_MS && !(forWatch && it.watchStarted) }
        val response = reusable ?: PlayerResponse(id, revision, now, client.request("player", buildJsonObject { put("videoId", id) }))
            .also { if (sessions.status.value.revision == revision) lastPlayer = it }
        if (forWatch) response.watchStarted = true
        response.json
    }
    override suspend fun engagement(url: String) = guarded { revision ->
        val id = ActionParser.videoId(url)
        val player = player(id, revision, forWatch = false)
        val next = client.request("next", buildJsonObject { put("videoId", id) })
        ActionParser.engagement(player, next)
    }
    suspend fun profile() = guarded {
        val json = client.request("account/accounts_list", buildJsonObject { })
        ActionParser.profile(json)
    }
    override suspend fun rate(url: String, rating: Rating, previous: Rating) = guarded {
        val action = when (rating) { Rating.Like -> "like"; Rating.Dislike -> "dislike"; Rating.None -> if (previous == Rating.Dislike) "removedislike" else "removelike" }
        ActionParser.requireAccepted(client.request("like/$action", buildJsonObject { put("target", buildJsonObject { put("videoId", ActionParser.videoId(url)) }) })); Unit
    }
    override suspend fun subscribe(channelId: String, subscribe: Boolean) = guarded {
        require(CHANNEL_ID.matches(channelId))
        ActionParser.requireAccepted(client.request("subscription/${if (subscribe) "subscribe" else "unsubscribe"}", buildJsonObject {
            put("channelIds", buildJsonArray { add(channelId) }); put("params", "")
        })); Unit
    }
    override suspend fun startWatch(url: String, position: Double): Outcome<WatchTicket> = guarded { revision ->
        val tracking = ActionParser.tracking(player(ActionParser.videoId(url), revision, forWatch = true))
        val random = SecureRandom(); val cpn = (1..16).map { NONCE_ALPHABET[random.nextInt(64)] }.joinToString("")
        ping(ActionParser.trackingUrl(tracking.playback, cpn, position), revision)
        Ticket(tracking.watchtime, cpn, revision)
    }
    override suspend fun reportWatch(ticket: WatchTicket, segments: List<WatchSegment>, position: Double) = guarded { revision ->
        val current = ticket as? Ticket ?: error("Invalid watch session")
        if (current.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        if (segments.isNotEmpty()) ping(ActionParser.trackingUrl(current.url, current.cpn, position, segments), revision)
        Unit
    }
    private suspend fun ping(url: HttpUrl, revision: Long) {
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        if (sessions.status.value.method == SessionMethod.DeviceCode) return tv.track(url)
        trackingHttp.newCall(Request.Builder().url(url).header("User-Agent", TvOAuthClient.USER_AGENT).build()).await().use {
            if (!it.isSuccessful) throw AccountFailure(if (it.code == 429) VideoError.RateLimited else VideoError.Network)
        }
    }
    /** Sign-out: the held response is account data. */
    fun clear() { lastPlayer = null }
    private companion object {
        const val PLAYER_REUSE_MS = 60_000L
        const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
