package dev.quietube.account.actions

import dev.quietube.data.Rating
import dev.quietube.playback.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActionParserTest {
    private fun fixture(name: String) = Json.parseToJsonElement(javaClass.getResource("/account/actions/$name.json")!!.readText()).jsonObject
    @Test fun tvMetadataAndAlternateSubscriptionAreParsed() {
        val info = ActionParser.engagement(fixture("player"),fixture("metadata"))
        assertEquals(Rating.Like,info.rating); assertEquals("123K",info.likes)
        assertEquals(true,info.subscribed); assertNull(info.dislikes)
        assertEquals("UCabcdefghijklmnopqrstuv",info.channelId)
    }
    @Test fun tvTransportLikeCountDoesNotNeedAnActionEndpoint() {
        val next = Json.parseToJsonElement("""{"transportControlsRenderer":{"likeButton":{"toggleButtonRenderer":{"defaultText":{"simpleText":"12.3K"}}}}}""").jsonObject
        assertEquals("12.3K",ActionParser.engagement(fixture("player"),next).likes)
    }
    @Test fun unknownFieldsAndMissingStatesStayUnknown() {
        val info = ActionParser.engagement(JsonObject(emptyMap()),buildJsonObject { put("unrelated",true) })
        assertNull(info.rating); assertNull(info.subscribed); assertNull(info.channelId)
    }
    @Test fun unrelatedChannelSubscriptionIsIgnored() {
        val next = Json.parseToJsonElement("""{"subscribeButtonRenderer":{"channelId":"UCzzzzzzzzzzzzzzzzzzzzzz","subscribed":true}}""").jsonObject
        assertNull(ActionParser.engagement(fixture("player"),next).subscribed)
    }
    @Test fun explicitFalseSubscriptionRemainsFalse() {
        val next = Json.parseToJsonElement("""{"subscribeButtonRenderer":{"channelId":"UCabcdefghijklmnopqrstuv","subscribed":false}}""").jsonObject
        assertEquals(false,ActionParser.engagement(fixture("player"),next).subscribed)
    }
    @Test fun trackingKeepsSignedFieldsAndRealIntervals() {
        val urls = ActionParser.tracking(fixture("player"))
        val url = ActionParser.trackingUrl(urls.second,"abcdefghijklmnop",54.5,listOf(0.0 to 4.5,50.0 to 54.5))
        assertEquals("fixture",url.queryParameter("vm")); assertEquals("fixture",url.queryParameter("of"))
        assertEquals("0.000,50.000",url.queryParameter("st")); assertEquals("4.500,54.500",url.queryParameter("et"))
        assertEquals("54.500",url.queryParameter("cmt")); assertNull(url.queryParameter("final"))
        assertEquals("detailpage",url.queryParameter("el"))
    }
    @Test fun untrustedTrackingUrlRejected() {
        for (url in listOf("http://www.youtube.com/api/stats/playback","https://evil.example/api/stats/playback","https://www.youtube.com:444/api/stats/playback","https://www.youtube.com/youtubei/v1/browse")) {
            val player = Json.parseToJsonElement(fixture("player").toString().replace("https://s.youtube.com/api/stats/playback",url)).jsonObject
            assertThrows(IllegalArgumentException::class.java) { ActionParser.tracking(player) }
        }
    }
    @Test fun invalidIntervalsRejected() {
        val base = ActionParser.tracking(fixture("player")).second
        assertThrows(IllegalArgumentException::class.java) { ActionParser.trackingUrl(base,"abcdefghijklmnop",Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ActionParser.trackingUrl(base,"abcdefghijklmnop",2.0,listOf(10.0 to 2.0)) }
    }
    @Test fun emptyOrRejectedActionsAreNotSuccess() {
        for (body in listOf("{}", "{\"responseContext\":{},\"error\":{}}", "{\"responseContext\":{},\"actions\":[{\"status\":\"STATUS_FAILED\"}]}"))
            assertThrows(IllegalArgumentException::class.java) { ActionParser.requireAccepted(Json.parseToJsonElement(body).jsonObject) }
        ActionParser.requireAccepted(Json.parseToJsonElement("{\"responseContext\":{\"visitorData\":\"fixture\"}}").jsonObject)
    }
    @Test fun malformedVideoIdRejected() { assertThrows(IllegalArgumentException::class.java) { ActionParser.videoId("https://www.youtube.com/watch?v=bad") } }
    private fun sample(position: Double,time: Long,playing: Boolean = true,seek: Boolean = false,revision: Long = 1,enabled: Boolean = true) = WatchSample("video",position,playing,time,revision,enabled,seek)
    @Test fun contiguousPlayingIntervalsMerge() {
        val intervals = WatchedIntervals()
        intervals.sample(sample(0.0,0)); intervals.sample(sample(1.0,1000)); intervals.sample(sample(2.0,2000,false))
        assertEquals(listOf(0.0 to 2.0),intervals.drain()); assertTrue(intervals.drain().isEmpty())
    }
    @Test fun seeksDoNotMarkSkippedRangesWatched() {
        val intervals = WatchedIntervals()
        intervals.sample(sample(0.0,0)); intervals.sample(sample(1.0,1000)); intervals.sample(sample(90.0,1500,seek = true)); intervals.sample(sample(91.0,2500))
        assertEquals(listOf(0.0 to 1.0,90.0 to 91.0),intervals.drain())
    }
    @Test fun pausesAndBufferingDoNotAccumulate() {
        val intervals = WatchedIntervals()
        intervals.sample(sample(0.0,0,false)); intervals.sample(sample(5.0,10000,false)); intervals.sample(sample(5.0,15000)); intervals.sample(sample(6.0,16000))
        assertEquals(listOf(5.0 to 6.0),intervals.drain())
    }
    @Test fun revisionAndDisabledSamplesNeverBridge() {
        val intervals = WatchedIntervals()
        intervals.sample(sample(0.0,0)); intervals.sample(sample(1.0,1000,revision = 2)); intervals.sample(sample(2.0,2000,revision = 2,enabled = false)); intervals.sample(sample(3.0,3000,revision = 2))
        assertTrue(intervals.drain().isEmpty())
    }
    @Test fun backwardSeekDoesNotMergeSeparatePlayback() {
        val intervals = WatchedIntervals()
        intervals.sample(sample(10.0,0)); intervals.sample(sample(11.0,1000)); intervals.sample(sample(2.0,1500,seek = true)); intervals.sample(sample(3.0,2500))
        assertEquals(listOf(10.0 to 11.0,2.0 to 3.0),intervals.drain())
    }
}
