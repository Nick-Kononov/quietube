package dev.quietube.ui

import dev.quietube.data.AccountFeed

internal enum class TopDestination(val label: String, val route: String) { Home("Home", "home"), Subscriptions("Subscriptions", "subscriptions"), Library("Library", "library") }
internal val libraryFeeds = listOf(AccountFeed.History, AccountFeed.WatchLater, AccountFeed.Liked, AccountFeed.Playlists)
internal fun feedRoute(feed: AccountFeed): String = when (feed) {
    AccountFeed.Home -> "home"
    AccountFeed.Subscriptions -> "subscriptions"
    else -> "library/${feed.name}"
}
internal fun routeFeed(route: String?): AccountFeed? = AccountFeed.entries.firstOrNull { feedRoute(it) == route }
internal fun routeTop(route: String?): TopDestination? = when {
    route == "home" -> TopDestination.Home
    route == "subscriptions" -> TopDestination.Subscriptions
    route == "library" || libraryFeeds.any { feedRoute(it) == route } -> TopDestination.Library
    else -> null
}
internal fun useNavigationRail(widthDp: Int): Boolean = widthDp >= 600

internal fun feedPageKey(route: String, playlistId: String?): String = if (playlistId == null) route else "$route/playlist/$playlistId"
