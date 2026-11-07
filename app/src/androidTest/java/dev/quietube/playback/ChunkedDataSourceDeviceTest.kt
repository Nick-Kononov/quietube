@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.quietube.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietube.data.ExtractorDownloader
import dev.quietube.data.NewPipeAnonymousSource
import dev.quietube.data.Outcome
import dev.quietube.data.StreamSelector
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory tests make no network requests. The live probe runs only with `-e live true`. */
@RunWith(AndroidJUnit4::class)
class ChunkedDataSourceDeviceTest {
    private val content = ByteArray(2_500) { (it % 251).toByte() }
    private val ranges = mutableListOf<LongRange>()
    private val requestedLengths = mutableListOf<Long>()
    /** Records each requested upstream range; ByteArrayDataSource serves position/length like a Range request. */
    private inner class Recording(private val inner: ByteArrayDataSource = ByteArrayDataSource(content)) : DataSource by inner {
        override fun open(dataSpec: DataSpec): Long {
            val opened = inner.open(dataSpec)
            ranges += dataSpec.position until dataSpec.position + opened
            requestedLengths += dataSpec.length
            return opened
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = inner.read(buffer, offset, length)
        override fun close() = inner.close()
    }
    private fun source(advertised: Long?, chunk: Long = 1_000) = ChunkedDataSource(Recording(), chunk) { advertised }
    private fun readAll(source: DataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val opened = source.open(spec)
        val out = ByteArrayOutputStream(); val buffer = ByteArray(333)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, n) }
        source.close()
        return opened to out.toByteArray()
    }
    private val uri = Uri.parse("https://rr1---sn-x.googlevideo.com/videoplayback")

    @Test fun openEndedReadIsSplitIntoBoundedRangesAndReassembled() {
        val (opened, bytes) = readAll(source(2_500), DataSpec.Builder().setUri(uri).setPosition(700).build())
        assertEquals(1_800L, opened)
        assertArrayEquals(content.copyOfRange(700, 2_500), bytes)
        assertEquals(listOf(700L..1_699L, 1_700L..2_499L), ranges)
    }
    @Test fun contentShorterThanAdvertisedEndsCleanly() {
        val (_, bytes) = readAll(source(3_200), DataSpec(uri))
        assertArrayEquals(content, bytes)
        // The third request asks for a full chunk; the source has only 500 bytes left and reading stops there.
        assertEquals(listOf(0L..999L, 1_000L..1_999L, 2_000L..2_999L), ranges)
    }
    @Test fun unknownSizePassesThroughOpenEnded() {
        assertArrayEquals(content, readAll(source(null), DataSpec(uri)).second)
        assertEquals(listOf(C.LENGTH_UNSET.toLong()), requestedLengths)
    }
    @Test fun streamsSmallerThanOneChunkStillUseBoundedRanges() {
        val (opened, bytes) = readAll(source(2_500, chunk = 10_000), DataSpec(uri))
        assertEquals(2_500L, opened); assertArrayEquals(content, bytes)
        assertArrayEquals(content.copyOfRange(100, 600), readAll(source(2_500), DataSpec.Builder().setUri(uri).setPosition(100).setLength(500).build()).second)
        assertEquals(listOf(2_500L, 500L), requestedLengths)
    }
    @Test fun onlyProgressiveGooglevideoUrlsAdvertiseLength() {
        assertEquals(1234L, youtubeContentLength(Uri.parse("https://rr1---sn-x.googlevideo.com/videoplayback?itag=299&clen=1234")))
        assertNull(youtubeContentLength(Uri.parse("https://rr1---sn-x.googlevideo.com/videoplayback?itag=299")))
        assertNull(youtubeContentLength(Uri.parse("https://example.com/videoplayback?clen=1234")))
        assertNull(youtubeContentLength(Uri.parse("http://rr1---sn-x.googlevideo.com/videoplayback?clen=1234")))
    }

    @Test fun liveThroughputOnThisDevice() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val http = OkHttpClient()
        val details = (NewPipeAnonymousSource(ExtractorDownloader(http)).playback("https://www.youtube.com/watch?v=aqz-KE-bpKQ") as Outcome.Success).value
        val stream = StreamSelector.select(details, 1080)!!.video
        val factory = OkHttpDataSource.Factory(http).setUserAgent(ExtractorDownloader.USER_AGENT)
        fun measure(label: String, source: DataSource): Double {
            val limit = 24L * 1024 * 1024; val buffer = ByteArray(64 * 1024); var total = 0L
            val start = SystemClock.elapsedRealtime()
            source.open(DataSpec(Uri.parse(stream.url)))
            while (total < limit && SystemClock.elapsedRealtime() - start < 20_000) {
                val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; total += n
            }
            source.close()
            val mbps = total * 8.0 / ((SystemClock.elapsedRealtime() - start) / 1000.0) / 1e6
            Log.i("QuietubePerf", "probe $label height=${stream.height} stream_mbps=${stream.bitrate / 1e6} read_mbps=$mbps bytes=$total")
            return mbps
        }
        val plain = measure("open-ended", factory.createDataSource())
        val chunked = measure("chunked", ChunkedDataSource(factory.createDataSource()))
        assertTrue("chunked=$chunked plain=$plain", chunked > stream.bitrate / 1e6)
    }
}
