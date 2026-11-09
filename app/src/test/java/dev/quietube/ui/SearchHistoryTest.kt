package dev.quietube.ui

import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    @Test fun boundedNewestFirstDeduplicatesTrimmedQueriesIgnoringCase() {
        val result = recentSearches((1..25).map { "Query $it" }, " query 5 ")
        assertEquals(20, result.size)
        assertEquals("query 5", result.first())
        assertEquals(1, result.count { it.equals("query 5", ignoreCase = true) })
        assertFalse(result.contains("Query 25"))
        assertEquals(listOf("X"), recentSearches(listOf(" x ", "X", ""), "X"))
    }
    @Test fun blankQueriesAndCorruptStorageAreSafe() {
        assertEquals(listOf("one"), recentSearches(listOf("one"), "  "))
        for (value in listOf(null, "", "broken", "{}", "[false,null,42]")) assertTrue(decodeSearchHistory(value).isEmpty())
        assertEquals(listOf("first", "second"), decodeSearchHistory("[\" first \",\"FIRST\",\"second\",\"\"]"))
    }
    @Test fun queryAndStorageLimitsAreApplied() {
        assertEquals(500, recentSearches(emptyList(), "a".repeat(600)).single().length)
        assertEquals(20, decodeSearchHistory((1..40).joinToString(",", "[", "]") { "\"$it\"" }).size)
    }
}
