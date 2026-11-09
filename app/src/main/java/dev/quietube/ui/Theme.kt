package dev.quietube.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import android.os.Build
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietube.R

// Material Color Utilities 0.3.0, SchemeTonalSpot(#FF0033); neutral dark surfaces and accessible action reds are overridden.
internal val BrandLight = lightColorScheme(
    primary = Color(0xFFC00029),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF733332),
    inversePrimary = Color(0xFFFFB3AF),
    secondary = Color(0xFF775654),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD8),
    onSecondaryContainer = Color(0xFF5D3F3E),
    tertiary = Color(0xFF735A2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA9),
    onTertiaryContainer = Color(0xFF594319),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231919),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231919),
    surfaceVariant = Color(0xFFF4DDDC),
    onSurfaceVariant = Color(0xFF534342),
    surfaceTint = Color(0xFF904A48),
    inverseSurface = Color(0xFF382E2D),
    inverseOnSurface = Color(0xFFFFEDEB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF857372),
    outlineVariant = Color(0xFFD7C1C0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFE8D6D5),
    surfaceContainer = Color(0xFFFCEAE8),
    surfaceContainerHigh = Color(0xFFF6E4E3),
    surfaceContainerHighest = Color(0xFFF0DEDD),
    surfaceContainerLow = Color(0xFFFFF0EF),
    surfaceContainerLowest = Color(0xFFFFFFFF)
)

internal val BrandDark = darkColorScheme(
    primary = Color(0xFFFF5268),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF733332),
    onPrimaryContainer = Color(0xFFFFDAD8),
    inversePrimary = Color(0xFF904A48),
    secondary = Color(0xFFE7BDBA),
    onSecondary = Color(0xFF442928),
    secondaryContainer = Color(0xFF5D3F3E),
    onSecondaryContainer = Color(0xFFFFDAD8),
    tertiary = Color(0xFFE3C28C),
    onTertiary = Color(0xFF412D05),
    tertiaryContainer = Color(0xFF594319),
    onTertiaryContainer = Color(0xFFFFDEA9),
    background = Color(0xFF101215),
    onBackground = Color(0xFFF0DEDD),
    surface = Color(0xFF101215),
    onSurface = Color(0xFFF0DEDD),
    surfaceVariant = Color(0xFF534342),
    onSurfaceVariant = Color(0xFFD7C1C0),
    surfaceTint = Color(0xFFFFB3AF),
    inverseSurface = Color(0xFFF0DEDD),
    inverseOnSurface = Color(0xFF382E2D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C8B),
    outlineVariant = Color(0xFF534342),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393D),
    surfaceDim = Color(0xFF101215),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF26292E),
    surfaceContainerHighest = Color(0xFF313439),
    surfaceContainerLow = Color(0xFF181A1E),
    surfaceContainerLowest = Color(0xFF0B0D10)
)

internal val VideoBlack = Color.Black
internal val OverlayWhite = Color.White
internal fun screenCorner(radius: Float) = RoundedCornerShape(radius)
private val AppShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp))
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp)
private val AppTypography = Typography(
    displayLarge = type(57, 64), displayMedium = type(45, 52), displaySmall = type(36, 44),
    headlineLarge = type(32, 40), headlineMedium = type(28, 36), headlineSmall = type(24, 32),
    titleLarge = type(22, 28), titleMedium = type(16, 24, FontWeight.Medium), titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 16, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium))
// YouTube's wordmark is set in the proprietary YouTube Sans; the system's condensed Roboto is the closest free match.
private val WordmarkFont = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold))

@Composable
internal fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_quietube), null, Modifier.size(34.dp))
        Spacer(Modifier.width(8.dp))
        Text("YouPoop", fontFamily = WordmarkFont, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-0.8).sp)
    }
}

@Composable
fun QuietubeTheme(appearance: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val dark = useDarkTheme(appearance.mode, isSystemInDarkTheme())
    val scheme = if (useDynamicColor(appearance.wallpaper, Build.VERSION.SDK_INT) && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) BrandDark else BrandLight
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), typography = AppTypography, shapes = AppShapes, content = content)
}
