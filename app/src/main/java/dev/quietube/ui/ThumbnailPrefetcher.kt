package dev.quietube.ui

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.data.NetworkStatus
import dev.quietube.data.Video
import javax.inject.Inject
import javax.inject.Singleton

data class ThumbnailRequest(val url: String,val width: Int,val height: Int)

@Singleton
class ThumbnailPrefetcher @Inject constructor(@ApplicationContext context: Context,network: NetworkStatus) {
    private val queue = ThumbnailQueue(network::onUnmeteredWifi) { request ->
        val disposable = SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(request.url)
            .size(request.width,request.height).scale(Scale.FILL).build())
        val cancel: () -> Unit = { disposable.dispose() }
        cancel
    }
    fun submit(requests: List<ThumbnailRequest>) = queue.submit(requests)
}

internal class ThumbnailQueue(private val allowed: () -> Boolean,private val enqueue: (ThumbnailRequest) -> (() -> Unit)) {
    private val pending = mutableMapOf<ThumbnailRequest,() -> Unit>()
    fun submit(requests: List<ThumbnailRequest>) {
        val wanted = if (requests.isEmpty() || !allowed()) emptySet() else requests.distinct().take(3).toSet()
        pending.keys.filter { it !in wanted }.forEach { pending.remove(it)?.invoke() }
        wanted.filter { it !in pending }.forEach { pending[it] = enqueue(it) }
    }
}

internal fun thumbnailLookAhead(videos: List<Video>,visibleUrls: List<String>,width: Int): List<ThumbnailRequest> {
    if (width <= 0) return emptyList()
    val visible = visibleUrls.toSet()
    val indices = videos.indices.filter { videos[it].url in visible }
    if (indices.isEmpty()) return emptyList()
    val candidates = videos.drop(indices.last()+1).take(2) + videos.take(indices.first()).takeLast(1)
    return candidates.mapNotNull { video -> (highResThumbnail(video.url) ?: video.thumbnailUrl)?.let {
        ThumbnailRequest(it,width,(width*9/16).coerceAtLeast(1))
    } }.distinct()
}
