package dev.quietube.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.quietube.account.actions.SyncSettings
import dev.quietube.data.CalmOption
import dev.quietube.data.CalmSettings
import dev.quietube.data.SegmentCategory
import dev.quietube.data.SegmentMode
import dev.quietube.data.ThirdPartySettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(appearance: AppearanceSettings, sync: SyncSettings, signedIn: Boolean, version: String,
    back: () -> Unit, themeMode: (ThemeMode) -> Unit, wallpaper: (Boolean) -> Unit, history: (Boolean) -> Unit, estimates: (Boolean) -> Unit,
    ambient: (Boolean) -> Unit = {}, videoColours: (Boolean) -> Unit = {}, thirdParty: ThirdPartySettings = ThirdPartySettings(),
    sponsorBlock: (Boolean) -> Unit = {}, segmentMode: (SegmentCategory, SegmentMode) -> Unit = { _, _ -> }, deArrow: (Boolean) -> Unit = {},
    calm: CalmSettings = CalmSettings(), calmOption: (CalmOption, Boolean) -> Unit = { _, _ -> }) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = { LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scroll, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            SettingsHeading("Appearance")
            ThemeMode.entries.forEach { mode ->
                ListItem(content = { Text(mode.name) }, leadingContent = { RadioButton(appearance.mode == mode, onClick = null) },
                    modifier = Modifier.selectable(appearance.mode == mode, role = Role.RadioButton, onClick = { themeMode(mode) }))
            }
            if (Build.VERSION.SDK_INT >= 31) SettingsSwitch("Use wallpaper colours", "Use colours from your wallpaper instead of the YouPoop palette.", appearance.wallpaper, wallpaper)
            SettingsHeading("Player")
            if (Build.VERSION.SDK_INT >= 31) SettingsSwitch("Ambient mode", "A soft glow of the video's colours behind the player in dark theme. Off in landscape, picture-in-picture and Battery Saver.", appearance.ambient, ambient)
            SettingsSwitch("Colour player from video", "Tint the player's buttons and tabs with colours from the video's thumbnail.", appearance.videoColours, videoColours)
            SettingsHeading("Feed")
            SettingsSwitch("Hide view counts", "On video cards and in the video's details.", calm.hideViews) { calmOption(CalmOption.HideViews, it) }
            SettingsSwitch("Hide like counts", "Likes, dislike estimates and comment likes.", calm.hideLikes) { calmOption(CalmOption.HideLikes, it) }
            SettingsSwitch("Mark where you're caught up", "In Subscriptions, a line above the newest video you've already watched on this phone.", calm.caughtUp) { calmOption(CalmOption.CaughtUp, it) }
            SettingsSwitch("Grey thumbnails", "Show thumbnails without colour, so they pull less at your attention. Videos still play in colour.", calm.greyThumbnails) { calmOption(CalmOption.GreyThumbnails, it) }
            SettingsHeading("Playback")
            SettingsSwitch("Autoplay", "When a video ends, play the next one after 5 seconds: the playlist's next video, or the first related video. Stops at the end of a playlist.", calm.autoplay) { calmOption(CalmOption.Autoplay, it) }
            ListItem(content = { Text("Default video quality") }, supportingContent = { Text("Auto · 1080p on Wi-Fi, 720p on mobile data. Metered Wi-Fi uses the mobile cap. Override quality from the video's details.") })
            ListItem(content = { Text("Background audio") }, supportingContent = { Text("Audio keeps playing with the screen off and when you leave the app. Use the notification or lock-screen controls to pause.") })
            SettingsHeading("Account sync")
            SettingsSwitch("Sync watch history", if (signedIn) "Report playback to your signed-in YouTube account." else "Applies when signed in to YouTube.", sync.history, history)
            SettingsHeading("Third-party services")
            SettingsSwitch("Estimate dislikes (Return YouTube Dislike)", "Return YouTube Dislike receives this video's ID and your IP address when enabled. Counts are estimates and can be delayed or unavailable.", sync.dislikeEstimates, estimates)
            SettingsSwitch("Skip sponsors (SponsorBlock)", "SponsorBlock receives the first 4 characters of a hash of each video's ID, which many videos share, and your IP address. Segments are crowd-sourced and can be missing or wrong. Never submits or votes.", thirdParty.sponsorBlock, sponsorBlock)
            if (thirdParty.sponsorBlock) SegmentCategory.entries.forEach { category -> SegmentModeRow(category, thirdParty.mode(category)) { segmentMode(category, it) } }
            SettingsSwitch("Clearer titles (DeArrow)", "Replaces clickbait titles with crowd-sourced ones; the original stays in the video's details. DeArrow receives the first 4 characters of a hash of each listed video's ID and your IP address. Thumbnails stay YouTube's.", thirdParty.deArrow, deArrow)
            SettingsHeading("About")
            ListItem(content = { Text("YouPoop") }, supportingContent = { Text("Version $version") })
            ListItem(content = { Text("Licence") }, supportingContent = { Text("GPL-3.0-or-later") })
            ListItem(content = { Text("Source") }, supportingContent = { Text("A private, open-source Android YouTube client. Includes NewPipeExtractor, licensed GPL-3.0-or-later.") })
            ListItem(content = { Text("Community data") }, supportingContent = { Text("Segments from SponsorBlock and titles from DeArrow (sponsor.ajay.app), licensed CC BY-NC-SA 4.0.") })
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun SettingsSwitch(title: String, body: String, checked: Boolean, change: (Boolean) -> Unit) {
    ListItem(content = { Text(title) }, supportingContent = { Text(body) }, trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = change))
}
