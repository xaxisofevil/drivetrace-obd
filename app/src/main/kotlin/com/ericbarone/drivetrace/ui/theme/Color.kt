package com.ericbarone.drivetrace.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DriveTrace's colour vocabulary. See docs/DESIGN_SYSTEM.md for the reasoning behind every value;
 * this file and `Skin.kt` are the machine-readable copy of that document, not an independent
 * source of truth.
 *
 * Two colour channels exist and they never borrow from each other:
 *
 *  1. CATEGORY  - which vehicle system a number belongs to (mixture, airpath, thermal,
 *     ignition, motion). Answers "what am I looking at" in one glance, without reading a label.
 *  2. STATUS    - whether something is nominal, degraded, or broken. Only three colours, and
 *     every use is paired with a glyph or a filled bar so colour is never the sole carrier.
 *
 * Keeping them separate is what stops "coolant temperature is orange" from being confused with
 * "coolant temperature is in trouble".
 *
 * ---------------------------------------------------------------------------
 * **Why these are properties with composable getters and not `val`s any more.**
 *
 * They used to be top-level `val`s holding literal hex. The literals moved into [Skin]; the names
 * stayed exactly as they were, and each one now reads its value off [LocalSkin]. Nothing that
 * consumes a token had to change: `Text(color = Chalk)` inside a composable still compiles,
 * still reads the same, and now follows whichever skin is active.
 *
 * That is the entire abstraction, and it is deliberately the smallest one that works. A theme
 * object (`DriveTraceTheme.colors.chalk`) would have meant touching every colour reference on
 * three screens and in the whole component vocabulary, and would have made every future screen
 * pay a prefix forever to support a feature most users will never turn on.
 *
 * The one constraint it imposes: **a token can only be read from composable code.** A helper that
 * picks a colour has to be `@Composable` (see `heroFigure` in LoggingScreen.kt), which is honest
 * anyway, since "what colour is this" genuinely depends on composition state now. Anything that
 * needs a colour outside composition should take the [Skin] explicitly.
 *
 * `@ReadOnlyComposable` on every getter is what keeps this free: the read does not open a group
 * in the composition, so a token reference costs the same as the `val` it replaced.
 */

// ---------------------------------------------------------------------------
// Surfaces. A deep near-black rather than #000000: pure black smears on OLED during
// scroll and makes the hairline borders that define a panel edge impossible to see.
// ---------------------------------------------------------------------------

/** Window background. */
val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.ink

/** Standard panel / card fill. */
val Panel: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.panel

/** A tile sitting inside a panel; one step up so nesting reads without a shadow. */
val PanelRaised: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.panelRaised

/** Pressed / selected panel fill. */
val PanelActive: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.panelActive

/** 1dp rules, panel borders, dividers. Elevation is expressed as a bezel line, not a shadow. */
val Hairline: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.hairline

/** Brighter hairline for the border of a selected/active panel. */
val HairlineBright: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.hairlineBright

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

/** Primary readouts and headings. >=18:1 on Ink in every skin. */
val Chalk: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.chalk

/** Body copy, secondary values. ~9:1 on Ink. */
val Mist: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.mist

/** Field labels, units, captions. >=5.4:1 on Ink; only used at >=11sp semibold. */
val Ash: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.ash

/** Disabled text and inactive glyphs. Deliberately below body contrast: unavailable should
 *  look unavailable. */
val Slate: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.slate

// ---------------------------------------------------------------------------
// Category accents. Six is the ceiling: hue stops working as a pre-attentive channel
// somewhere around eight simultaneous categories (Ware, Information Visualization), and
// this app only has five systems worth distinguishing plus a deliberate non-colour.
//
// Every skin has to keep all six mutually distinguishable, which is a measurable claim and is
// measured rather than eyeballed; see docs/DESIGN_SYSTEM.md section 3.5.
// ---------------------------------------------------------------------------

/**
 * MOTION - RPM, vehicle speed, engine load, absolute load, throttle position.
 * Deliberately achromatic, in every skin. The primary instrument in a cluster or a glass cockpit
 * is white on black, because maximum luminance contrast is the fastest thing the eye resolves,
 * and because this category always owns the hero slot, so position already identifies it.
 * Spending a hue here would waste the one channel the diagnostic subsystems actually need.
 */
