package org.openridesync.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.openridesync.app.ui.oemUiFontFamily

// RoadSync 26.8.10 modern phone palette, mapped to Material 3 roles.
// See docs/research/app/OEM-PHONE-THEME.md for source and intentional contrast changes.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A6FCD), onPrimary = Color(0xFFFCFCFC),
    primaryContainer = Color(0xFFD0E3FC), onPrimaryContainer = Color(0xFF123B6A),
    secondary = Color(0xFF1A6FCD), onSecondary = Color(0xFFFCFCFC),
    secondaryContainer = Color(0xFFEAEBED), onSecondaryContainer = Color(0xFF2E3033),
    tertiary = Color(0xFF1A6FCD), onTertiary = Color(0xFFFCFCFC),
    tertiaryContainer = Color(0xFFECF4FF), onTertiaryContainer = Color(0xFF123B6A),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF2E3033),
    surface = Color(0xFFFCFCFC), onSurface = Color(0xFF2E3033),
    surfaceVariant = Color(0xFFEAEBED), onSurfaceVariant = Color(0xFF4C4E51),
    surfaceTint = Color(0xFF1A6FCD),
    surfaceBright = Color(0xFFFCFCFC), surfaceDim = Color(0xFFEAEBED),
    surfaceContainerLowest = Color(0xFFFCFCFC), surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFF5F7FA), surfaceContainerHigh = Color(0xFFEAEBED),
    surfaceContainerHighest = Color(0xFFEAEBED),
    outline = Color(0xFF696B6E), outlineVariant = Color(0xFFEAEBED),
    inverseSurface = Color(0xFF2E3033), inverseOnSurface = Color(0xFFFCFCFC),
    inversePrimary = Color(0xFF4D99F4),
    error = Color(0xFFAC1C18), onError = Color(0xFFFCFCFC),
    errorContainer = Color(0xFFFFF4F3), onErrorContainer = Color(0xFF710003),
    scrim = Color(0xFF121417),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4D99F4), onPrimary = Color(0xFF121417),
    primaryContainer = Color(0xFF123B6A), onPrimaryContainer = Color(0xFFD0E3FC),
    secondary = Color(0xFF4D99F4), onSecondary = Color(0xFF121417),
    secondaryContainer = Color(0xFF4C4E51), onSecondaryContainer = Color(0xFFFCFCFC),
    tertiary = Color(0xFF4D99F4), onTertiary = Color(0xFF121417),
    tertiaryContainer = Color(0xFF142D4C), onTertiaryContainer = Color(0xFFD0E3FC),
    background = Color(0xFF121417), onBackground = Color(0xFFC9CBCD),
    surface = Color(0xFF2E3033), onSurface = Color(0xFFFCFCFC),
    surfaceVariant = Color(0xFF4C4E51), onSurfaceVariant = Color(0xFFC9CBCD),
    surfaceTint = Color(0xFF4D99F4),
    surfaceBright = Color(0xFF4C4E51), surfaceDim = Color(0xFF121417),
    surfaceContainerLowest = Color(0xFF121417), surfaceContainerLow = Color(0xFF27292D),
    surfaceContainer = Color(0xFF2E3033), surfaceContainerHigh = Color(0xFF2E3033),
    surfaceContainerHighest = Color(0xFF4C4E51),
    outline = Color(0xFF8B8D91), outlineVariant = Color(0xFF4C4E51),
    inverseSurface = Color(0xFFFCFCFC), inverseOnSurface = Color(0xFF2E3033),
    inversePrimary = Color(0xFF1A6FCD),
    error = Color(0xFFFF9891), onError = Color(0xFF121417),
    errorContainer = Color(0xFF53110E), onErrorContainer = Color(0xFFFFD6D3),
    scrim = Color(0xFF121417),
)

private fun phoneTypography(fontFamily: FontFamily): Typography {
    fun text(size: Int, height: Int, spacing: Double, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = fontFamily, fontWeight = weight,
        fontSize = size.sp, lineHeight = height.sp, letterSpacing = spacing.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    return Typography(
        headlineMedium = text(28, 34, 0.64, FontWeight.Medium), // OEM h1
        headlineSmall = text(24, 28, 0.48, FontWeight.Medium), // OEM h2
        titleLarge = text(20, 24, 0.4, FontWeight.Medium), // OEM h3
        titleMedium = text(16, 20, 0.32, FontWeight.Medium), // OEM subtitle1
        titleSmall = text(14, 18, 0.28, FontWeight.Medium), // OEM subtitle2
        bodyLarge = text(16, 24, 0.32), // OEM body1
        bodyMedium = text(14, 20, 0.28), // OEM body2
        bodySmall = text(12, 18, 0.24), // OEM caption
        labelLarge = text(16, 20, 0.32, FontWeight.Medium), // OEM button1
        labelMedium = text(14, 16, 0.28, FontWeight.Medium), // OEM button2
        labelSmall = text(11, 14, 0.22, FontWeight.Medium), // OEM label
    )
}

// The modern OEM theme leaves Material 3 shapes at their defaults.
private val PhoneShapes = Shapes()

/** Phone presentation only; screens keep using MaterialTheme roles when the visual style changes. */
@Composable
fun OpenRideSyncTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fontFamily = oemUiFontFamily()
    val typography = remember(fontFamily) { phoneTypography(fontFamily) }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = typography,
        shapes = PhoneShapes,
        content = content,
    )
}
