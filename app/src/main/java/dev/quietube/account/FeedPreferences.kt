package dev.quietube.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietube.data.AccountFeed
import dev.quietube.data.PlaylistSummary
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.feedPreferences by preferencesDataStore("feed_preferences")
private val SELECTED_FEED = stringPreferencesKey("selected_feed")
private val PLAYLIST_ID = stringPreferencesKey("playlist_id")
private val PLAYLIST_TITLE = stringPreferencesKey("playlist_title")
private val PLAYLIST_THUMBNAIL = stringPreferencesKey("playlist_thumbnail")
private val PLAYLIST_COUNT = stringPreferencesKey("playlist_count")

/** The feed (and opened playlist) a signed-in user was last looking at. */
class FeedSelection(val feed: AccountFeed = AccountFeed.Home, val playlist: PlaylistSummary? = null)

/** Account-scoped: cleared on sign-out. */
@Singleton
class FeedPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun read(): FeedSelection {
        val prefs = try { context.feedPreferences.data.first() } catch (_: IOException) { return FeedSelection() }
        val playlist = prefs[PLAYLIST_ID]?.let { id -> PlaylistSummary(id, prefs[PLAYLIST_TITLE] ?: "Playlist", prefs[PLAYLIST_THUMBNAIL], prefs[PLAYLIST_COUNT]) }
        return FeedSelection(AccountFeed.entries.firstOrNull { it.name == prefs[SELECTED_FEED] } ?: AccountFeed.Home, playlist)
    }
    /** [stillCurrent] is checked inside the write, so a selection from a signed-out or replaced session is never stored. */
    suspend fun save(selection: FeedSelection, stillCurrent: () -> Boolean) {
        context.feedPreferences.edit { prefs ->
            if (!stillCurrent()) return@edit
            prefs.clear(); prefs[SELECTED_FEED] = selection.feed.name
            selection.playlist?.let { playlist ->
                prefs[PLAYLIST_ID] = playlist.id
                prefs[PLAYLIST_TITLE] = playlist.title
                playlist.thumbnailUrl?.let { prefs[PLAYLIST_THUMBNAIL] = it }
                playlist.countText?.let { prefs[PLAYLIST_COUNT] = it }
            }
        }
    }
    suspend fun clear() { context.feedPreferences.edit { it.clear() } }
}
