package com.lifetrack.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lifetrack.app.data.AppSettings
import com.lifetrack.app.ui.components.LocalUiPreferences
import com.lifetrack.app.ui.components.UiPreferences

// Flight Recorder palette: warm instrument paper, ink type, electric orange markers.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B1710), onPrimary = Color(0xFFF5F0E4),
    primaryContainer = Color(0xFFEDE5D2), onPrimaryContainer = Color(0xFF1B1710),
    secondary = Color(0xFFD9571B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE9DC), onSecondaryContainer = Color(0xFF8A3D10),
    tertiary = Color(0xFFD9571B), onTertiary = Color.White,
    background = Color(0xFFF5F0E4), onBackground = Color(0xFF1B1710),
    surface = Color(0xFFFCF9F0), onSurface = Color(0xFF1B1710),
    surfaceVariant = Color(0xFFEDE5D2), onSurfaceVariant = Color(0xFF6E6552),
    outlineVariant = Color(0xFFE0D7C2),
    error = Color(0xFFA63A22), errorContainer = Color(0xFFF6E2DA), onError = Color.White, onErrorContainer = Color(0xFF6B2415)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1E8D4), onPrimary = Color(0xFF1B1710),
    primaryContainer = Color(0xFF322C21), onPrimaryContainer = Color(0xFFF1E8D4),
    secondary = Color(0xFFFF7A33), onSecondary = Color(0xFF1B1710),
    secondaryContainer = Color(0xFF453322), onSecondaryContainer = Color(0xFFFFB287),
    tertiary = Color(0xFFFF7A33), onTertiary = Color(0xFF1B1710),
    background = Color(0xFF1B1813), onBackground = Color(0xFFF1E8D4),
    surface = Color(0xFF26221A), onSurface = Color(0xFFF1E8D4),
    surfaceVariant = Color(0xFF322C21), onSurfaceVariant = Color(0xFFA3987F),
    outlineVariant = Color(0xFF3B3528),
    error = Color(0xFFFFA387), errorContainer = Color(0xFF4A2C22), onError = Color(0xFF1B1710), onErrorContainer = Color(0xFFFFA387)
)

@Composable
fun LifeTrackTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalUiPreferences provides UiPreferences(settings.hapticsEnabled, settings.animationsEnabled)) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
