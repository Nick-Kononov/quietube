package dev.quietube.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*

@Composable
internal fun PlayerLoadingIndicator() {
    val modifier = Modifier.clearAndSetSemantics { contentDescription = "Loading video"; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
    if (rememberAnimationsEnabled()) CircularProgressIndicator(modifier,color = OverlayWhite,trackColor = Color.Transparent)
    else CircularProgressIndicator(progress = { .25f },modifier = modifier,color = OverlayWhite,trackColor = Color.Transparent)
}
