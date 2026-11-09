package dev.quietube.data

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.stream.Description

class VideoDescriptionTest {
    @Test fun missingAndEmptyDescriptionsAreUnavailable() {
        assertNull(videoDescription(null))
        assertNull(videoDescription(Description(" \n ", Description.Type.PLAIN_TEXT)))
        assertNull(videoDescription(Description("<p></p>", Description.Type.HTML)))
    }
    @Test fun plainDescriptionsKeepLineBreaksAndLiteralMarkup() {
        assertEquals("Hello\n<b>World</b>", videoDescription(Description(" Hello\n<b>World</b> \n", Description.Type.PLAIN_TEXT)))
    }
    @Test fun htmlBecomesPlainTextWithoutRunningScriptsOrFetchingLinks() {
        val text = videoDescription(Description("<p>Hello<br>World &amp; friends</p><script>alert('bad')</script><a href='https://example.com'>Source</a>", Description.Type.HTML))!!
        assertTrue(text.contains("Hello\nWorld & friends"))
        assertTrue(text.contains("Source"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("<"))
    }
}
