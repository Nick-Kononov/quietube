package dev.quietube.data

import java.io.IOException
import javax.inject.Inject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class HttpFailure(val status: Int) : IOException("HTTP $status")

/** Dedicated, cookie-less client. No logging interceptor or account cookie jar. */
class ExtractorDownloader @Inject constructor(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") byteArrayOf().toRequestBody() else null
        return client.newCall(builder.method(method, body).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            Response(response.code, response.message, response.headers.toMultimap(),
                response.body?.string().orEmpty(), response.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
    }
}
