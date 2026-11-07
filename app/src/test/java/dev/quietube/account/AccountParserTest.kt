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
