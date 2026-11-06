package com.lifetrack.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifetrack.app.R
import com.lifetrack.app.data.AppSettings
import com.lifetrack.app.ui.components.LocalUiPreferences
import com.lifetrack.app.ui.components.UiPreferences

// ---------------------------------------------------------------------------
// The Personal Almanac — palette (docs/design/2026-11-07-almanac-mapping.md §1)
// Daybook (light) and Nocturnal Ledger (dark) token tables, mapped onto the
// Material 3 color scheme roles. Flat Folio: hairline outlines, no elevation.
// ---------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    // Deep Bookbinder Ink: primary text and ink actions; onPrimary is Archival Paper.
    primary = Color(0xFF1C1A17), onPrimary = Color(0xFFFFF8F2),
    primaryContainer = Color(0xFFF3EFE6), onPrimaryContainer = Color(0xFF1C1A17),
    // Sage: completion, progress fill, positive markers.
    secondary = Color(0xFF059669), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2E5D4), onSecondaryContainer = Color(0xFF034A33),
    // Ochre: skip/anchor/caution.
    tertiary = Color(0xFFD97706), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFF1D7), onTertiaryContainer = Color(0xFF7C3E02),
    background = Color(0xFFFFF8F2), onBackground = Color(0xFF1C1A17),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1A17),
    surfaceVariant = Color(0xFFF3EFE6), onSurfaceVariant = Color(0xFF686258),
    // Sunken Parchment container ladder (frame surface-container values).
    surfaceDim = Color(0xFFE2D9CC),
    surfaceBright = Color(0xFFFFF8F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2E5),
    surfaceContainer = Color(0xFFF6EDE0),
    surfaceContainerHigh = Color(0xFFF0E7DA),
    surfaceContainerHighest = Color(0xFFEAE1D5),
    outline = Color(0xFF7C766E),
    // Crisp Hairline for card/row/dialog borders and quiet rules.
    outlineVariant = Color(0xFFD8D0C3),
    inverseSurface = Color(0xFF343027), inverseOnSurface = Color(0xFFF9EFE2), inversePrimary = Color(0xFFCBC5C0),
    // Terracotta-adjacent red is reserved for destructive actions only.
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF93000A)
)
private val DarkColors = darkColorScheme(
    // Parchment Inscription: ink actions invert to light on Obsidian Paper.
    primary = Color(0xFFECE7DF), onPrimary = Color(0xFF151412),
    primaryContainer = Color(0xFF343027), onPrimaryContainer = Color(0xFFECE7DF),
    // Luminous Sage.
    secondary = Color(0xFFA4D7B5), onSecondary = Color(0xFF0B2A1C),
    secondaryContainer = Color(0xFF274238), onSecondaryContainer = Color(0xFFBFE5CE),
    // Luminous Ochre.
    tertiary = Color(0xFFE9C280), onTertiary = Color(0xFF2B2010),
    tertiaryContainer = Color(0xFF3A3123), onTertiaryContainer = Color(0xFFE9C280),
    background = Color(0xFF151412), onBackground = Color(0xFFECE7DF),
    surface = Color(0xFF1E1C19), onSurface = Color(0xFFECE7DF),
    surfaceVariant = Color(0xFF272420), onSurfaceVariant = Color(0xFFA39E95),
    surfaceDim = Color(0xFF151412),
    surfaceBright = Color(0xFF3A3630),
    surfaceContainerLowest = Color(0xFF171513),
    surfaceContainerLow = Color(0xFF1E1C19),
    surfaceContainer = Color(0xFF23201C),
    surfaceContainerHigh = Color(0xFF272420),
    surfaceContainerHighest = Color(0xFF2C2823),
    outline = Color(0xFF7D766B),
    outlineVariant = Color(0xFF33302B),
    inverseSurface = Color(0xFFECE7DF), inverseOnSurface = Color(0xFF151412), inversePrimary = Color(0xFF686258),
    error = Color(0xFFFFA387), errorContainer = Color(0xFF4A2C22), onError = Color(0xFF151412), onErrorContainer = Color(0xFFFFA387)
)

