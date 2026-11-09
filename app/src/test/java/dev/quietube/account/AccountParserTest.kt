package dev.quietube.account

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AccountParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("account/$name")!!.bufferedReader().use { it.readText() }
    @Test fun `legacy and modern renderers survive malformed unknown and ad items`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement(fixture("renderers.json")).jsonObject)
        assertEquals(listOf("Legacy title","Modern title"),page.videos.map { it.title })
        assertEquals("Modern creator",page.videos[1].channel)
        assertEquals(3723L,page.videos[1].durationSeconds)
        assertEquals(1_200_000L,page.videos[0].viewCount)
        assertEquals(1234L,page.videos[1].viewCount)
        assertEquals(150_000L,page.videos[0].resumePositionMs)
        assertEquals("https://i.ytimg.com/large.jpg",page.videos[0].thumbnailUrl)
        assertEquals("PLfixture",page.playlists.single().id)
        assertEquals("fixture-next",page.token)
        assertTrue(page.skipped)
    }
    @Test fun `continuation response appends playlist video renderers`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement(fixture("continuation.json")).jsonObject)
        assertEquals("History entry",page.videos.single().title)
        assertEquals("fixture-second",page.token)
    }
    @Test fun `tv history shorts tiles are videos and the grid continues`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement("""
            {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{
              "items":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO",
                "onSelectCommand":{"reelWatchEndpoint":{"videoId":"abcdefghijk","sequenceParams":"x"}},
                "header":{"tileHeaderRenderer":{"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"0:45"}}}]}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A short"}}}}}],
              "continuations":[{"nextContinuationData":{"continuation":"history-next"}}]}}}}}}}
        """).jsonObject)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",page.videos.single().url)
        assertEquals(45L,page.videos.single().durationSeconds)
        assertTrue(page.videos.single().isShort)
        assertEquals("history-next",page.token)
    }
    @Test fun `web shorts are flagged and ordinary videos are not`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement("""
            {"contents":[
              {"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Legacy short"},
                "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/shorts/aaaaaaaaaaa"}},"reelWatchEndpoint":{"videoId":"aaaaaaaaaaa"}}}},
              {"videoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Overlay short"},
                "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"0:30"},"style":"SHORTS"}}]}},
              {"lockupViewModel":{"contentId":"ccccccccccc","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Lockup short"}}},
                "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"ccccccccccc"}}}}}}},
              {"lockupViewModel":{"contentId":"ddddddddddd","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Regular video"}}},
                "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=ddddddddddd"}},
                  "watchEndpoint":{"videoId":"ddddddddddd"}}}}}}}]}
        """).jsonObject)
        assertEquals(listOf(true,true,true,false),page.videos.map { it.isShort })
    }
    @Test fun `tv continuation page reads continuationContents rather than the stub contents`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement("""
            {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{}}}},
             "continuationContents":{"gridContinuation":{
              "items":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","onSelectCommand":{"watchEndpoint":{"videoId":"bcdefghijkl"}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Older video"}}}}}],
              "continuations":[{"nextContinuationData":{"continuation":"history-third"}}]}}}
        """).jsonObject)
        assertEquals("Older video",page.videos.single().title)
        assertEquals("history-third",page.token)
    }
    @Test fun `tv playlist tiles browse to VL ids and carry their size`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement("""
            {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{"items":[
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","onSelectCommand":{"browseEndpoint":{"browseId":"VLPLfixture123"}},
                "header":{"tileHeaderRenderer":{"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"runs":[{"text":"833"},{"text":" videos"}]}}}]}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Favorite Songs"}}}}},
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","onSelectCommand":{"browseEndpoint":{"browseId":"VLWL"}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Watch later"}}}}},
              {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_CHANNEL","onSelectCommand":{"browseEndpoint":{"browseId":"UCchannel"}},
                "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A channel"}}}}}]}}}}}}}
        """).jsonObject)
        assertEquals(listOf("PLfixture123","WL"),page.playlists.map { it.id })
        assertEquals("833 videos",page.playlists[0].countText)
        assertTrue(page.videos.isEmpty())
    }
    @Test fun `captured logged out home is a legitimate empty page`() {
        val page = AccountPageParser.parse(accountJson.parseToJsonElement(fixture("captured-public-empty-home.json")).jsonObject)
        assertTrue(page.videos.isEmpty()); assertFalse(page.skipped)
    }
    @Test fun `captured client config parses after visitor data sanitization`() {
        val config = WebConfigurationParser.parse(fixture("captured-public-ytcfg.html"))
        assertEquals("fixture-visitor",config.visitor)
        assertEquals(false,config.loggedIn)
        assertTrue(config.version.isNotBlank())
    }
    @Test fun `config handles braces inside strings additional calls and secondary channel sync`() {
        val html = """<script>ytcfg.set('not-json', 'ignore {');ytcfg.set({"INNERTUBE_CLIENT_VERSION":"v1","VISITOR_DATA":"visitor}","DATASYNC_ID":"channel||user","LOGGED_IN":true});ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB"}}});</script>"""
        val config = WebConfigurationParser.parse(html)
        assertEquals("visitor}",config.visitor); assertEquals("user",config.userSessionId); assertEquals("channel",config.delegatedSessionId)
    }
    @Test(expected = IllegalArgumentException::class) fun `missing config fails safely`() { WebConfigurationParser.parse("<html>Sign in</html>") }
}
