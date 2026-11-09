package dev.quietube.account

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Like execute(), but cancelling the coroutine cancels the HTTP call. A blocking execute() would keep
 * running, holding the request lock, so a newly selected feed waits behind an abandoned one.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, value, _ -> value.close() }
    })
}
