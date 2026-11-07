package dev.quietube.account.actions

import dev.quietube.data.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonElement.objects(depth: Int = 0): Sequence<JsonObject> = sequence {
    if (depth > 80) return@sequence
    when (val element = this@objects) {
        is JsonObject -> { yield(element); element.values.forEach { yieldAll(it.objects(depth+1)) } }
        is JsonArray -> element.forEach { yieldAll(it.objects(depth+1)) }
        else -> Unit
    }
}
internal fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.o(key: String) = this[key] as? JsonObject
internal fun JsonElement?.label(): String? {
    val obj = this as? JsonObject ?: return null
    return obj.s("simpleText") ?: obj.s("content") ?: (obj["runs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("text") }?.joinToString("")?.takeIf { it.isNotBlank() }
}
object ActionParser {
    fun requireAccepted(response: JsonObject) {
        require(response.o("responseContext") != null && response["error"] == null) { "Unconfirmed action response" }
        require(response.objects().none { it.s("status") in setOf("STATUS_FAILED","ERROR","FAILED") ||
            it.o("alertRenderer")?.s("type") == "ERROR" }) { "Action rejected" }
    }
    fun videoId(url: String): String {
        val id = url.toHttpUrlOrNull()?.queryParameter("v") ?: ""
        require(Regex("[A-Za-z0-9_-]{11}").matches(id)); return id
    }
    fun engagement(player: JsonObject,next: JsonObject): Engagement {
        val channel = player.o("videoDetails")?.s("channelId")?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) }
        val metadata = next.objects().firstNotNullOfOrNull { it.o("videoMetadataRenderer") ?: it.o("slimVideoMetadataRenderer") }
        val subscribe = next.objects().mapNotNull { it.o("subscribeButtonRenderer") }.firstOrNull { obj ->
            obj.s("channelId") == channel || obj.objects().any { (it["channelIds"] as? JsonArray)?.any { id -> (id as? JsonPrimitive)?.content == channel } == true }
        }
        val owner = metadata?.o("owner")?.o("videoOwnerRenderer")
        val controls = next.objects().firstNotNullOfOrNull { it.o("transportControlsRenderer") }
        fun toggled(key: String) = (controls?.o(key)?.o("toggleButtonRenderer")?.get("isToggled") as? JsonPrimitive)?.booleanOrNull
        val ownerChannel = owner?.o("navigationEndpoint")?.o("browseEndpoint")?.s("browseId")
        val subscribed = (subscribe?.get("subscribed") as? JsonPrimitive)?.booleanOrNull
            ?: if (ownerChannel == channel) ((owner?.o("subscriptionButton")?.get("subscribed") ?: owner?.get("subscribed")) as? JsonPrimitive)?.booleanOrNull else null
        val controlChannel = controls?.o("channelButton")?.o("videoOwnerRenderer")?.o("navigationEndpoint")?.o("browseEndpoint")?.s("browseId")
        val rating = when (metadata?.s("likeStatus")) { "LIKE" -> Rating.Like; "DISLIKE" -> Rating.Dislike; "INDIFFERENT" -> Rating.None; else -> when { toggled("likeButton") == true -> Rating.Like; toggled("dislikeButton") == true -> Rating.Dislike; toggled("likeButton") == false && toggled("dislikeButton") == false -> Rating.None; else -> null } }
        val like = metadata?.get("likesCount").label()?.takeIf { it.isNotBlank() }
            ?: controls?.o("likeButton")?.o("toggleButtonRenderer")?.get("defaultText").label()?.takeIf { text -> text.any(Char::isDigit) }
            ?: next.objects().mapNotNull { it.o("toggleButtonRenderer") }
            .firstOrNull { it.objects().any { endpoint -> endpoint.o("likeEndpoint")?.s("status") == "LIKE" } }?.get("defaultText").label()
        return Engagement(rating,like,metadata?.get("dislikesCount").label(),channel,
            subscribed ?: if (controlChannel == channel) toggled("subscribeButton") else null)
    }
    fun tracking(player: JsonObject): Pair<HttpUrl,HttpUrl> {
        val tracking = player.o("playbackTracking") ?: error("Tracking unavailable")
        fun url(key: String,path: String): HttpUrl {
            val url = tracking.o(key)?.s("baseUrl")?.toHttpUrlOrNull() ?: error("Tracking unavailable")
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.host in setOf("www.youtube.com","s.youtube.com","youtube.com") && url.encodedPath == path)
            return url
        }
        return url("videostatsPlaybackUrl","/api/stats/playback") to url("videostatsWatchtimeUrl","/api/stats/watchtime")
    }
    fun trackingUrl(base: HttpUrl,cpn: String,position: Double,segments: List<Pair<Double,Double>> = emptyList(),final: Boolean = false): HttpUrl {
        require(Regex("[A-Za-z0-9_-]{16}").matches(cpn) && position.isFinite() && position >= 0)
        require(segments.all { (start,end) -> start.isFinite() && end.isFinite() && start >= 0 && end >= start })
        fun number(value: Double) = java.lang.String.format(java.util.Locale.US,"%.3f",value)
        return base.newBuilder().setQueryParameter("ver","2").setQueryParameter("cpn",cpn)
            .setQueryParameter("cmt",number(position)).setQueryParameter("el","detailpage")
            .apply {
                if (segments.isNotEmpty()) { setQueryParameter("st",segments.joinToString(",") { number(it.first) }); setQueryParameter("et",segments.joinToString(",") { number(it.second) }) }
                if (final) setQueryParameter("final","1")
            }.build()
    }
}
