package dev.quietube.account

import org.junit.Assert.*
import org.junit.Test

class SessionCookiesTest {
    private fun row(domain: String, name: String, value: String, expiry: Long = 0) = "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
    @Test fun `Netscape import supports HttpOnly and secure cookies without unrelated domains`() {
        val cookies = SessionCookies.fromNetscape(listOf("# Netscape HTTP Cookie File", row("#HttpOnly_.youtube.com", "SAPISID", "fake-primary"),
            row(".youtube.com", "LOGIN_INFO", "fake-login"), row("www.youtube.com", "__Secure-3PAPISID", "fake-third"),
            row(".google.com", "SID", "must-not-import"), row("youtube.com.evil.test", "HSID", "must-not-import"),
            row(".youtube.com", "expired", "must-not-import",100)).joinToString("\r\n"), nowSeconds = 200)
        assertEquals("fake-primary", cookies.value("SAPISID"))
        assertEquals("fake-third", cookies.value("__Secure-3PAPISID"))
        assertNull(cookies.value("SID")); assertNull(cookies.value("HSID")); assertNull(cookies.value("expired"))
        assertFalse(cookies.toString().contains("fake-primary"))
    }
    @Test fun `third party cookie can replace absent SAPISID`() {
        val cookies = SessionCookies.fromHeader("LOGIN_INFO=fake; __Secure-3PAPISID=third=value")
        assertEquals("third=value", cookies.value("__Secure-3PAPISID"))
    }
    @Test(expected = IllegalArgumentException::class) fun `missing login marker is rejected`() { SessionCookies.fromHeader("SAPISID=fake") }
    @Test(expected = IllegalArgumentException::class) fun `header injection is rejected`() { SessionCookies.fromHeader("SAPISID=fake\r\nX-Injected: value; LOGIN_INFO=fake") }
    @Test(expected = IllegalArgumentException::class) fun `invalid Netscape file is rejected`() { SessionCookies.fromNetscape("not a cookie export") }
    @Test fun `only applicable root path cookies are imported`() {
        val text = row(".youtube.com","SAPISID","root") + "\n" + row(".youtube.com","LOGIN_INFO","login") + "\n" +
            ".youtube.com\tTRUE\t/other\tTRUE\t0\tSAPISID\tnot-root"
        assertEquals("root",SessionCookies.fromNetscape(text).value("SAPISID"))
    }
}
