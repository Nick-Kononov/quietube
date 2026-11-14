package dev.quietube.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class VideoColoursTest {
    private fun image(vararg colours: Pair<Int, Int>): IntArray = colours.flatMap { (argb, count) -> List(count) { argb } }.toIntArray()
    private fun contrast(a: Int, b: Int): Float {
        val x = Color(a).luminance(); val y = Color(b).luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
    private fun hue(argb: Int): Float {
        val hsv = FloatArray(3)
        val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d == 0f) return -1f
        hsv[0] = when (max) { r -> 60 * (((g - b) / d) % 6); g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
        return (hsv[0] + 360) % 360
    }

    @Test fun aColourfulThumbnailTintsAccentsTowardsItsColour() {
        val blue = image(0xFF1565C0.toInt() to 600, 0xFF101010.toInt() to 400)
        for (dark in listOf(false, true)) {
            val accents = videoAccents(blue, dark)!!
            assertTrue("primary hue ${hue(accents.primary)}", hue(accents.primary) in 190f..250f)
            assertTrue(contrast(accents.primary, accents.onPrimary) >= 4.5f)
            assertTrue(contrast(accents.primaryContainer, accents.onPrimaryContainer) >= 4.5f)
            assertTrue(contrast(accents.secondaryContainer, accents.onSecondaryContainer) >= 4.5f)
        }
        assertNotEquals(videoAccents(blue, false), videoAccents(blue, true))
    }
    @Test fun greyThumbnailsKeepTheAppColours() {
        assertNull(videoAccents(image(0xFF808080.toInt() to 500, 0xFF202020.toInt() to 500), dark = true))
        assertNull(videoAccents(IntArray(0), dark = true))
    }
    @Test fun accentsReplaceOnlyAccentRoles() {
        val accents = videoAccents(image(0xFF2E7D32.toInt() to 1000), dark = true)!!
        val scheme = BrandDark.withAccents(accents)
        assertEquals(Color(accents.primary), scheme.primary)
        assertEquals(Color(accents.secondaryContainer), scheme.secondaryContainer)
        assertEquals(BrandDark.background, scheme.background)
        assertEquals(BrandDark.surfaceContainer, scheme.surfaceContainer)
        assertEquals(BrandDark.error, scheme.error)
    }
}
