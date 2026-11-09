package dev.quietube.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.quietube.account.AccountUiState
import dev.quietube.account.SessionMethod
import dev.quietube.account.SessionStatus
import dev.quietube.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(session: SessionStatus, ui: AccountUiState, back: () -> Unit,
    login: () -> Unit, import: () -> Unit, signOut: () -> Unit, deviceLogin: () -> Unit, cancelDeviceLogin: () -> Unit,
    settings: () -> Unit, profile: Profile? = null) {
    var explain by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val enabled = session.ready && !ui.busy
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = { LargeTopAppBar(title = { Text("Account") }, scrollBehavior = scroll, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            if (ui.busy || !session.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session.signedIn) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(profile?.avatarUrl, null, 64.dp)
                    Column {
                        Text(profile?.name ?: "YouTube account", style = MaterialTheme.typography.titleLarge)
                        Text("Connected · ${if (session.method == SessionMethod.DeviceCode) "Device-code sign-in" else "Browser cookies"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("Sign in for your recommendations, subscriptions and library.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                Button(onClick = login, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { Text("Sign in with Google") }
            }
            AccountAction(Icons.Rounded.Settings, "Settings", action = settings)
            if (session.signedIn) AccountAction(Icons.AutoMirrored.Rounded.Login, "Sign in again", enabled = enabled, action = login)
            AccountAction(Icons.Rounded.FileOpen, "Import cookies.txt", enabled = enabled, action = import)
            if (session.signedIn) AccountAction(Icons.AutoMirrored.Rounded.Logout, "Sign out", enabled = enabled, action = { confirmSignOut = true })
            AccountAction(Icons.Rounded.Tv, "Device-code sign-in", "Experimental · approve in your browser", enabled, deviceLogin)
            ui.deviceLogin?.let { DeviceLoginCard(it, cancelDeviceLogin) }
            if (ui.signingIn && ui.deviceLogin == null) TextButton(onClick = cancelDeviceLogin, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Cancel sign-in") }
            AccountAction(Icons.Rounded.Info, "How sign-in works", action = { explain = !explain }, expanded = explain)
            if (explain) Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Google sign-in uses an isolated browser inside YouPoop. If Google blocks it, import a Netscape cookies.txt export from a browser signed in to youtube.com. A phone browser with a cookie-export extension can create it; a computer is optional.")
                Text("Device-code sign-in uses YouTube's experimental TV flow. Google's approval screen may identify it as YouTube on TV. Device-code tokens refresh automatically.")
                Text("Cookies grant access to your Google session. They are encrypted on this phone and excluded from backups. Sign out removes the session, personal feeds, saved progress and caches. Your browser export remains yours to delete.")
                Text("The app saves cookie updates YouTube sends while your session remains valid. Google may still require a fresh sign-in.")
                Text("Playback remains anonymous. Watch-history sync and dislike estimates are controlled in Settings; ratings and subscriptions are available in the player.")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirmSignOut && session.signedIn) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out?") },
        text = { Text("This stops playback and clears your session, personal feeds, saved progress and caches. Appearance preferences are kept.") },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirmSignOut = false; signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })
}

@Composable
private fun AccountAction(icon: ImageVector, title: String, body: String? = null, enabled: Boolean = true, action: () -> Unit, expanded: Boolean? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = body?.let { { Text(it) } }, leadingContent = { Icon(icon, null) },
        trailingContent = expanded?.let { { Icon(if (it) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) } },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = action).semantics { expanded?.let { stateDescription = if (it) "Expanded" else "Collapsed" } })
}

@Composable
private fun DeviceLoginCard(pending: dev.quietube.account.oauth.DeviceLogin, cancel: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approve this code in your browser", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(pending.userCode, style = MaterialTheme.typography.headlineLarge) }
            Text("Google may call this YouTube on TV.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val clip = ClipData.newPlainText("YouTube activation code", pending.userCode)
                if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text("Copy activation code") }
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pending.verificationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(Intent(intent).setPackage("com.android.chrome")) }
                catch (_: android.content.ActivityNotFoundException) { context.startActivity(intent) }
            }) { Text("Open Google in Chrome") }
            TextButton(onClick = cancel) { Text("Cancel sign-in") }
        }
    }
}
