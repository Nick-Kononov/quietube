@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.quietube.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

@Composable
internal fun AppLoadingIndicator(modifier: Modifier = Modifier,contained: Boolean = false) {
    val animate = rememberAnimationsEnabled()
    val semantics = modifier.clearAndSetSemantics { contentDescription = "Loading"; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
    if (animate) {
        if (contained) ContainedLoadingIndicator(semantics) else LoadingIndicator(semantics)
    } else {
        if (contained) ContainedLoadingIndicator(progress = { .5f },modifier = semantics)
        else LoadingIndicator(progress = { .5f },modifier = semantics)
    }
}
