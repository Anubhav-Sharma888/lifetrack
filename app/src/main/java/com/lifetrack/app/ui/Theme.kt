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

private val LightColors = lightColorScheme(
    primary = Color(0xFF427C62), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EFE5), onPrimaryContainer = Color(0xFF183D2B),
    secondary = Color(0xFF6A756D), background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEEF1EB),
    outlineVariant = Color(0xFFE1E5DD)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5D5B8), primaryContainer = Color(0xFF294E38),
    background = Color(0xFF151916), surface = Color(0xFF1D231E),
    surfaceVariant = Color(0xFF2A332C), outlineVariant = Color(0xFF3A443D)
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
