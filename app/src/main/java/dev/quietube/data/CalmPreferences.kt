package dev.quietube.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.calm by preferencesDataStore("calm")

/** Options that make the app quieter; all off by default. */
data class CalmSettings(val hideViews: Boolean = false, val hideLikes: Boolean = false, val autoplay: Boolean = false,
    val caughtUp: Boolean = false, val greyThumbnails: Boolean = false)

enum class CalmOption(internal val key: Preferences.Key<Boolean>) {
    HideViews(booleanPreferencesKey("hide_views")), HideLikes(booleanPreferencesKey("hide_likes")), Autoplay(booleanPreferencesKey("autoplay")),
    CaughtUp(booleanPreferencesKey("caught_up")), GreyThumbnails(booleanPreferencesKey("grey_thumbnails")),
}

@Singleton
class CalmPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val settings: Flow<CalmSettings> = context.calm.data.catch { emit(emptyPreferences()) }.map { prefs ->
        fun on(option: CalmOption) = prefs[option.key] ?: false
        CalmSettings(on(CalmOption.HideViews), on(CalmOption.HideLikes), on(CalmOption.Autoplay), on(CalmOption.CaughtUp), on(CalmOption.GreyThumbnails))
    }.distinctUntilChanged()
    suspend fun set(option: CalmOption, enabled: Boolean) { context.calm.edit { it[option.key] = enabled } }
}

/** Where "You're all caught up" goes in Subscriptions: above the newest video already watched here, if any. */
internal fun caughtUpIndex(videos: List<Video>): Int? = videos.indexOfFirst { it.resumePositionMs != null }.takeIf { it >= 0 }

/** The video autoplay moves on to: the playlist's next one when playing from a playlist, otherwise the first related video. */
internal fun autoplayNext(queue: List<String>?, related: List<Video>, current: String?): String? =
    if (queue != null) queue.firstOrNull()
    else related.firstOrNull { !it.isLive && !it.isShort && it.url != current }?.url
