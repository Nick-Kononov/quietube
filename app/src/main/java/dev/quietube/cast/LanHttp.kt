package dev.quietube.cast

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Minimal HTTP/1.1 for DIAL, which is plain HTTP on the local network by design. The app keeps cleartext disabled
 * for everything else (usesCleartextTraffic="false", which OkHttp enforces), so these few requests use a socket
 * and are confined to private IPv4 addresses.
 */
internal object LanHttp {
    class Response(val code: Int, val headers: Map<String, String>, val body: String)

    private const val MAX_BYTES = 64 * 1024

    fun isPrivate(address: InetAddress) = address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress)

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response {
        val uri = URI(url)
        require(uri.scheme == "http") { "DIAL uses http" }
        val host = InetAddress.getByName(uri.host)
        require(isPrivate(host)) { "Not a local network address" }
        val port = if (uri.port == -1) 80 else uri.port
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val payload = body?.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3_000)
            socket.soTimeout = 5_000
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nConnection: close\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                if (payload != null) append("Content-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); payload?.let(::write); flush() }
            val raw = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (raw.size() < MAX_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                raw.write(buffer, 0, read)
            }
            val text = raw.toString(Charsets.UTF_8.name())
            val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.length
            val lines = text.substring(0, split).split("\r\n")
            val code = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("Malformed response")
            val parsed = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            return Response(code, parsed, text.substring(minOf(split + 4, text.length)))
        }
    }
}
