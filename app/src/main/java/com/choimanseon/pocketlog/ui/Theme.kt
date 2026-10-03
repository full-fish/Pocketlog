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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.choimanseon.pocketlog.R

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

// Pretendard (기획서 §6): one variable font file, so every weight is the same file at a different axis value.
private fun pretendard(weight: FontWeight) =
    Font(R.font.pretendard, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val Pretendard = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map(::pretendard))

// Tabular figures so amounts line up in columns and don't jiggle while animating.
private fun TextStyle.p(size: Int, weight: FontWeight? = null) =
    copy(fontFamily = Pretendard, fontSize = size.sp, fontWeight = weight ?: fontWeight, fontFeatureSettings = "tnum")

private val typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Pretendard),
        displayMedium = t.displayMedium.copy(fontFamily = Pretendard),
        displaySmall = t.displaySmall.p(34, FontWeight.Bold).copy(lineHeight = 40.sp, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontFamily = Pretendard),
        headlineMedium = t.headlineMedium.copy(fontFamily = Pretendard),
        headlineSmall = t.headlineSmall.p(24, FontWeight.Bold),
        titleLarge = t.titleLarge.p(21, FontWeight.Bold),
        titleMedium = t.titleMedium.p(17, FontWeight.SemiBold),
        titleSmall = t.titleSmall.p(15, FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.p(16),
        bodyMedium = t.bodyMedium.p(15),
        bodySmall = t.bodySmall.p(13),
        labelLarge = t.labelLarge.p(15, FontWeight.SemiBold),
        labelMedium = t.labelMedium.p(13),
        labelSmall = t.labelSmall.p(11),
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
