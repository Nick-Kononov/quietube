package dev.quietube.account.oauth

import dev.quietube.account.AccountPageParser
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceCodeTest {
    private val config = TvClientConfiguration("fixture.apps.googleusercontent.com","FAKE_CLIENT_SECRET","7.fixture","FAKE_VISITOR")
    private fun json(text: String) = oauthJson.parseToJsonElement(text).jsonObject
    @Test fun `pending slow down denial and expiry map without exposing response data`() {
        assertEquals(DevicePoll.Pending,DeviceCodeParser.poll(json("""{"error":"authorization_pending"}"""),config,0))
        assertEquals(DevicePoll.SlowDown,DeviceCodeParser.poll(json("""{"error":"slow_down"}"""),config,0))
        assertEquals(DevicePoll.Denied,DeviceCodeParser.poll(json("""{"error":"access_denied"}"""),config,0))
        assertEquals(DevicePoll.Expired,DeviceCodeParser.poll(json("""{"error":"expired_token"}"""),config,0))
    }
    @Test fun `device code lifetime and minimum polling interval are enforced`() {
        val login = DeviceCodeParser.login(json("""{"device_code":"FAKE_DEVICE","user_code":"TEST-CODE","verification_url":"https://www.google.com/device","expires_in":600,"interval":2}"""),config,1000)
        assertEquals(601000L,login.expiresAt); assertEquals(5L,login.intervalSeconds)
        assertFalse(login.toString().contains("FAKE_DEVICE")); assertFalse(login.toString().contains("TEST-CODE"))
    }
    @Test fun `refresh keeps previous refresh token if server omits it`() {
        val result = DeviceCodeParser.poll(json("""{"access_token":"FAKE_ACCESS","token_type":"Bearer","expires_in":3600}"""),config,1000,"FAKE_REFRESH") as DevicePoll.Granted
        assertEquals("FAKE_REFRESH",result.session.refreshToken); assertEquals(3601000L,result.session.expiresAt)
        assertFalse(result.session.toString().contains("FAKE_ACCESS")); assertFalse(config.toString().contains("FAKE_CLIENT_SECRET"))
    }
    @Test fun `token record round trip preserves issuing client for automatic refresh`() {
        val session = DeviceSession("FAKE_ACCESS","FAKE_REFRESH",123456L,config)
        val restored = DeviceSession.decode(session.encoded())
        assertEquals("FAKE_REFRESH",restored.refreshToken); assertEquals(config.clientId,restored.configuration.clientId)
        assertEquals(config.clientSecret,restored.configuration.clientSecret)
    }
    @Test(expected = IllegalArgumentException::class) fun `untrusted activation callback cannot receive user code`() {
        DeviceCodeParser.login(json("""{"device_code":"FAKE","user_code":"TEST-CODE","verification_url":"https://google.com.evil.test/device"}"""),config,0)
    }
    @Test fun `activation destinations reject insecure ports userinfo and unrelated pages`() {
        assertTrue(DeviceCodeParser.allowedActivationUrl("https://www.google.com/device"))
        assertTrue(DeviceCodeParser.allowedActivationUrl("https://youtube.com/activate"))
        assertFalse(DeviceCodeParser.allowedActivationUrl("http://www.google.com/device"))
        assertFalse(DeviceCodeParser.allowedActivationUrl("https://www.google.com:8443/device"))
        assertFalse(DeviceCodeParser.allowedActivationUrl("https://user@www.google.com/device"))
        assertFalse(DeviceCodeParser.allowedActivationUrl("https://www.google.com/other"))
    }
    @Test fun `published configuration parses without hardcoded client credentials or versions`() {
        val html = """<script id="base-js" src="/s/client/m=base"></script><script>{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"FAKE_VISITOR"}</script>"""
        assertEquals("https://www.youtube.com/s/client/m=base",TvConfigurationParser.scriptPath(html))
        val parsed = TvConfigurationParser.parse(html,"""clientId:"fixture.apps.googleusercontent.com",field:"FAKE_CLIENT_SECRET"""" )
        assertEquals("7.fixture",parsed.version); assertEquals("FAKE_CLIENT_SECRET",parsed.clientSecret)
    }
    @Test(expected = IllegalArgumentException::class) fun `script configuration rejects external host`() {
        TvConfigurationParser.scriptPath("""<script id="base-js" src="https://evil.test/s/client/m=base"></script>""")
    }
    @Test fun `token destinations keep bearer away from player CDNs and sign in pages`() {
        assertTrue(allowedTvDestination("https","www.youtube.com","/o/oauth2/token"))
        assertTrue(allowedTvDestination("https","www.youtube.com","/youtubei/v1/browse"))
        assertFalse(allowedTvDestination("https","googlevideo.com","/youtubei/v1/browse"))
        assertFalse(allowedTvDestination("https","www.youtube.com","/watch"))
        assertFalse(allowedTvDestination("http","www.youtube.com","/o/oauth2/token"))
        // Watch-history pings carry the bearer so YouTube credits the account.
        assertTrue(allowedTvDestination("https","www.youtube.com","/api/stats/watchtime"))
        assertFalse(allowedTvDestination("https","s.youtube.com","/api/stats/watchtime"))
        assertFalse(allowedTvDestination("https","www.youtube.com","/api/stats/qoe"))
    }
    @Test fun `TV tiles parse video metadata progress and continuation and skip navigation tiles`() {
        val text = javaClass.classLoader!!.getResourceAsStream("account/oauth/tv-tile.json")!!.bufferedReader().use { it.readText() }
        val page = AccountPageParser.parse(json(text))
        assertEquals("TV fixture video",page.videos.single().title)
        assertEquals("Fixture creator",page.videos.single().channel)
        assertEquals(200L,page.videos.single().durationSeconds)
        assertEquals(20_000L,page.videos.single().resumePositionMs)
        assertEquals(12000L,page.videos.single().viewCount)
        assertEquals("tv-next-fixture",page.token); assertFalse(page.skipped)
    }
}
