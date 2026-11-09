package dev.quietube.account.actions

import dev.quietube.account.*
import dev.quietube.data.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.SecureRandom

private class Ticket(val url: HttpUrl,val cpn: String,val revision: Long) : WatchTicket {
    override fun toString() = "WatchTicket([redacted])"
}
@Singleton
class YouTubeActions @Inject constructor(private val client: InnerTubeClient,private val sessions: EncryptedSessionStore,
    private val tv: dev.quietube.account.oauth.TvOAuthClient) : AccountActions {
    // Cookie sessions send tracking pings without credentials; device-code sessions attach the bearer token (TvOAuthClient.track).
    private val trackingHttp = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
    private suspend fun <T> guarded(block: suspend (Long) -> T): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            sessions.initialize(); val status = sessions.status.value
            if (!status.signedIn) return@withContext Outcome.Failure(VideoError.AuthExpired)
            val result = block(status.revision)
            if (sessions.status.value.revision != status.revision) Outcome.Failure(VideoError.AuthExpired) else Outcome.Success(result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(InnerTubeClient.errorFor(e)) }
    }
    override suspend fun engagement(url: String) = guarded {
        val id = ActionParser.videoId(url)
        val payload = buildJsonObject { put("videoId",id) }
        val player = client.request("player",payload)
        val next = client.request("next",payload)
        ActionParser.engagement(player,next)
    }
    suspend fun profile() = guarded {
        val json = client.request("account/accounts_list",buildJsonObject { })
        ActionParser.profile(json)
    }
    override suspend fun rate(url: String,rating: Rating,previous: Rating) = guarded {
        val action = when(rating) { Rating.Like -> "like"; Rating.Dislike -> "dislike"; Rating.None -> if (previous == Rating.Dislike) "removedislike" else "removelike" }
        ActionParser.requireAccepted(client.request("like/$action",buildJsonObject { put("target",buildJsonObject { put("videoId",ActionParser.videoId(url)) }) })); Unit
    }
    override suspend fun subscribe(channelId: String,subscribe: Boolean) = guarded {
        require(Regex("UC[A-Za-z0-9_-]{22}").matches(channelId))
        ActionParser.requireAccepted(client.request("subscription/${if (subscribe) "subscribe" else "unsubscribe"}",buildJsonObject {
            put("channelIds",buildJsonArray { add(channelId) }); put("params","")
        })); Unit
    }
    override suspend fun startWatch(url: String,position: Double): Outcome<WatchTicket> = guarded { revision ->
        val player = client.request("player",buildJsonObject { put("videoId",ActionParser.videoId(url)) })
        val (playback,watchtime) = ActionParser.tracking(player)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val random = SecureRandom(); val cpn = (1..16).map { alphabet[random.nextInt(64)] }.joinToString("")
        ping(ActionParser.trackingUrl(playback,cpn,position),revision)
        Ticket(watchtime,cpn,revision)
    }
    override suspend fun reportWatch(ticket: WatchTicket,segments: List<Pair<Double,Double>>,position: Double,final: Boolean) = guarded { revision ->
        val current = ticket as? Ticket ?: error("Invalid watch session")
        if (current.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        // Never use the TV client's final=1 shortcut: it can mark the entire video watched.
        if (segments.isNotEmpty()) ping(ActionParser.trackingUrl(current.url,current.cpn,position,segments),revision)
        Unit
    }
    private suspend fun ping(url: HttpUrl,revision: Long) {
        currentCoroutineContext().ensureActive()
        if (sessions.status.value.revision != revision) throw AccountFailure(VideoError.AuthExpired)
        if (sessions.status.value.method == SessionMethod.DeviceCode) return tv.track(url)
        trackingHttp.newCall(Request.Builder().url(url).header("User-Agent",dev.quietube.account.oauth.TvOAuthClient.USER_AGENT).build()).execute().use {
            if (!it.isSuccessful) throw AccountFailure(if (it.code == 429) VideoError.RateLimited else VideoError.Network)
        }
    }
}
