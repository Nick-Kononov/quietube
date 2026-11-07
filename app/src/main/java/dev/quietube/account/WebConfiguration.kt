package dev.quietube.account

import kotlinx.serialization.json.*

class WebConfiguration(val context: JsonObject, val version: String, val visitor: String,
    val userSessionId: String?, val delegatedSessionId: String?, val loggedIn: Boolean?) {
    override fun toString() = "WebConfiguration([redacted])"
}
internal val accountJson = Json { ignoreUnknownKeys = true; isLenient = false }

/** Balanced JSON scanning handles braces inside escaped strings and multiple ytcfg.set calls. */
object WebConfigurationParser {
    fun parse(html: String): WebConfiguration {
        val merged = linkedMapOf<String, JsonElement>()
        Regex("ytcfg\\.set\\s*\\(").findAll(html).forEach { match ->
            val start = (match.range.last + 1 until html.length).firstOrNull { !html[it].isWhitespace() } ?: return@forEach
            if (html[start] != '{') return@forEach
            var depth = 0; var quoted = false; var escaped = false
            for (i in start until html.length) {
                val c = html[i]
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) {
                        try { merged.putAll(accountJson.parseToJsonElement(html.substring(start, i + 1)).jsonObject) }
                        catch (_: Exception) { /* Skip malformed config blocks. */ }
                        break
                    } }
                }
            }
        }
        val root = JsonObject(merged)
        val context = root.obj("INNERTUBE_CONTEXT") ?: JsonObject(emptyMap())
        val client = context.obj("client")
        val version = root.str("INNERTUBE_CLIENT_VERSION") ?: client?.str("clientVersion")
        val visitor = root.str("VISITOR_DATA") ?: client?.str("visitorData")
        require(!version.isNullOrBlank() && !visitor.isNullOrBlank()) { "YouTube client configuration unavailable." }
        val sync = root.str("DATASYNC_ID")?.split("||", limit = 2)
        val user = root.str("USER_SESSION_ID") ?: sync?.let { if (it.size == 2 && it[1].isNotBlank()) it[1] else it.first() }
        val delegated = root.str("DELEGATED_SESSION_ID") ?: sync?.takeIf { it.size == 2 && it[1].isNotBlank() }?.first()
        return WebConfiguration(context, version, visitor, user, delegated,
            (root["LOGGED_IN"] as? JsonPrimitive)?.booleanOrNull)
    }
}
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
