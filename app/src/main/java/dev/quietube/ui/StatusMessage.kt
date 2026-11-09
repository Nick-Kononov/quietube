package dev.quietube.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun StatusMessage(icon: ImageVector, title: String, body: String? = null, actionLabel: String? = null, action: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null && actionLabel != null) TextButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable
internal fun ConsumeSnackbar(message: String?, host: SnackbarHostState, consume: (String) -> Unit, actionLabel: String? = null, action: (() -> Unit)? = null,eventKey: Any? = message) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(message,eventKey) {
        message?.let {
            consume(it)
            // The queued display must outlive the state acknowledgement that clears the effect key.
            scope.launch { if (host.showSnackbar(it,actionLabel = actionLabel,withDismissAction = actionLabel != null) == SnackbarResult.ActionPerformed) action?.invoke() }
        }
    }
}
