package dev.quietube.ui

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*

private val Context.appearancePreferences by preferencesDataStore("appearance_preferences")
enum class ThemeMode { System, Light, Dark }
data class AppearanceSettings(val mode: ThemeMode = ThemeMode.System, val wallpaper: Boolean = false)
internal fun themeMode(value: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.System
internal fun useDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
internal fun useDynamicColor(enabled: Boolean, sdk: Int): Boolean = enabled && sdk >= 31

@Singleton
class AppearancePreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val settings = context.appearancePreferences.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { AppearanceSettings(themeMode(it[stringPreferencesKey("mode")]), it[booleanPreferencesKey("wallpaper")] ?: false) }
    suspend fun mode(mode: ThemeMode) { context.appearancePreferences.edit { it[stringPreferencesKey("mode")] = mode.name } }
    suspend fun wallpaper(enabled: Boolean) { context.appearancePreferences.edit { it[booleanPreferencesKey("wallpaper")] = enabled } }
}
