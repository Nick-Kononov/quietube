package dev.quietube.account.oauth

import dev.quietube.account.accountJson
import dev.quietube.account.decodeQuietly
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** The encrypted file's layout; EncryptedSessionStore recognises it by the leading "oauth" key. Changing it signs existing users out. */
@Serializable private class StoredDeviceSession(val oauth: StoredOAuth)
@Serializable private class StoredOAuth(val accessToken: String, val refreshToken: String, val expiresAt: Long,
    val clientId: String, val clientSecret: String, val version: String, val visitor: String)
@Serializable private class DeviceCodeResponse(@SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null, @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("user_code") val userCode: String? = null, val interval: Long? = null, @SerialName("device_code") val deviceCode: String? = null)
@Serializable private class TokenResponse(val error: String? = null, @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null, @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null)
class TvClientConfiguration(val clientId: String, val clientSecret: String, val version: String, val visitor: String) {
    override fun toString() = "TvClientConfiguration([redacted])"
}
class DeviceLogin(val userCode: String, val verificationUrl: String, val expiresAt: Long, val intervalSeconds: Long,
    internal val deviceCode: String, internal val configuration: TvClientConfiguration) {
    override fun toString() = "DeviceLogin([redacted])"
}
class DeviceSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val configuration: TvClientConfiguration) {
    init {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank() && accessToken.none { it <= ' ' || it >= '\u007f' }) { "Invalid token response." }
    }
    override fun toString() = "DeviceSession([redacted])"
    fun encoded(): String = accountJson.encodeToString(StoredDeviceSession.serializer(), StoredDeviceSession(StoredOAuth(
        accessToken, refreshToken, expiresAt, configuration.clientId, configuration.clientSecret, configuration.version, configuration.visitor)))
    companion object {
        fun decode(text: String): DeviceSession {
            val stored = accountJson.decodeQuietly(StoredDeviceSession.serializer(), text).oauth
            return DeviceSession(stored.accessToken, stored.refreshToken, stored.expiresAt,
                TvClientConfiguration(stored.clientId, stored.clientSecret, stored.version, stored.visitor))
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
    private val USER_CODE = Regex("[A-Za-z0-9-]{4,32}")
    fun login(obj: JsonObject, configuration: TvClientConfiguration, now: Long): DeviceLogin {
        val response = accountJson.decodeQuietly(DeviceCodeResponse.serializer(), obj)
        val url = response.verificationUrl ?: response.verificationUri ?: error("Missing activation URL")
        require(allowedActivationUrl(url)) { "Untrusted activation URL." }
        val seconds = response.expiresIn?.coerceIn(1, 1800) ?: 600
        val code = response.userCode ?: error("Missing user code")
        require(USER_CODE.matches(code)) { "Invalid user code." }
        return DeviceLogin(code, url, now+seconds*1000, (response.interval ?: 5).coerceIn(5, 60),
            response.deviceCode?.takeIf { it.isNotBlank() } ?: error("Missing device code"), configuration)
    }
    fun poll(obj: JsonObject, configuration: TvClientConfiguration, now: Long, previousRefresh: String? = null): DevicePoll {
        val response = accountJson.decodeQuietly(TokenResponse.serializer(), obj)
        return when (response.error) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied" -> DevicePoll.Denied
            "expired_token", "expired_device_code" -> DevicePoll.Expired
            null -> {
                val token = response.accessToken ?: error("Missing access token")
                require(response.tokenType?.equals("Bearer", true) == true) { "Unexpected token type." }
                val refresh = response.refreshToken ?: previousRefresh ?: error("Missing refresh token")
                val seconds = response.expiresIn?.takeIf { it in 1..86400 } ?: error("Invalid token expiry")
                DevicePoll.Granted(DeviceSession(token, refresh, now+seconds*1000, configuration))
            }
            else -> throw IllegalArgumentException("YouTube rejected device authorization.")
        }
    }
    fun allowedActivationUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            ((uri.host in setOf("www.google.com", "google.com") && uri.path == "/device") ||
             (uri.host in setOf("www.youtube.com", "youtube.com") && uri.path == "/activate"))
    } catch (_: Exception) { false }
}
object TvConfigurationParser {
    private val SCRIPT_PATTERNS = listOf(Regex("id=\"base-js\"\\s+src=\"([^\"]+)\""),
        Regex("\\.src\\s*=\\s*'([^']+m=base)'"), Regex("<script[^>]+src=\"([^\"]+/m=base)\""))
    private val CREDENTIALS = Regex("clientId:\"([-\\w]+\\.apps\\.googleusercontent\\.com)\",\\s*[$\\w]+:\"(\\w+)\"")
    fun scriptPath(html: String): String {
        val raw = SCRIPT_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: error("TV client script not found")
        val uri = URI("https://www.youtube.com").resolve(raw.replace("&amp;", "&").replace("\\/", "/"))
        require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path.startsWith("/s/") && uri.userInfo == null && uri.port == -1) { "Untrusted TV client script." }
        return uri.toString()
    }
    fun parse(html: String, script: String): TvClientConfiguration {
        val credentials = CREDENTIALS.find(script) ?: error("TV OAuth configuration changed")
        return refresh(html, TvClientConfiguration(credentials.groupValues[1], credentials.groupValues[2], "", ""))
    }
    fun refresh(html: String, previous: TvClientConfiguration): TvClientConfiguration {
        fun field(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val version = field("INNERTUBE_CLIENT_VERSION") ?: field("clientVersion") ?: error("TV version missing")
        val visitor = field("VISITOR_DATA") ?: field("visitorData") ?: error("TV visitor data missing")
        return TvClientConfiguration(previous.clientId, previous.clientSecret, version, visitor)
    }
}
