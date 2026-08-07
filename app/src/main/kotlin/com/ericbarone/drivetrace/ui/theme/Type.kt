package com.ericbarone.drivetrace.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Typography. See docs/DESIGN_SYSTEM.md.
 *
 * The single most important line in this file is [TABULAR]. By default Roboto sets digits with
 * proportional widths, so a "1" is narrower than a "0" and a readout that ticks from 1899 to
 * 2000 RPM physically changes width, dragging every digit sideways. At a glance that reads as
 * motion where there is none. Roboto ships the OpenType `tnum` feature, which forces every digit
 * onto the same advance width; Compose exposes it through TextStyle.fontFeatureSettings. Every
 * style that can ever contain a changing number sets it.
 *
 * No font file is bundled. Roboto is the platform font, its metrics are known, and shipping a
 * webfont for a dashboard that has to render instantly on a cold start buys nothing.
 */
private const val TABULAR = "tnum"

/**
 * Readout styles. Material 3 has no type slot for a 64sp tabular numeral, so these live outside
 * the Typography object and are reached through [LocalReadoutType].
 */
data class ReadoutTypography(
    /** The one number that owns the screen. Light weight: at this size a regular weight turns
     *  into a wall of ink, and thin large numerals are what instrument clusters actually use. */
    val hero: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = TABULAR,
    ),
    /** Secondary hero: the second-most-glanced number on a screen. */
    val large: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR,
    ),
    /** The value inside a metric tile. */
    val medium: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    /** Inline values in a dense key/value row. */
    val small: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    ),
    /**
     * Field label. Small, heavy, wide-tracked, always set in caps. This is the legend engraved
     * on a bezel: it must be identifiable without being readable, so it can shrink far below
     * body size without competing with the number it names.
     */
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.3.sp,
    ),
    /** Unit suffix. Always a separate Text from the numeral so the numeral's own width and
     *  baseline never shift when the unit string changes. */
    val unit: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    /** Machine output: adapter MAC addresses, session IDs, the live status line. Monospaced
     *  because these are identifiers to compare character by character, not prose to read. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    /** Screen title in the header bar. */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    /** The DRIVETRACE wordmark. Caps, heavily tracked. */
    val wordmark: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 3.5.sp,
    ),
)

val LocalReadoutType = staticCompositionLocalOf { ReadoutTypography() }

/**
 * Material 3 slots, so stock components (Button, AlertDialog, RadioButton labels) inherit the
 * system instead of falling back to the Material baseline. Numeric-capable slots carry `tnum`
 * for the same reason the readouts do.
 */
val DriveTraceTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Light, fontSize = 52.sp, lineHeight = 56.sp,
        letterSpacing = (-1).sp, fontFeatureSettings = TABULAR,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp,
        letterSpacing = 0.1.sp, fontFeatureSettings = TABULAR,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp,
        letterSpacing = 0.1.sp, fontFeatureSettings = TABULAR,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp,
        letterSpacing = 0.1.sp, fontFeatureSettings = TABULAR,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp,
    ),
)

/** Centre-aligned variant helper, used by empty states. */
internal fun TextStyle.centered(): TextStyle = copy(textAlign = TextAlign.Center)
