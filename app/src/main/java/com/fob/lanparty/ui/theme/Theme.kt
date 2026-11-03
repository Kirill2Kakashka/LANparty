package com.fob.lanparty.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = LanGreen,
    onPrimary = Color.White,
    primaryContainer = LanMint,
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF496455),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE9D8),
    onSecondaryContainer = Color(0xFF072015),
    tertiary = Color(0xFF3E6373),
    onTertiary = Color.White,
    background = LanSurface,
    onBackground = Color(0xFF172019),
    surface = LanSurface,
    onSurface = Color(0xFF172019),
    surfaceVariant = Color(0xFFE2E8E2),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF737B73),
    outlineVariant = Color(0xFFC2C9C2),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD9AC),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0B5230),
    onPrimaryContainer = Color(0xFFB4F5CE),
    secondary = Color(0xFFB0D0BD),
    onSecondary = Color(0xFF1B3826),
    secondaryContainer = Color(0xFF324F3C),
    onSecondaryContainer = Color(0xFFCCECD8),
    tertiary = Color(0xFFA6CCDD),
    onTertiary = Color(0xFF083544),
    background = LanDarkSurface,
    onBackground = Color(0xFFE1E8E2),
    surface = LanDarkSurface,
    onSurface = Color(0xFFE1E8E2),
    surfaceVariant = Color(0xFF3F4741),
    onSurfaceVariant = Color(0xFFBFC8C0),
    outline = Color(0xFF89928B),
    outlineVariant = Color(0xFF3F4741),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun LanPartyTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LanTypographyValue,
        shapes = LanShapesValue,
        content = content,
    )
}
