package dev.quietube.playback

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.MainActivity
import dev.quietube.R
import dev.quietube.links.shareableLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The launcher's "Continue watching" shortcut for the last unfinished video, kept in step with the resume positions
 * saved to Room. It holds only the plain watch link and title, never a stream URL.
 */
@Singleton
class ContinueShortcut @Inject constructor(@ApplicationContext private val context: Context) {
    private var shown: String? = null

    /** Called with each resume save: [positionMs] is what was saved, 0 once the video is finished. */
    fun saved(url: String, title: String, positionMs: Long) {
        val link = shareableLink(url) ?: return
        when {
            positionMs >= MIN_PROGRESS_MS -> if (shown != link) publish(link, title)
            positionMs == 0L && published() == link -> remove()
        }
    }

    fun remove() {
        try { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID)) } catch (_: Exception) {}
        shown = null
    }

    private fun publish(link: String, title: String) {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java)
        val shortcut = ShortcutInfoCompat.Builder(context, ID).setShortLabel(shortcutShortLabel(title)).setLongLabel(shortcutLongLabel(title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_resume)).setIntent(intent).build()
        // Push, unlike set, isn't rate-limited while the app is in the background.
        try { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut); shown = link } catch (_: Exception) {}
    }

    private fun published(): String? = shown ?: try {
        ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == ID }?.intent?.dataString
    } catch (_: Exception) { null }

    private companion object {
        const val ID = "continue"
        // A few seconds in isn't worth a shortcut.
        const val MIN_PROGRESS_MS = 30_000L
    }
}

internal fun shortcutShortLabel(title: String): String = ellipsize(title, 25)
internal fun shortcutLongLabel(title: String): String = ellipsize("Continue watching $title", 60)
private fun ellipsize(text: String, max: Int): String = text.trim().let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }
