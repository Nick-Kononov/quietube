package dev.quietube.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.quietube.data.Profile

@Composable
internal fun AccountButton(signedIn: Boolean, profile: Profile?, onClick: () -> Unit) {
    val avatar = profile?.avatarUrl
    if (signedIn && avatar != null) IconButton(onClick = onClick) { Avatar(avatar, "Account", 32.dp) }
    else TextButton(onClick = onClick) { Text(if (signedIn) "Account" else "Sign in") }
}

@Composable
internal fun Avatar(url: String?, description: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(url, description, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}
