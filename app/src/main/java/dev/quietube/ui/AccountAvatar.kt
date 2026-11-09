package dev.quietube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/** Avatar menu keeps account and appearance settings reachable from feed and search. */
@Composable
internal fun AccountButton(signedIn: Boolean, profile: Profile?, onClick: () -> Unit, settings: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        val open = { if (settings == null) onClick() else menu = true }
        val avatar = profile?.avatarUrl
        if (signedIn && avatar != null) IconButton(onClick = open) { Avatar(avatar, "Account menu", 32.dp) }
        else TextButton(onClick = open) { Text(if (signedIn) "Account" else "Sign in") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (signedIn) "Account" else "Sign in") }, onClick = { menu = false; onClick() })
            settings?.let { action -> DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; action() }) }
        }
    }
}

@Composable
internal fun Avatar(url: String?, description: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(url, description, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
}
