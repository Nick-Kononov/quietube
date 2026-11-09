package dev.quietube.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.quietube.account.actions.EngagementState
import dev.quietube.data.Engagement
import dev.quietube.data.Rating
import kotlinx.coroutines.launch

/** Isolated, synthetic component samples; never reads an account or calls the repository. */
class ExpressiveSamplesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark",false)
        val bars = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = bars,navigationBarStyle = bars)
        setContent {
            QuietubeTheme(AppearanceSettings(mode = if (dark) ThemeMode.Dark else ThemeMode.Light)) {
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var subscribed by remember { mutableStateOf(false) }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 20.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text("Material 3 Expressive",style = MaterialTheme.typography.headlineMedium)
                            Text("Phase 5 · synthetic UI samples",style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Ratings",style = MaterialTheme.typography.titleLarge,modifier = Modifier.padding(horizontal = 20.dp))
                        listOf(Rating.None,Rating.Like,Rating.Dislike).forEach { initial ->
                            var selected by remember { mutableStateOf(initial) }
                            RatingButtons(EngagementState(url = "sample",signedIn = true,info = Engagement(rating = selected,likes = "1280",dislikes = "24",estimatedDislikes = true)),null) {
                                selected = if (selected == it) Rating.None else it
                            }
                        }
                        RatingButtons(EngagementState(),1280) {}
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Subscription",style = MaterialTheme.typography.titleLarge)
                            SubscribeButton(EngagementState(signedIn = true,info = Engagement(channelId = "synthetic",subscribed = subscribed))) {
                                subscribed = !subscribed
                                if (!subscribed) scope.launch { if (snackbar.showSnackbar("Unsubscribed","Undo",withDismissAction = true) == SnackbarResult.ActionPerformed) subscribed = true }
                            }
                            SubscribeButton(EngagementState(signedIn = true,info = Engagement(channelId = "synthetic",subscribed = true))) {}
                            Text("Loading",style = MaterialTheme.typography.titleLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                AppLoadingIndicator()
                                AppLoadingIndicator(contained = true)
                            }
                            Text("No account data or network requests.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
