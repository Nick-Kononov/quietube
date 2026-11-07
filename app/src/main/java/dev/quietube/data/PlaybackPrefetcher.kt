package dev.quietube.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

@Singleton
class NetworkStatus @Inject constructor(@ApplicationContext private val context: Context) {
    fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}

/**
 * Caches resolved playback details. On Wi-Fi, videos that stay visible in a list are resolved
 * ahead of a tap, two at a time; a tap reuses a finished or in-flight result. Failures are never
 * cached, and rate limiting pauses prefetching so it cannot get playback itself blocked.
 */
@Singleton
class PlaybackPrefetcher(
    private val source: AnonymousSource,
    private val prefetchAllowed: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(source: AnonymousSource, network: NetworkStatus) : this(source, network::onWifi)

    private class Entry(val result: Deferred<Outcome<PlaybackDetails>>, val createdAt: Long)
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private var wanted = ArrayDeque<String>()
    private var pausedUntil = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init { repeat(WORKERS) { scope.launch { work() } } }

    suspend fun get(url: String): Outcome<PlaybackDetails> {
        val (entry, reused) = synchronized(lock) { freshLocked(url)?.let { it to true } ?: (startLocked(url) to false) }
        val result = entry.result.await()
        if (result !is Outcome.Failure) return result
        forget(url, entry, result)
        // A shared prefetch may have failed transiently; a tap deserves its own attempt.
        if (!reused || result.error == VideoError.RateLimited) return result
        val retry = synchronized(lock) { startLocked(url) }
        return retry.result.await().also { if (it is Outcome.Failure) forget(url, retry, it) }
    }

    /** Stream URLs were rejected (expired or bound to another network); the next get re-extracts. */
    fun invalidate(url: String) { synchronized(lock) { entries.remove(url) } }

    /** Replaces the queue: videos scrolled out of view before their turn are not resolved. */
    fun visible(urls: List<String>) {
        synchronized(lock) { wanted = ArrayDeque(urls.distinct().take(MAX_VISIBLE)) }
        wake.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            val next = synchronized(lock) {
                if (!prefetchAllowed() || clock() < pausedUntil) { wanted.clear(); null }
                else generateSequence { wanted.removeFirstOrNull() }.firstOrNull { freshLocked(it) == null }
                    ?.let { url -> url to startLocked(url).also { if (wanted.isNotEmpty()) wake.trySend(Unit) } }
            }
            if (next == null) { wake.receive(); continue }
            val (url, entry) = next
            val result = entry.result.await()
            if (result is Outcome.Failure) forget(url, entry, result)
        }
    }

    private fun forget(url: String, entry: Entry, failure: Outcome.Failure) = synchronized(lock) {
        if (entries[url] === entry) entries.remove(url)
        if (failure.error == VideoError.RateLimited) pausedUntil = clock() + RATE_LIMIT_PAUSE_MS
    }
    private fun freshLocked(url: String): Entry? = entries[url]?.takeIf { clock() - it.createdAt < TTL_MS }
    private fun startLocked(url: String): Entry =
        Entry(scope.async { source.playback(url) }, clock()).also { entries[url] = it }

    companion object {
        /** Signed stream URLs last about six hours; stay well inside that. */
        const val TTL_MS = 60 * 60 * 1000L
        const val RATE_LIMIT_PAUSE_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 40
        const val MAX_VISIBLE = 6
        const val WORKERS = 2
    }
}
