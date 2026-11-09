package dev.quietube.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.runtime.Composable

@Composable
internal fun AppNavigation(selected: TopDestination, hidden: Boolean, rail: Boolean, select: (TopDestination) -> Unit, content: @Composable () -> Unit) {
    NavigationSuiteScaffold(layoutType = if (hidden) NavigationSuiteType.None else if (rail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        navigationSuiteItems = { TopDestination.entries.forEach { destination ->
            item(selected = selected == destination, onClick = { select(destination) }, label = { Text(destination.label) }, icon = {
                Icon(when (destination) { TopDestination.Home -> Icons.Rounded.Home; TopDestination.Subscriptions -> Icons.Rounded.Subscriptions; TopDestination.Library -> Icons.Rounded.VideoLibrary }, null)
            })
        } }, content = content)
}
