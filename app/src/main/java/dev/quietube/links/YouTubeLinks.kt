package dev.quietube.links

import java.net.URI
import java.net.URLDecoder

private val videoId = Regex("[A-Za-z0-9_-]{11}")
private val timestamp = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
private fun seconds(value: String): Long? {
    if (value.matches(Regex("[0-9]+"))) return value.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 1000 }
    val match = timestamp.matchEntire(value)?.takeIf { value.isNotEmpty() } ?: return null
    return try {
        match.groupValues.drop(1).zip(listOf(3600L, 60L, 1L)).fold(0L) { sum, (part, unit) ->
            Math.addExact(sum, Math.multiplyExact(if (part.isEmpty()) 0 else part.toLong(), unit))
        }.takeIf { it <= Long.MAX_VALUE / 1000 }
    } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
}
fun parseYouTubeLink(value: String): String? = try {
    val uri = URI(value.trim())
    val host = uri.host?.lowercase()
    if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null || uri.port != -1 ||
        host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")) null
    else {
        val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val path = uri.rawPath.orEmpty()
        val id = when {
            host == "youtu.be" -> path.removePrefix("/")
            path == "/watch" -> params.singleOrNull { it.first == "v" }?.second
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/")
            path.startsWith("/live/") -> path.removePrefix("/live/")
            else -> null
        }
        val times = params.filter { it.first in setOf("t", "start") }
        val rawTime = times.singleOrNull()?.second ?: uri.rawFragment?.takeIf { it.startsWith("t=") }?.removePrefix("t=")
        val time = rawTime?.let(::seconds)
        if (id == null || !videoId.matches(id) || times.size > 1 || (rawTime != null && time == null)) null
        else "https://www.youtube.com/watch?v=$id" + (time?.let { "&t=$it" } ?: "")
    }
} catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null }

fun sharedYouTubeLink(text: String): String? = Regex("https://[^\\s<>\"\\u0000-\\u001f]+", RegexOption.IGNORE_CASE)
    .findAll(text).mapNotNull { parseYouTubeLink(it.value.trimEnd('.', ',', ')', ']', '}', '!', ';')) }.firstOrNull()
internal fun linkStartMs(canonical: String): Long? = canonical.substringAfter("&t=", "").toLongOrNull()?.let { Math.multiplyExact(it, 1000) }
