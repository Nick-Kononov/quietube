package dev.quietube.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quietube.account.actions.EngagementState
import dev.quietube.data.Rating

@Composable
internal fun RatingButtons(engagement: EngagementState,publicLikes: Long?,rate: (Rating) -> Unit,signInToRate: () -> Unit = {}) {
    val hint by rememberUpdatedState(signInToRate)
    val enabled = engagement.signedIn && !engagement.busy && engagement.url != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ButtonGroup(overflowIndicator = { menu -> ButtonGroupDefaults.OverflowIndicator(menu) },horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(Rating.Like,Rating.Dislike).forEachIndexed { index,rating ->
                val checked = engagement.info.rating == rating
                val label = if (rating == Rating.Like) "Like" else "Dislike"
                val actionLabel = if (checked) "Remove ${label.lowercase()}" else label
                val count = if (rating == Rating.Like) displayLikeCount(engagement.info.likes,publicLikes) else displayDislikeCount(engagement.info.dislikes,engagement.info.estimatedDislikes)
                val click = { if (!engagement.signedIn) hint() else if (enabled) rate(rating) }
                customItem(buttonGroupContent = {
                    val attempt = if (engagement.signedIn) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false,pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) hint()
                        }
                    }
                        .semantics { customActions = listOf(CustomAccessibilityAction("Sign in to rate") { hint(); true }) }
                    Box(attempt) {
                    ToggleButton(checked = checked,onCheckedChange = { click() },enabled = enabled,
                        shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = actionLabel },
                        icon = { Icon(if (rating == Rating.Like) { if (checked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp }
                            else { if (checked) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown },null) }) { Text(count) }
                    }
                },menuContent = { menu -> DropdownMenuItem(text = { Text("$actionLabel · $count") },enabled = enabled || !engagement.signedIn,onClick = { menu.dismiss(); click() }) })
            }
        }
    }
}

@Composable
internal fun SubscribeButton(engagement: EngagementState,subscribe: () -> Unit) {
    val subscribed = engagement.info.subscribed == true
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (subscribed) scheme.secondaryContainer else scheme.primary,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),label = "Subscription container")
    val content by animateColorAsState(if (subscribed) scheme.onSecondaryContainer else scheme.onPrimary,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),label = "Subscription content")
    ToggleButton(checked = subscribed,onCheckedChange = { subscribe() },
        enabled = !engagement.busy && engagement.signedIn && engagement.info.channelId != null && engagement.info.subscribed != null,
        colors = ToggleButtonDefaults.colors(containerColor = container,contentColor = content,
            checkedContainerColor = container,checkedContentColor = content,disabledContainerColor = container,disabledContentColor = content.copy(alpha = .38f)),
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text(when (engagement.info.subscribed) { true -> "Subscribed"; false -> "Subscribe"; null -> "Subscription unavailable" })
    }
}
