package dev.quietube.data

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared by the opt-in crowd-sourced services (SponsorBlock, DeArrow): its own client with no cookies, no redirects
 * and a call timeout, so nothing from the YouTube session or the app's other clients can travel with a request.
 */
internal class ThirdPartyHttp(private val maxBody: Long) {
    private val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()

    /** The body of a successful response, null for 404 (nothing known), or throws for anything else. */
    fun get(url: HttpUrl): String? = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        // Never buffer an arbitrarily large third-party body.
        val body = response.body
        if (body.contentLength() > maxBody) throw java.io.IOException("Response too large")
        val bytes = body.source().apply { request(maxBody + 1) }.buffer
        if (bytes.size > maxBody) throw java.io.IOException("Response too large")
        bytes.readUtf8()
    }
}

/** The first [length] hex characters of the video ID's SHA-256: the service sees only a bucket shared by many videos. */
internal fun hashPrefix(videoId: String, length: Int = 4): String =
    MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(length)

internal class Cached<T>(val at: Long, val value: T)

/** A small time-limited cache of answers per video, including "nothing known", so scrolling back doesn't refetch. */
internal class AnswerCache<T>(private val ttlMs: Long, private val capacity: Int = 300, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = object : LinkedHashMap<String, Cached<T>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<T>>) = size > capacity
    }
    @Synchronized fun get(key: String): Cached<T>? = entries[key]?.takeIf { now() - it.at < ttlMs }
    @Synchronized fun put(key: String, value: T) { entries[key] = Cached(now(), value) }
}
