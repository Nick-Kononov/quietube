@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.quietube.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun RefreshBox(refreshing: Boolean,refresh: () -> Unit,modifier: Modifier = Modifier,content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val animate = rememberAnimationsEnabled()
    PullToRefreshBox(isRefreshing = refreshing,onRefresh = refresh,state = state,modifier = modifier,
        indicator = {
            if (refreshing && !animate) AppLoadingIndicator(Modifier.align(Alignment.TopCenter),contained = true)
            else PullToRefreshDefaults.LoadingIndicator(state,refreshing,Modifier.align(Alignment.TopCenter))
        },content = content)
}
