package dev.quietube.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import dev.quietube.account.SessionMethod
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.quietube.account.AccountUiState
import dev.quietube.account.SessionStatus

@Composable
fun AccountScreen(session: SessionStatus, ui: AccountUiState, back: () -> Unit,
    login: () -> Unit, import: () -> Unit, signOut: () -> Unit, deviceLogin: () -> Unit, cancelDeviceLogin: () -> Unit) {
    val context = LocalContext.current
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Your account", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(28.dp))
            Text(if (session.signedIn) "Connected to YouTube" else "Make it yours.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(if (session.signedIn) "Your recommendations, subscriptions and library are available in the home tabs."
                else "Sign in to see your real recommendations, subscriptions, history and saved videos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            if (ui.busy || !session.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Experimental device-code sign-in",style = MaterialTheme.typography.titleMedium)
            Text("Uses YouTube's TV sign-in flow. Google's approval screen may identify it as YouTube on TV rather than Quietube. It opens in your normal browser and does not require cookie exports.",
                style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant,modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = deviceLogin,enabled = session.ready && !ui.busy,modifier = Modifier.fillMaxWidth()) { Text("Try device-code sign-in") }
            if (session.method == SessionMethod.DeviceCode) Text("Connected with device-code tokens. Access refreshes automatically.",style = MaterialTheme.typography.bodySmall)
            ui.deviceLogin?.let { pending ->
                SelectionContainer { Text(pending.userCode,style = MaterialTheme.typography.headlineLarge,modifier = Modifier.padding(vertical = 16.dp)) }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("YouTube activation code",pending.userCode)
                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }) { Text("Copy activation code") }
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW,Uri.parse(pending.verificationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(Intent(intent).setPackage("com.android.chrome")) }
                    catch (_: android.content.ActivityNotFoundException) { context.startActivity(intent) }
                },modifier = Modifier.fillMaxWidth()) { Text("Open Google in Chrome") }
            }
            if (ui.busy) TextButton(onClick = cancelDeviceLogin) { Text("Cancel sign-in") }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedButton(onClick = login, enabled = session.ready && !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(if (session.signedIn) "Sign in again" else "Sign in with Google") }
            OutlinedButton(onClick = import, enabled = session.ready && !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Import cookies.txt") }
            if (session.signedIn) OutlinedButton(onClick = signOut, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out and clear session") }
            Spacer(Modifier.height(16.dp))
            ui.message?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp)) }
            Text("If Google blocks the embedded browser, import a Netscape cookies.txt export from a browser signed in to youtube.com. You can create it on this phone using a browser with a cookie-export extension; a computer is optional.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Cookies grant access to your Google session. They are encrypted on this phone and excluded from backups. Sign out removes them. Your browser export remains yours to delete.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("The app automatically saves cookie updates YouTube sends while your session remains valid. Google may still require a fresh sign-in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Playback remains anonymous. The player has watch-history sync, rating and subscription controls.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
