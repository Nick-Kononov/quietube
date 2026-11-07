package dev.quietube.playback

import dev.quietube.data.*
import org.junit.Assert.*
import org.junit.Test

class StreamSelectorTest {
    private val video = Video("https://www.youtube.com/watch?v=test", "Test", "Channel", null, 100, 1, null)
    private fun stream(height: Int, split: Boolean = true, codec: String = "avc1") =
        StreamVariant("https://example.invalid/$height/$split/$codec", "video/mp4", height, videoOnly = split, codec = codec)
    private val audio = StreamVariant("https://example.invalid/audio", "audio/mp4", bitrate = 128)
    @Test fun `wifi chooses 1080 and merges audio`() {
        val details = PlaybackDetails(video, listOf(stream(2160), stream(1080), stream(720)), listOf(audio))
        val selected = StreamSelector.select(details, 1080)!!
        assertEquals(1080, selected.video.height)
        assertEquals(audio, selected.audio)
    }
    @Test fun `mobile cap and manual quality select available resolution`() {
        val details = PlaybackDetails(video, listOf(stream(2160), stream(1080), stream(720), stream(360)), listOf(audio))
        assertEquals(720, StreamSelector.select(details, 720)!!.video.height)
        assertEquals(2160, StreamSelector.select(details, 2160)!!.video.height)
        assertEquals(360, StreamSelector.select(details, 480)!!.video.height)
        assertEquals(listOf(2160,1080,720,360), StreamSelector.qualities(details))
    }
    @Test fun `missing audio falls back to muxed and never silent video`() {
        val details = PlaybackDetails(video, listOf(stream(1080), stream(720, false)), emptyList())
        assertEquals(720, StreamSelector.select(details, 1080)!!.video.height)
        assertNull(StreamSelector.select(details, 1080)!!.audio)
        assertNull(StreamSelector.select(details.copy(videoStreams = listOf(stream(1080))), 1080))
    }
    @Test fun `adaptive beats muxed at same quality and avc is preferred`() {
        val details = PlaybackDetails(video, listOf(stream(1080, false), stream(1080, true, "av01"), stream(1080)), listOf(audio))
        val selected = StreamSelector.select(details, 1080)!!
        assertTrue(selected.video.videoOnly)
        assertEquals("avc1", selected.video.codec)
    }
    @Test fun `live prefers HLS and on demand can fall back to manifest`() {
        val details = PlaybackDetails(video.copy(isLive = true), listOf(stream(720, false)), emptyList(), hlsUrl = "https://example.invalid/live.m3u8")
        assertEquals(Delivery.Hls, StreamSelector.select(details, 1080)!!.video.delivery)
        assertEquals(Delivery.Dash, StreamSelector.select(PlaybackDetails(video, emptyList(), emptyList(), dashUrl = "https://example.invalid/v.mpd"),1080)!!.video.delivery)
    }
    @Test fun `when all variants exceed cap choose lowest not highest`() {
        val details = PlaybackDetails(video, listOf(stream(2160), stream(1440)), listOf(audio))
        assertEquals(1440, StreamSelector.select(details, 720)!!.video.height)
    }
    @Test fun `Russian original wins over higher bitrate English dub and unknown tracks`() {
        val original = audio.copy(url = "https://example.invalid/original", mimeType = "audio/webm",
            bitrate = 48, audioRole = AudioRole.Original, audioLanguage = "ru")
        val dubbed = audio.copy(bitrate = 256, audioRole = AudioRole.Dubbed, audioLanguage = "en")
        val details = PlaybackDetails(video, listOf(stream(1080)), listOf(dubbed, audio, original))
        assertEquals(original, StreamSelector.select(details, 1080)!!.audio)
        assertEquals(original, StreamSelector.select(details.copy(audioStreams = details.audioStreams.reversed()), 1080)!!.audio)
    }
    @Test fun `original English wins over Russian dub without hardcoded language preference`() {
        val original = audio.copy(audioRole = AudioRole.Original, audioLanguage = "en")
        val dub = audio.copy(audioRole = AudioRole.Dubbed, audioLanguage = "ru", bitrate = 300)
        assertEquals(original, StreamSelector.selectAudio(PlaybackDetails(video, emptyList(), listOf(dub, original))))
    }
    @Test fun `legacy unlabelled audio stays playable and ranks before known dubs`() {
        val dub = audio.copy(audioRole = AudioRole.Dubbed, bitrate = 300)
        assertEquals(audio, StreamSelector.selectAudio(PlaybackDetails(video, emptyList(), listOf(dub, audio))))
        assertEquals(audio, StreamSelector.selectAudio(PlaybackDetails(video, emptyList(), listOf(audio))))
        assertEquals(dub, StreamSelector.selectAudio(PlaybackDetails(video, emptyList(), listOf(dub))))
    }
    @Test fun `quality fallback preserves original audio rather than muxed localized audio`() {
        val original = audio.copy(audioRole = AudioRole.Original, audioLanguage = "ru")
        val details = PlaybackDetails(video, listOf(stream(1080, false), stream(720)), listOf(original))
        assertEquals(720, StreamSelector.select(details, 1080)!!.video.height)
        assertEquals(original, StreamSelector.select(details, 1080)!!.audio)
        assertEquals(listOf(720), StreamSelector.qualities(details))
    }
}
