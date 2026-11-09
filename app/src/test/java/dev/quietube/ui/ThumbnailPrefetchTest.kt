package dev.quietube.ui

import dev.quietube.data.Video
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPrefetchTest {
    private fun video(index: Int) = Video("https://www.youtube.com/watch?v=${index.toString().padStart(11,'0')}","Video $index","Channel",null,60,1,null)
    @Test fun `look ahead warms two upcoming covers and one previous cover excluding visible items`() {
        val videos = (0..9).map(::video)
        val requests = thumbnailLookAhead(videos,listOf("header",videos[3].url,videos[4].url),960)
        assertEquals(listOf(5,6,2).map { highResThumbnail(videos[it].url) },requests.map { it.url })
        assertTrue(requests.all { it.width == 960 && it.height == 540 })
    }
    @Test fun `empty skeleton and end of feed cannot queue unrelated covers`() {
        val videos = (0..2).map(::video)
        assertTrue(thumbnailLookAhead(videos,listOf("skeleton","footer"),960).isEmpty())
        assertTrue(thumbnailLookAhead(videos,listOf(videos.first().url),0).isEmpty())
        assertEquals(listOf(highResThumbnail(videos[1].url)),thumbnailLookAhead(videos,listOf(videos.last().url),960).map { it.url })
    }
    @Test fun `covers without a watch id use their supplied image and missing images are skipped`() {
        val videos = listOf(video(0),video(1).copy(url = "unmapped",thumbnailUrl = "https://i.ytimg.com/fixture.jpg"),video(2).copy(url = "missing"))
        assertEquals(listOf("https://i.ytimg.com/fixture.jpg"),thumbnailLookAhead(videos,listOf(videos[0].url),960).map { it.url })
    }
    @Test fun `scrolling retains overlapping work and cancels only covers no longer wanted`() {
        val started = mutableListOf<ThumbnailRequest>()
        val cancelled = mutableListOf<ThumbnailRequest>()
        val queue = ThumbnailQueue({ true }) { request -> started += request; { cancelled += request } }
        val a = ThumbnailRequest("a",960,540); val b = a.copy(url = "b"); val c = a.copy(url = "c")
        queue.submit(listOf(a,b)); queue.submit(listOf(b,c)); queue.submit(listOf(b,c))
        assertEquals(listOf(a,b,c),started)
        assertEquals(listOf(a),cancelled)
        queue.submit(emptyList())
        assertEquals(setOf(a,b,c),cancelled.toSet())
    }
    @Test fun `unmetered wifi is required and losing it cancels speculative work`() {
        var allowed = false
        var started = 0; var cancelled = 0
        val queue = ThumbnailQueue({ allowed }) { started++; { cancelled++ } }
        val request = ThumbnailRequest("a",960,540)
        queue.submit(listOf(request)); assertEquals(0,started)
        allowed = true; queue.submit(listOf(request)); assertEquals(1,started)
        allowed = false; queue.submit(listOf(request)); assertEquals(1,cancelled)
    }
    @Test fun `queue is bounded deduplicated and resizing replaces the decoded request`() {
        val started = mutableListOf<ThumbnailRequest>(); val cancelled = mutableListOf<ThumbnailRequest>()
        val queue = ThumbnailQueue({ true }) { request -> started += request; { cancelled += request } }
        val requests = (0..9).map { ThumbnailRequest("image-$it",960,540) }
        queue.submit(listOf(requests[0])+requests)
        assertEquals(3,started.size)
        queue.submit(listOf(requests[0].copy(width = 480,height = 270)))
        assertEquals(4,started.size)
        assertEquals(3,cancelled.size)
    }
}
