package dev.quietube.ui

import dev.quietube.data.AccountFeed
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    @Test fun everyFormerChipHasOneRouteAndOneTopLevelDestination() {
        assertEquals(AccountFeed.entries.size, AccountFeed.entries.map(::feedRoute).distinct().size)
        for (feed in AccountFeed.entries) {
            assertEquals(feed, routeFeed(feedRoute(feed)))
            assertEquals(when (feed) { AccountFeed.Home -> TopDestination.Home; AccountFeed.Subscriptions -> TopDestination.Subscriptions; else -> TopDestination.Library }, routeTop(feedRoute(feed)))
        }
        assertEquals(TopDestination.Library, routeTop("library"))
        assertEquals(4, libraryFeeds.size)
        assertEquals("library/Playlists", feedPageKey("library/Playlists", null))
        assertNotEquals(feedPageKey("library/Playlists", null), feedPageKey("library/Playlists", "root"))
        assertNotEquals(feedPageKey("library/Playlists", "one"), feedPageKey("library/Playlists", "two"))
    }
    @Test fun overlaysDoNotChangeTheSelectedTopLevelDestination() {
        for (route in listOf(null, "search", "player", "account", "settings", "library/unknown")) {
            assertNull(routeTop(route)); assertNull(routeFeed(route))
        }
    }
    @Test fun railStartsAtMediumWidth() {
        assertFalse(useNavigationRail(393)); assertFalse(useNavigationRail(599))
        assertTrue(useNavigationRail(600)); assertTrue(useNavigationRail(1000))
    }
}
