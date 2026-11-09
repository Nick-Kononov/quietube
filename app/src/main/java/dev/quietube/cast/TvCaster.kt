package dev.quietube.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.quietube.account.await
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A TV on the local network: [appUrl] is its DIAL endpoint when the YouTube app can be launched, [renderer] its
 * DLNA AVTransport control URL when it can play a stream directly.
 */
data class Tv(val name: String, val host: String, val appUrl: String? = null, val renderer: String? = null)

/**
 * Plays a video in the TV's own YouTube app, the way the YouTube phone app does for smart TVs:
 * 1. DIAL (SSDP discovery, then an HTTP launch) opens YouTube on the TV with a one-time pairing code;
 * 2. the TV registers that code with YouTube, which exchanges it for the screen's lounge token;
 * 3. a Lounge (MDX) remote session tells the screen to play the video from the current position.
 * Lounge is not a public API, so this can break if YouTube changes it. Playback then runs on the TV under its own
 * account; nothing from this app's session is sent.
 */
@Singleton
class TvCaster @Inject constructor(private val repository: dev.quietube.data.VideoRepository,
    private val streaming: OkHttpClient, @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context) {
    private val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    /** Identifies this app as one remote across casts; YouTube shows [NAME] on the TV when it connects. */
    private val remoteId = UUID.randomUUID().toString()

    suspend fun discover(): List<Tv> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Triple<InetAddress, String, String>>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            val buffer = ByteArray(2048)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEARCH_MS)
            var sends = 0
            while (System.nanoTime() < deadline) {
                // UDP can drop; repeat the searches a few times during the window.
                if (sends < 3) {
                    for (target in listOf(DIAL, RENDERER)) {
                        val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                            "ST: $target\r\n\r\n").toByteArray()
                        socket.send(DatagramPacket(search, search.size, group, 1900))
                    }
                    sends++
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val headers = String(packet.data, 0, packet.length).lineSequence().mapNotNull { line ->
                    line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
                }.toMap()
                val target = headers["st"]?.takeIf { it == DIAL || it == RENDERER } ?: continue
                val location = headers["location"] ?: continue
                found.putIfAbsent("$target $location", Triple(packet.address, target, location))
            }
        }
        val described = coroutineScope {
            found.values.map { (address, target, location) -> async {
                runCatching { if (target == DIAL) describeDial(address, location) else describeRenderer(address, location) }.getOrNull()
            } }.awaitAll().filterNotNull()
        }
        // One TV usually answers both searches; merge them by address.
        described.groupBy { it.host }.map { (host, parts) ->
            Tv(parts.firstNotNullOfOrNull { tv -> tv.name.takeUnless { it.startsWith("TV at ") } } ?: parts.first().name, host,
                parts.firstNotNullOfOrNull { it.appUrl }, parts.firstNotNullOfOrNull { it.renderer })
        }
    }

    /** DIAL description gives the name and app endpoint; kept only if the TV's YouTube app answers. */
    private fun describeDial(responder: InetAddress, location: String): Tv? {
        // A responder may only point at itself, so discovery can't be used to reach other hosts.
        if (!LanHttp.isPrivate(responder) || InetAddress.getByName(URI(location).host) != responder) return null
        val description = LanHttp.request("GET", location)
        val appUrl = description.headers["application-url"]?.let { if (it.endsWith("/")) it else "$it/" } ?: return null
        if (InetAddress.getByName(URI(appUrl).host) != responder) return null
        if (LanHttp.request("GET", appUrl + YOUTUBE, mapOf("Origin" to ORIGIN)).code != 200) return null
        return Tv(friendlyName(description.body, responder), responder.hostAddress!!, appUrl = appUrl)
    }

    /** UPnP description of a media renderer: the AVTransport service's control URL. */
    private fun describeRenderer(responder: InetAddress, location: String): Tv? {
        if (!LanHttp.isPrivate(responder) || InetAddress.getByName(URI(location).host) != responder) return null
        val description = LanHttp.request("GET", location)
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(description.body)
            .map { it.groupValues[1] }.firstOrNull { it.contains(Dlna.SERVICE) } ?: return null
        val control = Regex("<controlURL>\\s*([^<]+?)\\s*</controlURL>").find(service)?.groupValues?.get(1) ?: return null
        val url = URI(location).resolve(control).toString()
        if (InetAddress.getByName(URI(url).host) != responder) return null
        return Tv(friendlyName(description.body, responder), responder.hostAddress!!, renderer = url)
    }

    private fun friendlyName(description: String, responder: InetAddress) =
        Regex("<friendlyName>([^<]{1,80})</friendlyName>").find(description)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "TV at ${responder.hostAddress}"

    private var direct: DirectStream? = null

    /**
     * Ad-free playback: the TV's media player gets a stream that this phone remuxes from YouTube's HD H.264 video
     * and AAC audio tracks. Runs until [stopDirect] or another cast; [StreamService] keeps the process alive.
     */
    suspend fun playDirect(tv: Tv, url: String, positionMs: Long) = withContext(Dispatchers.IO) {
        val control = tv.renderer ?: error("No media renderer")
        val details = (repository.playback(url) as? dev.quietube.data.Outcome.Success)?.value ?: error("Video unavailable")
        val video = details.videoStreams.filter { it.videoOnly && it.codec.startsWith("avc") && it.height in 1..1080 }
            .maxWithOrNull(compareBy({ it.height }, { it.bitrate })) ?: error("No H.264 stream")
        val audio = details.audioStreams.filter { it.mimeType == "audio/mp4" }
            .let { candidates -> dev.quietube.data.StreamSelector.selectAudio(details.copy(audioStreams = candidates)) } ?: error("No AAC stream")
        val address = InetAddress.getByName(tv.host)
        // The phone's address on the TV's network: whatever interface routes to the TV.
        val local = DatagramSocket().use { it.connect(address, 9); it.localAddress }
        val client = streaming.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
        val sources = dev.quietube.playback.ChunkedDataSource.Factory(
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setUserAgent(dev.quietube.data.ExtractorDownloader.USER_AGENT))
        stopDirect()
        val duration = details.video.durationSeconds.takeIf { it > 0 }?.times(1_000_000) ?: Long.MAX_VALUE / 4
        val bytes = listOf(video.url, audio.url).sumOf { dev.quietube.playback.youtubeContentLength(android.net.Uri.parse(it)) ?: 0L }
        check(bytes > 0) { "Unknown stream size" }
        val stream = DirectStream(sources, android.net.Uri.parse(video.url), android.net.Uri.parse(audio.url),
            duration, bytes, address, local)
        direct = stream
        active = Active(tv, url, null, stream)
        stream.start()
        StreamService.start(context, tv.name)
        try { Dlna.play(control, stream.url, details.video.title, stream.streamDurationUs()) }
        catch (e: Exception) { stopDirect(); throw e }
        // The stream starts at 0:00 (the TV's clock counts from its first timestamp); then it jumps to the position.
        if (positionMs > 2_000) {
            // It ignores seeks until frames are showing, and until then may still report the previous video's
            // position: wait for the clock to restart near zero and then run.
            var tries = 0
            var restarted = false
            while (tries++ < 40) {
                val status = if (stream.sending) Dlna.status(control) else null
                if (status != null && status.first < 3_000) restarted = true
                if (restarted && status != null && status.second && status.first >= 1_000) break
                delay(500)
            }
            val seek = runCatching { Dlna.seek(control, positionMs * 1000 - stream.originUs.coerceAtLeast(0)) }
            android.util.Log.i("YouPoopTv", "Start seek after ${tries * 500}ms: ${seek.exceptionOrNull()?.message ?: "sent"}")
        }
        Unit
    }

    fun stopDirect(tv: Tv? = null) {
        if (active?.stream != null) active = null
        direct?.close(); direct = null
        StreamService.stop(context)
        tv?.renderer?.let { control -> Thread { runCatching { Dlna.stop(control) } }.start() }
    }

    suspend fun play(tv: Tv, videoId: String, seconds: Long) = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        val pairing = UUID.randomUUID().toString()
        val launch = LanHttp.request("POST", (tv.appUrl ?: error("No YouTube app")) + YOUTUBE,
            mapOf("Origin" to ORIGIN, "Content-Type" to "text/plain; charset=utf-8"),
            "pairingCode=$pairing&v=$videoId&t=$seconds&theme=cl")
        check(launch.code == 200 || launch.code == 201) { "The TV refused to open YouTube (${launch.code})" }
        // The TV registers the pairing code once its YouTube app is up; that takes a few seconds from standby.
        var token: String? = null
        for (attempt in 1..20) {
            delay(1_000)
            token = loungeToken(pairing)
            if (token != null) break
        }
        val lounge = token ?: error("The TV did not pair with YouTube")
        command(lounge, mapOf("req0__sc" to "setPlaylist", "req0_videoId" to videoId, "req0_currentTime" to seconds.toString(),
            "req0_currentIndex" to "0", "req0_videoIds" to videoId))
        stopDirect()
        active = Active(tv, null, lounge, null)
    }

    /** Sends one Lounge command over a fresh remote session (session ids expire quickly). */
    private suspend fun command(token: String, request: Map<String, String>) {
        val bind = lounge(mapOf("RID" to "1", "app" to "youtube-desktop", "device" to "REMOTE_CONTROL", "id" to remoteId,
            "name" to NAME, "loungeIdToken" to token), emptyMap())
        val sid = Regex("\\[\"c\",\"([^\"]+)\"").find(bind)?.groupValues?.get(1) ?: error("No lounge session")
        val gsession = Regex("\\[\"S\",\"([^\"]+)\"").find(bind)?.groupValues?.get(1) ?: error("No lounge session")
        lounge(mapOf("RID" to "2", "SID" to sid, "gsessionid" to gsession, "loungeIdToken" to token), mapOf("count" to "1") + request)
    }

    /** What the TV is playing from this app, so seeks on the phone can follow. */
    private class Active(val tv: Tv, val url: String?, val lounge: String?, val stream: DirectStream?)
    @Volatile private var active: Active? = null

    /** Moves the TV to [positionMs] of the video: Lounge seekTo, DLNA Seek, or a restarted stream as a fallback. */
    suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
        val current = active ?: return@withContext
        current.lounge?.let { token -> command(token, mapOf("req0__sc" to "seekTo", "req0_newTime" to (positionMs / 1000).toString())); return@withContext }
        val stream = current.stream ?: return@withContext
        val relative = positionMs * 1000 - stream.originUs
        val seeked = stream.originUs >= 0 && relative >= 0 &&
            runCatching { Dlna.seek(current.tv.renderer!!, relative) }.isSuccess
        if (!seeked) playDirect(current.tv, current.url!!, positionMs)
    }

    /** Video position (ms) and playing state of a direct stream, as the TV reports them. */
    suspend fun directStatus(): Pair<Long, Boolean>? = withContext(Dispatchers.IO) {
        val current = active ?: return@withContext null
        val stream = current.stream?.takeIf { it.originUs >= 0 } ?: return@withContext null
        Dlna.status(current.tv.renderer ?: return@withContext null)?.let { (ms, playing) -> ms + stream.originUs / 1000 to playing }
    }

    private suspend fun loungeToken(pairing: String): String? {
        val request = Request.Builder().url("$LOUNGE/pairing/get_screen").header("Origin", "https://www.youtube.com")
            .post(FormBody.Builder().add("pairing_code", pairing).build()).build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            val screen = runCatching { json.parseToJsonElement(response.body!!.string()).jsonObject["screen"]?.jsonObject }.getOrNull()
            screen?.get("loungeToken")?.jsonPrimitive?.content
        }
    }

    private suspend fun lounge(query: Map<String, String>, form: Map<String, String>): String {
        val url = "$LOUNGE/bc/bind".toHttpUrl().newBuilder().apply {
            addQueryParameter("CVER", "1"); addQueryParameter("VER", "8")
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(url).header("Origin", "https://www.youtube.com").post(body).build()
        return http.newCall(request).await().use { response ->
            check(response.isSuccessful) { "YouTube rejected the TV session (${response.code})" }
            response.body!!.string()
        }
    }

    companion object {
        private const val YOUTUBE = "YouTube"
        /** DIAL origin: TVs accept Android package origins. */
        private const val ORIGIN = "package:dev.quietube"
        private const val NAME = "YouPoop"
        private const val LOUNGE = "https://www.youtube.com/api/lounge"
        private const val SEARCH_MS = 3_000L
        private const val DIAL = "urn:dial-multiscreen-org:service:dial:1"
        private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }
}