/** Accents the M3 scheme cannot carry (missed terracotta, notebook rules, decorative faded ink). */
data class AlmanacColors(
    val terracotta: Color,
    val terracottaWash: Color,
    val rule: Color,
    val grid: Color,
    val faded: Color
)

private val LightAlmanac = AlmanacColors(
    terracotta = Color(0xFFC2410C), terracottaWash = Color(0xFFFCECE8),
    rule = Color(0xFFE5DFD5), grid = Color(0xFFEEE8DE), faded = Color(0xFF8C8479)
)
private val DarkAlmanac = AlmanacColors(
    terracotta = Color(0xFFF0A288), terracottaWash = Color(0xFF3A2A24),
    rule = Color(0xFF33302B), grid = Color(0xFF282520), faded = Color(0xFF7D766B)
)

/** Defaults to the light Daybook accents so previews and plain-MaterialTheme test harnesses render. */
val LocalAlmanac = staticCompositionLocalOf { LightAlmanac }

@Composable
fun almanacColors(): AlmanacColors = LocalAlmanac.current

// ---------------------------------------------------------------------------
// Bundled variable fonts (res/font, OFL). minSdk 26 + Compose BOM 2025.05.01
// allow FontVariation.Settings; each weight is declared as its own instance.
// Newsreader ships upright only — the frames' italic sublines render upright.
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object AlmanacFonts {
    private fun newsreader(weight: FontWeight) = Font(
        R.font.newsreader_variable, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )
    private fun jakarta(weight: FontWeight) = Font(
        R.font.plusjakartasans_variable, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )
    private fun jetbrains(weight: FontWeight) = Font(
        R.font.jetbrainsmono_variable, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )

    val display: FontFamily = FontFamily(
        newsreader(FontWeight.Normal), newsreader(FontWeight.Medium),
        newsreader(FontWeight.SemiBold), newsreader(FontWeight.Bold)
    )
    val body: FontFamily = FontFamily(
        jakarta(FontWeight.Normal), jakarta(FontWeight.Medium),
        jakarta(FontWeight.SemiBold), jakarta(FontWeight.Bold)
    )
    val mono: FontFamily = FontFamily(
        jetbrains(FontWeight.Normal), jetbrains(FontWeight.Medium), jetbrains(FontWeight.SemiBold)
    )
}

/** Extra roles beyond the Material slots: label-caps running headers and mono numerals. */
object AlmanacType {
    /** Eyebrow / section headers. Strings keep their existing casing (no visual-only uppercasing). */
    val labelCaps = TextStyle(
        fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.9.sp
    )

    /** Big figures: day summary, streak wells, metric tiles. Tabular by design. */
    val monoMetric = TextStyle(
        fontFamily = AlmanacFonts.mono, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    )

    /** Row numerals: counts, dates, quantities. */
    val monoData = TextStyle(
        fontFamily = AlmanacFonts.mono, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    )

    /** Decorative stamps and seals only — never functional copy. */
    val monoCaption = TextStyle(
        fontFamily = AlmanacFonts.mono, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
}

private val AlmanacTypography = Typography(
    displayLarge = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.9).sp),
    displayMedium = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    displaySmall = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = AlmanacFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    bodySmall = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AlmanacFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

/** Sunken parchment inputs: 1dp hairline border, ink focus rule, sage cursor ring. */
@Composable
fun almanacFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    cursorColor = MaterialTheme.colorScheme.secondary,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/** Flat Folio: small radii only — 4/6/8/10dp; circular seals stay explicit in components. */
private val AlmanacShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

@Composable
fun LifeTrackTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalUiPreferences provides UiPreferences(settings.hapticsEnabled, settings.animationsEnabled),
        LocalAlmanac provides if (dark) DarkAlmanac else LightAlmanac
    ) {
        MaterialTheme(colorScheme = colors, typography = AlmanacTypography, shapes = AlmanacShapes, content = content)
    }
}
