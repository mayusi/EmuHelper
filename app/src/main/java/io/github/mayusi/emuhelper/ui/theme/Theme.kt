package io.github.mayusi.emuhelper.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** User-chosen theme preference. SYSTEM follows the OS dark-mode setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// "Modern dark, vibrant accent" — near-black canvas, electric violet accent with
// cyan + green supports. Tuned for OLED and a modern media-app feel.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),            // electric violet
    onPrimary = Color(0xFF12101F),
    primaryContainer = Color(0xFF2A2350),   // accent-tinted card highlight
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFF22D3EE),          // cyan
    onSecondary = Color(0xFF06121A),
    secondaryContainer = Color(0xFF12303A),
    onSecondaryContainer = Color(0xFFB8F1FB),
    tertiary = Color(0xFF34D399),           // green = success / done
    onTertiary = Color(0xFF06231A),
    background = Color(0xFF0B0B12),          // near-black
    onBackground = Color(0xFFE7E7F2),
    surface = Color(0xFF15151F),            // raised cards
    onSurface = Color(0xFFE7E7F2),
    surfaceVariant = Color(0xFF23232F),     // tracks, chips, dividers
    onSurfaceVariant = Color(0xFFA9A9BC),
    outline = Color(0xFF3A3A4A),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF1A0610)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6246EA),            // violet (darker for light bg)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF221B4D),
    secondary = Color(0xFF0E8FA8),          // teal
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFEFF6),
    onSecondaryContainer = Color(0xFF053640),
    tertiary = Color(0xFF0E9F6E),           // green
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F6FB),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFECECF3),
    onSurfaceVariant = Color(0xFF5A5A6A),
    outline = Color(0xFFC6C6D2),
    error = Color(0xFFD6204A),
    onError = Color(0xFFFFFFFF)
)

// Rounder, more modern shapes than the M3 defaults.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Bolder, larger headers for a punchier feel; body text left to M3 defaults.
private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun EmuHelperTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Legacy parameter kept for backward-compatibility; themeMode takes precedence.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // statusBarColor/navigationBarColor are deprecated on API 35+; with
            // enableEdgeToEdge() the system handles bar colors automatically, we
            // only need to flip the icon-appearance flags so they stay readable.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
