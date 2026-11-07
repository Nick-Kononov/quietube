package dev.quietube.account.oauth

import kotlinx.serialization.json.*
import java.net.URI

internal val oauthJson = Json { ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
class TvClientConfiguration(val clientId: String,val clientSecret: String,val version: String,val visitor: String) {
    override fun toString() = "TvClientConfiguration([redacted])"
}
class DeviceLogin(val userCode: String,val verificationUrl: String,val expiresAt: Long,val intervalSeconds: Long,
    internal val deviceCode: String,internal val configuration: TvClientConfiguration) {
    override fun toString() = "DeviceLogin([redacted])"
}
class DeviceSession(val accessToken: String,val refreshToken: String,val expiresAt: Long,val configuration: TvClientConfiguration) {
    init {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank() && accessToken.none { it <= ' ' || it >= '\u007f' }) { "Invalid token response." }
    }
    override fun toString() = "DeviceSession([redacted])"
    fun encoded(): String = buildJsonObject {
        put("oauth",buildJsonObject {
            put("accessToken",accessToken); put("refreshToken",refreshToken); put("expiresAt",expiresAt)
            put("clientId",configuration.clientId); put("clientSecret",configuration.clientSecret)
            put("version",configuration.version); put("visitor",configuration.visitor)
        })
    }.toString()
    companion object {
        fun decode(text: String): DeviceSession {
            val obj = oauthJson.parseToJsonElement(text).jsonObject["oauth"]!!.jsonObject
            return DeviceSession(obj.string("accessToken")!!,obj.string("refreshToken")!!,obj.number("expiresAt")!!,
                TvClientConfiguration(obj.string("clientId")!!,obj.string("clientSecret")!!,obj.string("version")!!,obj.string("visitor")!!))
        }
    }
}
sealed interface DevicePoll {
    class Granted(val session: DeviceSession) : DevicePoll
    data object Pending : DevicePoll
    data object SlowDown : DevicePoll
    data object Denied : DevicePoll
    data object Expired : DevicePoll
}
object DeviceCodeParser {
    fun login(obj: JsonObject,configuration: TvClientConfiguration,now: Long): DeviceLogin {
        val url = obj.string("verification_url") ?: obj.string("verification_uri") ?: error("Missing activation URL")
        require(allowedActivationUrl(url)) { "Untrusted activation URL." }
        val seconds = obj.number("expires_in")?.coerceIn(1,1800) ?: 600
        val code = obj.string("user_code") ?: error("Missing user code")
        require(Regex("[A-Za-z0-9-]{4,32}").matches(code)) { "Invalid user code." }
        return DeviceLogin(code,url,now+seconds*1000,(obj.number("interval") ?: 5).coerceIn(5,60),
            obj.string("device_code")?.takeIf { it.isNotBlank() } ?: error("Missing device code"),configuration)
    }
    fun poll(obj: JsonObject,configuration: TvClientConfiguration,now: Long,previousRefresh: String? = null): DevicePoll = when(obj.string("error")) {
        "authorization_pending" -> DevicePoll.Pending
        "slow_down" -> DevicePoll.SlowDown
        "access_denied" -> DevicePoll.Denied
        "expired_token", "expired_device_code" -> DevicePoll.Expired
        null -> {
            val token = obj.string("access_token") ?: error("Missing access token")
            require(obj.string("token_type")?.equals("Bearer",true) == true) { "Unexpected token type." }
            val refresh = obj.string("refresh_token") ?: previousRefresh ?: error("Missing refresh token")
            val seconds = obj.number("expires_in")?.takeIf { it in 1..86400 } ?: error("Invalid token expiry")
            DevicePoll.Granted(DeviceSession(token,refresh,now+seconds*1000,configuration))
        }
        else -> throw IllegalArgumentException("YouTube rejected device authorization.")
    }
    fun allowedActivationUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            ((uri.host in setOf("www.google.com","google.com") && uri.path == "/device") ||
             (uri.host in setOf("www.youtube.com","youtube.com") && uri.path == "/activate"))
    } catch (_: Exception) { false }
}
object TvConfigurationParser {
    fun scriptPath(html: String): String {
        val patterns = listOf(Regex("id=\"base-js\"\\s+src=\"([^\"]+)\""),
            Regex("\\.src\\s*=\\s*'([^']+m=base)'"),Regex("<script[^>]+src=\"([^\"]+/m=base)\""))
        val raw = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: error("TV client script not found")
        val uri = URI("https://www.youtube.com").resolve(raw.replace("&amp;","&").replace("\\/","/"))
        require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path.startsWith("/s/") && uri.userInfo == null && uri.port == -1) { "Untrusted TV client script." }
        return uri.toString()
    }
    fun parse(html: String,script: String): TvClientConfiguration {
        val credentials = Regex("clientId:\"([-\\w]+\\.apps\\.googleusercontent\\.com)\",\\s*[$\\w]+:\"(\\w+)\"")
            .find(script) ?: error("TV OAuth configuration changed")
        return refresh(html,TvClientConfiguration(credentials.groupValues[1],credentials.groupValues[2],"",""))
    }
    fun refresh(html: String,previous: TvClientConfiguration): TvClientConfiguration {
        fun field(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val version = field("INNERTUBE_CLIENT_VERSION") ?: field("clientVersion") ?: error("TV version missing")
        val visitor = field("VISITOR_DATA") ?: field("visitorData") ?: error("TV visitor data missing")
        return TvClientConfiguration(previous.clientId,previous.clientSecret,version,visitor)
    }
}
