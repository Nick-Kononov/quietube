package dev.quietube.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun defaultsAndUnknownModesFollowSystem() {
        assertEquals(AppearanceSettings(ThemeMode.System, false), AppearanceSettings())
        for (value in listOf(null, "", "future")) assertEquals(ThemeMode.System, themeMode(value))
        for (mode in ThemeMode.entries) assertEquals(mode, themeMode(mode.name))
    }
    @Test fun explicitModesOverrideSystem() {
        for (system in listOf(false, true)) {
            assertEquals(system, useDarkTheme(ThemeMode.System, system))
            assertFalse(useDarkTheme(ThemeMode.Light, system))
            assertTrue(useDarkTheme(ThemeMode.Dark, system))
        }
    }
    @Test fun wallpaperRequiresOptInAndSupportedApi() {
        for (sdk in listOf(26, 30, 31, 37)) {
            assertFalse(useDynamicColor(false, sdk))
            assertEquals(sdk >= 31, useDynamicColor(true, sdk))
        }
    }
    @Test fun brandTextAndIconsMeetContrastTargets() {
        for (scheme in listOf(BrandLight, BrandDark)) with(scheme) {
            for ((text, background) in listOf(onPrimary to primary, onPrimaryContainer to primaryContainer,
                onSecondary to secondary, onSecondaryContainer to secondaryContainer, onTertiary to tertiary,
                onTertiaryContainer to tertiaryContainer, onError to error, onErrorContainer to errorContainer,
                onBackground to background, inverseOnSurface to inverseSurface, onSurfaceVariant to surfaceVariant)) contrast(text, background, 4.5f)
            for (surface in listOf(surface, surfaceBright, surfaceDim, surfaceContainerLowest, surfaceContainerLow,
                surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)) {
                contrast(onSurface, surface, 4.5f)
                contrast(onSurfaceVariant, surface, 4.5f)
                contrast(primary, surface, 3f)
                contrast(outline, surface, 3f)
            }
        }
    }
    private fun contrast(a: Color, b: Color, minimum: Float) {
        val ratio = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
        assertTrue("$a on $b: $ratio < $minimum", ratio >= minimum)
    }
}