val AccentMotion: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentMotion

/** MIXTURE - short/long term fuel trims, equivalence ratio, MAF, fuel rate, fuel level, MPG.
 *  Teal in both skins, inheriting DriveTrace's original: fuel trim is the signal this app exists
 *  to chase, so the brand colour and the flagship data category are the same colour on purpose. */
val AccentMixture: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentMixture

/** AIRPATH - intake manifold pressure, desired MAP, turbo compressor inlet, barometric. */
val AccentAirpath: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentAirpath

/** THERMAL - coolant, intake air, oil, catalyst, ambient. Blue because the cold-engine
 *  telltale is blue in every OEM cluster; heat is then expressed by the value crossing a
 *  threshold into the status channel, not by the category hue itself. */
val AccentThermal: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentThermal

/** IGNITION - timing advance, knock retard, knock control system. Farthest hue from every
 *  other accent and from the alarm band. */
val AccentIgnition: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentIgnition

/** HOUSEKEEPING - Tier C bookkeeping (runtime, distance since codes cleared, EGR, module
 *  voltage). No hue on purpose, in every skin: the hue budget belongs to numbers worth
 *  glancing at. */
val AccentHousekeeping: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentHousekeeping

// ---------------------------------------------------------------------------
// Status channel. Never used as a category accent.
// Normal is achromatic (no green "everything is fine" wash); colour means attention.
// ---------------------------------------------------------------------------

/** Confirmed-good: the live-data heartbeat dot, a verified upload. Used sparingly. */
val StatusLive: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusLive

/** Degraded but not broken: stale samples, pending upload, an analysis flag. */
val StatusCaution: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusCaution

/** Broken: failed connection, failed upload, no real data from the vehicle. */
val StatusFault: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusFault

/** Unknown / still resolving. */
val StatusUnknown: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusUnknown

/** Low-alpha fills for status bands and chips, so a full-width alert reads as tinted glass
 *  over the panel rather than a solid block of alarm colour. */
val StatusLiveFill: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusLiveFill

val StatusCautionFill: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusCautionFill

val StatusFaultFill: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.statusFaultFill

val AccentMixtureFill: Color
    @Composable @ReadOnlyComposable get() = LocalSkin.current.accentMixtureFill

// ---------------------------------------------------------------------------
// Daylight variants. Explicitly NOT a light theme, and explicitly not a third skin.
//
// The dark-first decision (see Theme.kt and docs/DESIGN_SYSTEM.md section 2) is about night
// driving and holds regardless of a user toggle: a bright panel at cabin-forward brightness
// wrecks dark adaptation and reflects off the windscreen. Direct sun is the opposite failure,
// and the fix for it is not inverting the display, it is spending more luminance on the one
// thing that has to survive a two-second glance. So Ink stays the ground in every skin and in
// both modes, and only the hero readout's ink gets brighter.
//
// The twins themselves live on the [Skin] (Skin.kt) and the mapping below is built from
// whichever skin is active, so the boost is relative to the running skin rather than pinned to
// the default one. A new skin gets a working daylight mode by supplying five more colours; it
// does not get to reimplement the mode.
// ---------------------------------------------------------------------------

/**
 * The mapping a hero readout runs its colours through, threaded down as a CompositionLocal
 * exactly like [LocalReadoutType], so the daylight mode is one provider swap at the theme root
 * rather than an `if (highContrast)` at every call site.
 *
 * Unknown colours pass through untouched, which is the graceful part: a future accent that
 * forgets to register a daylight twin renders at its normal luminance instead of crashing or
 * rendering as something unrelated.
 */
data class ReadoutPalette(
    /** Colour of the hero's uppercase field legend. */
    val heroLabel: Color,
    /** Colour of the hero's provenance caption. */
    val heroCaption: Color,
    private val heroAccents: Map<Color, Color> = emptyMap(),
) {
    fun hero(accent: Color): Color = heroAccents[accent] ?: accent
}

/** Defaulted to the default skin's standard palette so a preview or a stray composable outside
 *  [DriveTraceTheme] renders in something coherent rather than in nothing. */
val LocalReadoutPalette = staticCompositionLocalOf {
    SkinId.INSTRUMENT.skin.readoutPalette(highContrast = false)
}
