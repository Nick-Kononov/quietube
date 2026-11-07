package dev.quietube.account

import java.security.MessageDigest
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Deliberately not a data class; values and expiry metadata remain private. */
class SessionCookies private constructor(private val values: Map<String, String>, private val expires: Map<String, Long> = emptyMap()) {
    fun header(nowMillis: Long = System.currentTimeMillis()): String = values.entries
        .filter { (expires[it.key] ?: Long.MAX_VALUE) > nowMillis }.joinToString("; ") { "${it.key}=${it.value}" }
    internal fun value(name: String, nowMillis: Long = System.currentTimeMillis()): String? =
        values[name]?.takeIf { (expires[name] ?: Long.MAX_VALUE) > nowMillis }
    internal fun usable(nowMillis: Long = System.currentTimeMillis()): Boolean = value("LOGIN_INFO", nowMillis) != null &&
        listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID").any { value(it,nowMillis) != null }
    internal fun sameContents(other: SessionCookies): Boolean = values == other.values && expires == other.expires
    internal fun encoded(): String = buildJsonObject {
        put("cookies", JsonArray(values.map { (name,value) -> buildJsonObject {
            put("name", name); put("value", value); expires[name]?.let { put("expires", it) }
        } }))
    }.toString()
    internal fun updated(headers: List<String>, nowMillis: Long = System.currentTimeMillis()): SessionCookies {
        val next = values.toMutableMap(); val expiry = expires.toMutableMap()
        next.keys.toList().filter { (expiry[it] ?: Long.MAX_VALUE) <= nowMillis }.forEach { next.remove(it); expiry.remove(it) }
        val origin = "https://www.youtube.com/".toHttpUrl()
        headers.forEach { header ->
            val cookie = Cookie.parse(origin, header) ?: return@forEach
            if (cookie.domain !in setOf("youtube.com", "www.youtube.com") || cookie.path != "/") return@forEach
            if (!valid(cookie.name, cookie.value) && cookie.expiresAt > nowMillis) return@forEach
            if (cookie.expiresAt <= nowMillis) { next.remove(cookie.name); expiry.remove(cookie.name) }
            else { next[cookie.name] = cookie.value; if (cookie.persistent) expiry[cookie.name] = cookie.expiresAt else expiry.remove(cookie.name) }
        }
        return SessionCookies(next,expiry)
    }
    override fun toString(): String = "SessionCookies([redacted])"
    companion object {
        private val namePattern = Regex("[A-Za-z0-9_-]+")
        private fun valid(name: String, value: String): Boolean = namePattern.matches(name) && value.isNotBlank() &&
            value.none { it <= ' ' || it >= '\u007f' || it == ';' }
        private fun checked(values: Map<String,String>, expiry: Map<String,Long> = emptyMap(), now: Long = System.currentTimeMillis()): SessionCookies {
            require(values.all { valid(it.key,it.value) }) { "Invalid cookie format." }
            return SessionCookies(values,expiry).also { require(it.usable(now)) {
                "No usable YouTube sign-in session found. Sign in again or import fresh cookies."
            } }
        }
        fun fromHeader(header: String): SessionCookies {
            require(header.length <= 512_000) { "Cookie data is too large." }
            val result = linkedMapOf<String,String>()
            header.split(';').forEach { part ->
                val pair = part.trim().split('=',limit=2)
                if (pair.size == 2) { require(valid(pair[0],pair[1])) { "Invalid cookie format." }; result[pair[0]] = pair[1] }
            }
            return checked(result)
        }
        internal fun decoded(text: String): SessionCookies {
            if (!text.startsWith('{')) return fromHeader(text) // Existing encrypted v1 sessions migrate on next write.
            val rows = accountJson.parseToJsonElement(text).jsonObject["cookies"]!!.jsonArray
            val values = linkedMapOf<String,String>(); val expires = linkedMapOf<String,Long>()
            rows.forEach { row ->
                val obj = row.jsonObject; val name = obj["name"]!!.jsonPrimitive.content
                values[name] = obj["value"]!!.jsonPrimitive.content
                obj["expires"]?.jsonPrimitive?.longOrNull?.let { expires[name] = it }
            }
            return checked(values,expires)
        }
        fun fromNetscape(text: String, nowSeconds: Long = System.currentTimeMillis() / 1000): SessionCookies {
            require(text.length <= 512_000) { "Cookie file is too large." }
            val values = linkedMapOf<String,String>(); val expires = linkedMapOf<String,Long>()
            text.lineSequence().forEach { raw ->
                val line = raw.removePrefix("\uFEFF").removePrefix("#HttpOnly_").trimEnd('\r')
                if (line.isBlank() || line.startsWith('#')) return@forEach
                val cols = line.split('\t',limit=7)
                require(cols.size == 7) { "Expected a Netscape cookies.txt file with seven tab-separated columns." }
                if (cols[0].lowercase().removePrefix(".") !in setOf("youtube.com","www.youtube.com") || cols[2] != "/") return@forEach
                val expiry = cols[4].toLongOrNull() ?: throw IllegalArgumentException("Invalid cookie expiration.")
                if (expiry != 0L && expiry <= nowSeconds) return@forEach
                require(expiry in 0..Long.MAX_VALUE/1000 && valid(cols[5],cols[6])) { "Invalid cookie format." }
                values[cols[5]] = cols[6]
                if (expiry > 0) expires[cols[5]] = expiry * 1000 else expires.remove(cols[5])
            }
            return checked(values,expires,nowSeconds*1000)
        }
    }
}

/** Mirrors yt-dlp YouTube _base.py; inject time so tests never need live YouTube. */
object YouTubeAuthorization {
    const val ORIGIN = "https://www.youtube.com"
    fun generate(cookies: SessionCookies, timestamp: Long, userSessionId: String? = null): String {
        val additional = userSessionId?.takeIf { it.isNotBlank() }
        val primary = cookies.value("SAPISID") ?: cookies.value("__Secure-3PAPISID")
        return listOf("SAPISIDHASH" to primary, "SAPISID1PHASH" to cookies.value("__Secure-1PAPISID"),
            "SAPISID3PHASH" to cookies.value("__Secure-3PAPISID")).mapNotNull { (scheme, sid) ->
            sid ?: return@mapNotNull null
            val input = listOfNotNull(additional, timestamp.toString(), sid, ORIGIN).joinToString(" ")
            val hash = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            "$scheme ${timestamp}_$hash${if (additional != null) "_u" else ""}"
        }.joinToString(" ")
    }
}
