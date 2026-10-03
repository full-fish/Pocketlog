package com.choimanseon.pocketlog.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens from 기획서 §6. Expense amounts use `text`; red is only for over-budget and destructive actions. */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val sub: Color,
    val faint: Color,
    val brand: Color,
    val brandSoft: Color,
    val income: Color,
    val warn: Color,
    val danger: Color,
    val divider: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF4F5F7), surface2 = Color(0xFFE9EBEF),
    text = Color(0xFF111318), sub = Color(0xFF6B7280), faint = Color(0xFFA0A6B1),
    brand = Color(0xFF4C5BF5), brandSoft = Color(0xFFEDEFFE), income = Color(0xFF12B886),
    warn = Color(0xFFFF9F1C), danger = Color(0xFFF04452), divider = Color(0xFFEEF0F3), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0F1115), surface = Color(0xFF1A1D23), surface2 = Color(0xFF252932),
    text = Color(0xFFF2F3F5), sub = Color(0xFF9CA3AF), faint = Color(0xFF6B7280),
    brand = Color(0xFF7C87FF), brandSoft = Color(0xFF23284A), income = Color(0xFF38D9A9),
    warn = Color(0xFFFFB347), danger = Color(0xFFFF6B76), divider = Color(0xFF23262D), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val pal: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

// Tabular figures so amounts line up in columns and don't jiggle while animating.
private const val TNUM = "tnum"

private val typography = Typography().let { t ->
    Typography(
        displaySmall = t.displaySmall.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = TNUM),
        headlineSmall = t.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM),
        titleLarge = t.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM),
        titleMedium = t.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
        titleSmall = t.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, fontFeatureSettings = TNUM),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, fontFeatureSettings = TNUM),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, fontFeatureSettings = TNUM),
        labelLarge = t.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp, fontFeatureSettings = TNUM),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = TNUM),
    )
}

@Composable
fun PocketTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.brand, onPrimary = Color.White, primaryContainer = p.brandSoft, onPrimaryContainer = p.brand,
        secondaryContainer = p.brandSoft, onSecondaryContainer = p.brand,
        background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text, onSurfaceVariant = p.sub,
        surfaceVariant = p.surface, surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2,
        outline = p.faint, outlineVariant = p.divider, error = p.danger,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // no Surface at the root, so set the default text/icon color here (otherwise black in dark mode)
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
