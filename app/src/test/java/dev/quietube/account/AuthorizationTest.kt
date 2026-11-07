package dev.quietube.account
import org.junit.Assert.*
import org.junit.Test
class AuthorizationTest {
    @Test fun `legacy hash matches independent SHA1 fixture`() {
        assertEquals("SAPISIDHASH 1700000000_0483023a5bfb982b022e6fa5bf626ee8040b896c", YouTubeAuthorization.generate(SessionCookies.fromHeader("SAPISID=primary; LOGIN_INFO=fake"),1700000000))
    }
    @Test fun `modern three scheme hashes include user session suffix`() {
        assertEquals("SAPISIDHASH 1700000000_3f68f01cdb71bce857a3980022a0ff5bd40ed3de_u SAPISID1PHASH 1700000000_0476d2c48b6ed418bbf859fa714e037e08b27ec4_u SAPISID3PHASH 1700000000_79569503700e5cf781d5d953d5eac832ea06d1d9_u", YouTubeAuthorization.generate(SessionCookies.fromHeader("SAPISID=primary; __Secure-1PAPISID=first; __Secure-3PAPISID=third; LOGIN_INFO=fake"),1700000000,"12345"))
    }
    @Test fun `missing SAPISID uses third party cookie for both schemes`() {
        assertEquals("SAPISIDHASH 1700000000_087409a51eb3ce9f7cb1463013c16bb280321850 SAPISID3PHASH 1700000000_087409a51eb3ce9f7cb1463013c16bb280321850", YouTubeAuthorization.generate(SessionCookies.fromHeader("__Secure-3PAPISID=third; LOGIN_INFO=fake"),1700000000))
    }
    @Test fun `credentials can only go to exact secure YouTube account destinations`() {
        assertTrue(allowedAccountUrl("https","www.youtube.com","/"))
        assertTrue(allowedAccountUrl("https","www.youtube.com","/youtubei/v1/browse"))
        assertFalse(allowedAccountUrl("http","www.youtube.com","/youtubei/v1/browse"))
        assertFalse(allowedAccountUrl("https","www.youtube.com.evil.test","/youtubei/v1/browse"))
        assertFalse(allowedAccountUrl("https","redirect.googlevideo.com","/"))
        assertFalse(allowedAccountUrl("https","www.youtube.com","/watch"))
    }
}
