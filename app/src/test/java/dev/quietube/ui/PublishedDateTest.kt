package dev.quietube.ui

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PublishedDateTest {
    @Test fun isoDateAndTimestampUseReadableCalendarDate() {
        assertEquals("Nov 10, 2014", formatPublishedDate("2014-11-10T06:05:55-08:00", Locale.US))
        assertEquals("Nov 10, 2014", formatPublishedDate("2014-11-10", Locale.US))
    }
    @Test fun relativeMissingAndInvalidDatesArePreserved() {
        assertNull(formatPublishedDate(null))
        assertEquals("11 years ago", formatPublishedDate("11 years ago", Locale.US))
        assertEquals("2014-99-99", formatPublishedDate("2014-99-99", Locale.US))
        assertEquals("", formatPublishedDate("", Locale.US))
    }
}
