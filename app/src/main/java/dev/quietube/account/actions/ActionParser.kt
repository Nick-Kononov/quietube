package dev.quietube.account.actions

import dev.quietube.account.AccountPageParser
import dev.quietube.account.bool
import dev.quietube.account.obj
import dev.quietube.account.objects
import dev.quietube.account.str
import dev.quietube.data.*
import dev.quietube.links.CHANNEL_ID
import dev.quietube.links.VIDEO_ID
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where YouTube expects the start-of-playback ping and the later watch-time pings. */
class TrackingUrls(val playback: HttpUrl, val watchtime: HttpUrl)

internal fun JsonElement?.label(): String? = (this as? JsonObject)?.let(AccountPageParser::text)?.takeIf { it.isNotBlank() }
object ActionParser {
    private val FAILED = setOf("STATUS_FAILED", "ERROR", "FAILED")
    private val TRACKING_HOSTS = setOf("www.youtube.com", "s.youtube.com", "youtube.com")
    private val PLAYBACK_NONCE = Regex("[A-Za-z0-9_-]{16}")
    fun requireAccepted(response: JsonObject) {
        require(response.obj("responseContext") != null && response["error"] == null) { "Unconfirmed action response" }
        require(response.objects().none { it.str("status") in FAILED || it.obj("alertRenderer")?.str("type") == "ERROR" }) { "Action rejected" }
    }
    /** The signed-in (selected) account from account/accounts_list; brand accounts are listed alongside it. */
    fun profile(response: JsonObject): Profile {
        val accounts = response.objects().filter { it["accountName"] != null && it["accountPhoto"] != null }.toList()
        val account = accounts.firstOrNull { it.bool("isSelected") == true } ?: accounts.firstOrNull() ?: error("Account unavailable")
        val photo = account.obj("accountPhoto")?.objects()?.filter { it.str("url")?.startsWith("https://") == true }
            ?.maxByOrNull { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }?.str("url")
        return Profile(account["accountName"].label().orEmpty(), photo)
    }
    fun videoId(url: String): String {
        val id = url.toHttpUrlOrNull()?.queryParameter("v") ?: ""
        require(VIDEO_ID.matches(id)); return id
    }
    fun engagement(player: JsonObject, next: JsonObject): Engagement {
        val channel = player.obj("videoDetails")?.str("channelId")?.takeIf(CHANNEL_ID::matches)
        // One walk of the watch page collects every renderer the rest of this function reads.
        var metadata: JsonObject? = null
        var controls: JsonObject? = null
        val subscribeButtons = mutableListOf<JsonObject>()
        val toggleButtons = mutableListOf<JsonObject>()
        for (found in next.objects()) {
            if (metadata == null) metadata = found.obj("videoMetadataRenderer") ?: found.obj("slimVideoMetadataRenderer")
            if (controls == null) controls = found.obj("transportControlsRenderer")
            found.obj("subscribeButtonRenderer")?.let(subscribeButtons::add)
            found.obj("toggleButtonRenderer")?.let(toggleButtons::add)
        }
        val subscribe = subscribeButtons.firstOrNull { button ->
            button.str("channelId") == channel || button.objects().any { (it["channelIds"] as? JsonArray)?.any { id -> (id as? JsonPrimitive)?.content == channel } == true }
        }
        val owner = metadata?.obj("owner")?.obj("videoOwnerRenderer")
        fun toggled(key: String) = controls?.obj(key)?.obj("toggleButtonRenderer")?.bool("isToggled")
        val ownerChannel = owner?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
        val subscribed = subscribe?.bool("subscribed")
            ?: if (ownerChannel == channel) ((owner?.obj("subscriptionButton")?.get("subscribed") ?: owner?.get("subscribed")) as? JsonPrimitive)?.booleanOrNull else null
        val controlChannel = controls?.obj("channelButton")?.obj("videoOwnerRenderer")?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
        val rating = when (metadata?.str("likeStatus")) {
            "LIKE" -> Rating.Like
            "DISLIKE" -> Rating.Dislike
            "INDIFFERENT" -> Rating.None
            else -> when {
                toggled("likeButton") == true -> Rating.Like
                toggled("dislikeButton") == true -> Rating.Dislike
                toggled("likeButton") == false && toggled("dislikeButton") == false -> Rating.None
                else -> null
            }
        }
        val like = metadata?.get("likesCount").label()
            ?: controls?.obj("likeButton")?.obj("toggleButtonRenderer")?.get("defaultText").label()?.takeIf { text -> text.any(Char::isDigit) }
            ?: toggleButtons.firstOrNull { it.objects().any { endpoint -> endpoint.obj("likeEndpoint")?.str("status") == "LIKE" } }?.get("defaultText").label()
        return Engagement(rating, like, metadata?.get("dislikesCount").label(), channel,
            subscribed ?: if (controlChannel == channel) toggled("subscribeButton") else null)
    }
    fun tracking(player: JsonObject): TrackingUrls {
        val tracking = player.obj("playbackTracking") ?: error("Tracking unavailable")
        fun url(key: String, path: String): HttpUrl {
            val url = tracking.obj(key)?.str("baseUrl")?.toHttpUrlOrNull() ?: error("Tracking unavailable")
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.host in TRACKING_HOSTS && url.encodedPath == path)
            return url
        }
        return TrackingUrls(url("videostatsPlaybackUrl", "/api/stats/playback"), url("videostatsWatchtimeUrl", "/api/stats/watchtime"))
    }
    /** There is deliberately no `final=1`: with the TV client it can mark the entire video watched. */
    fun trackingUrl(base: HttpUrl, cpn: String, position: Double, segments: List<WatchSegment> = emptyList()): HttpUrl {
        require(PLAYBACK_NONCE.matches(cpn) && position.isFinite() && position >= 0)
        require(segments.all { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end >= it.start })
        fun number(value: Double) = java.lang.String.format(java.util.Locale.US, "%.3f", value)
        return base.newBuilder().setQueryParameter("ver", "2").setQueryParameter("cpn", cpn)
            .setQueryParameter("cmt", number(position)).setQueryParameter("el", "detailpage")
            .apply {
                if (segments.isNotEmpty()) { setQueryParameter("st", segments.joinToString(",") { number(it.start) }); setQueryParameter("et", segments.joinToString(",") { number(it.end) }) }
            }.build()
    }
}
