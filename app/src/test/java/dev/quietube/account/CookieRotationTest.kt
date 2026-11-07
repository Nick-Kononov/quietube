package dev.quietube.account

import org.junit.Assert.*
import org.junit.Test

class CookieRotationTest {
    private fun session() = SessionCookies.fromHeader("SAPISID=fake-primary; LOGIN_INFO=fake-login; SID=fake-sid")
    @Test fun `response rotates auth cookies and preserves unrelated session values`() {
        val updated = session().updated(listOf("SAPISID=fake-rotated; Domain=.youtube.com; Path=/; Secure; HttpOnly", "__Secure-3PAPISID=fake-third; Path=/; Secure"))
        assertEquals("fake-rotated",updated.value("SAPISID")); assertEquals("fake-third",updated.value("__Secure-3PAPISID"))
        assertEquals("fake-sid",updated.value("SID")); assertTrue(updated.usable())
    }
    @Test fun `foreign domains and non root paths cannot overwrite session`() {
        val original = session()
        val updated = original.updated(listOf("SAPISID=bad; Domain=google.com; Path=/", "SAPISID=bad; Domain=youtube.com.evil.test; Path=/", "SAPISID=bad; Path=/other"))
        assertTrue(original.sameContents(updated))
    }
    @Test fun `server deletion invalidates authentication rather than retaining stale cookie`() {
        val updated = session().updated(listOf("LOGIN_INFO=; Domain=.youtube.com; Path=/; Max-Age=0"))
        assertNull(updated.value("LOGIN_INFO")); assertFalse(updated.usable()); assertFalse(updated.header().contains("LOGIN_INFO"))
    }
    @Test fun `expiry metadata survives encrypted record encoding and prevents expired headers`() {
        val start = System.currentTimeMillis()
        val updated = session().updated(listOf("SAPISID=fake-new; Domain=.youtube.com; Path=/; Max-Age=60"),start)
        val restored = SessionCookies.decoded(updated.encoded())
        assertEquals("fake-new",restored.value("SAPISID",start+1000))
        assertNull(restored.value("SAPISID",start+120_000)); assertFalse(restored.header(start+120_000).contains("SAPISID="))
        assertEquals("fake-login",restored.value("LOGIN_INFO",start+120_000))
    }
    @Test fun `legacy encrypted header payload still migrates`() {
        val restored = SessionCookies.decoded("SAPISID=fake-primary; LOGIN_INFO=fake-login")
        assertTrue(restored.usable()); assertTrue(restored.encoded().startsWith("{"))
    }
    @Test fun `cookie import preserves expiry after persistence`() {
        val now = System.currentTimeMillis()/1000
        val text = ".youtube.com\tTRUE\t/\tTRUE\t${now+60}\tSAPISID\tfake-primary\n.youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tfake-login"
        val restored = SessionCookies.decoded(SessionCookies.fromNetscape(text).encoded())
        assertTrue(restored.usable((now+10)*1000)); assertFalse(restored.usable((now+120)*1000))
    }
    @Test fun `malformed cookies and cookie attributes are not imported as values`() {
        val original = session()
        val updated = original.updated(listOf("not-a-cookie", "SAPISID=bad value; Path=/", "SID=bad; Path=/elsewhere"))
        assertTrue(original.sameContents(updated)); assertNull(updated.value("Path")); assertNull(updated.value("Secure"))
    }
    @Test fun `unchanged response does not change stored record`() {
        val original = session()
        assertTrue(original.sameContents(original.updated(emptyList())))
    }
}
