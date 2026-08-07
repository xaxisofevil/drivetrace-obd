package com.ericbarone.drivetrace.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DriveTrace palette. See docs/DESIGN_SYSTEM.md for the reasoning behind every value here;
 * this file is the machine-readable copy of that document, not an independent source of truth.
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
 */

// ---------------------------------------------------------------------------
// Surfaces. Deep blue-black rather than #000000: pure black smears on OLED during
// scroll and makes the hairline borders that define a panel edge impossible to see.
// ---------------------------------------------------------------------------

/** Window background. */
val Ink = Color(0xFF06090E)

/** Standard panel / card fill. */
val Panel = Color(0xFF0D131B)

/** A tile sitting inside a panel; one step up so nesting reads without a shadow. */
val PanelRaised = Color(0xFF141C26)

/** Pressed / selected panel fill. */
val PanelActive = Color(0xFF1A2431)

/** 1dp rules, panel borders, dividers. Elevation is expressed as a bezel line, not a shadow. */
val Hairline = Color(0xFF202B39)

/** Brighter hairline for the border of a selected/active panel. */
val HairlineBright = Color(0xFF33455A)

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

/** Primary readouts and headings. ~18:1 on Ink. */
val Chalk = Color(0xFFF2F6FA)

/** Body copy, secondary values. ~9:1 on Ink. */
val Mist = Color(0xFF9AA9BA)

/** Field labels, units, captions. ~5.4:1 on Ink; only used at >=11sp semibold. */
val Ash = Color(0xFF7C8B9E)

/** Disabled text and inactive glyphs. Deliberately below body contrast: unavailable should
 *  look unavailable. */
val Slate = Color(0xFF4A5768)

// ---------------------------------------------------------------------------
// Category accents. Six is the ceiling: hue stops working as a pre-attentive channel
// somewhere around eight simultaneous categories (Ware, Information Visualization), and
// this app only has five systems worth distinguishing plus a deliberate non-colour.
// ---------------------------------------------------------------------------

/**
 * MOTION - RPM, vehicle speed, engine load, absolute load, throttle position.
 * Deliberately achromatic. The primary instrument in a cluster or a glass cockpit is white on
 * black, because maximum luminance contrast is the fastest thing the eye resolves, and because
 * this category always owns the hero slot, so position already identifies it. Spending a hue
 * here would waste the one channel the diagnostic subsystems actually need.
 */
val AccentMotion = Chalk

/** MIXTURE - short/long term fuel trims, equivalence ratio, MAF, fuel rate, fuel level, MPG.
 *  Inherits DriveTrace's original teal: fuel trim is the signal this app exists to chase. */
val AccentMixture = Color(0xFF2ED3C6)

/** AIRPATH - intake manifold pressure, desired MAP, turbo compressor inlet, barometric. */
val AccentAirpath = Color(0xFF8E7BFF)

/** THERMAL - coolant, intake air, oil, catalyst, ambient. Blue because the cold-engine
 *  telltale is blue in every OEM cluster; heat is then expressed by the value crossing a
 *  threshold into the status channel, not by the category hue itself. */
val AccentThermal = Color(0xFF5AC8FA)

/** IGNITION - timing advance, knock retard, knock control system. Farthest hue from every
 *  other accent and from the alarm band. */
val AccentIgnition = Color(0xFFFF66C4)

/** HOUSEKEEPING - Tier C bookkeeping (runtime, distance since codes cleared, EGR, module
 *  voltage). No hue on purpose: the hue budget belongs to numbers worth glancing at. */
val AccentHousekeeping = Ash

// ---------------------------------------------------------------------------
// Status channel. Never used as a category accent.
// Normal is achromatic (no green "everything is fine" wash); colour means attention.
// ---------------------------------------------------------------------------

/** Confirmed-good: the live-data heartbeat dot, a verified upload. Used sparingly. */
val StatusLive = Color(0xFF31C56A)

/** Degraded but not broken: stale samples, pending upload, an analysis flag. */
val StatusCaution = Color(0xFFFFC53D)

/** Broken: failed connection, failed upload, no real data from the vehicle. */
val StatusFault = Color(0xFFFF4D4F)

/** Unknown / still resolving. */
val StatusUnknown = Slate

/** Low-alpha fills for status bands and chips, so a full-width alert reads as tinted glass
 *  over the panel rather than a solid block of alarm colour. */
val StatusLiveFill = Color(0x1A31C56A)
val StatusCautionFill = Color(0x1AFFC53D)
val StatusFaultFill = Color(0x1FFF4D4F)
val AccentMixtureFill = Color(0x1A2ED3C6)

// ---------------------------------------------------------------------------
// Daylight variants. Explicitly NOT a light theme.
//
// The dark-first decision (see Theme.kt and docs/DESIGN_SYSTEM.md section 2) is about night
// driving and holds regardless of a user toggle: a bright panel at cabin-forward brightness
// wrecks dark adaptation and reflects off the windscreen. Direct sun is the opposite failure,
// and the fix for it is not inverting the display, it is spending more luminance on the one
// thing that has to survive a two-second glance. So Ink stays the ground in both modes and only
// the hero readout's ink gets brighter.
//
// Each value below is the same hue as its standard twin, lifted toward white rather than
// re-picked: the category contract (mixture is teal forever, thermal is blue forever) has to
// survive the mode change or the pre-attentive channel breaks the moment a user toggles it.
// ---------------------------------------------------------------------------

/** Boosted MOTION / primary readout. The one place pure white is allowed, and only on a
 *  64sp numeral: the smearing problem that rules #000000 out as a surface doesn't apply to
 *  foreground text. ~21:1 on Ink. */
val DaylightChalk = Color(0xFFFFFFFF)

/** Boosted MIXTURE. */
val DaylightMixture = Color(0xFF7DF5E8)

/** Boosted AIRPATH. */
val DaylightAirpath = Color(0xFFC0B4FF)

/** Boosted THERMAL. */
val DaylightThermal = Color(0xFFA8E4FF)

/** Boosted IGNITION. */
val DaylightIgnition = Color(0xFFFFA8DC)

/**
 * The mapping a hero readout runs its colours through, threaded down as a CompositionLocal
 * exactly like [LocalReadoutType], so the daylight mode is one provider swap at the theme root
 * rather than a `if (highContrast)` at every call site.
 *
 * Unknown colours pass through untouched, which is the graceful part: a future accent that
 * forgets to register a daylight twin renders at its normal luminance instead of crashing or
 * rendering as something unrelated.
 */
data class ReadoutPalette(
    /** Colour of the hero's uppercase field legend. */
    val heroLabel: Color = Ash,
    /** Colour of the hero's provenance caption. */
    val heroCaption: Color = Slate,
    private val heroAccents: Map<Color, Color> = emptyMap(),
) {
    fun hero(accent: Color): Color = heroAccents[accent] ?: accent
}

/** Identity. Every colour renders exactly as the palette above defines it. */
val StandardReadoutPalette = ReadoutPalette()

/**
 * Direct-sun variant. Note `Slate to Ash`: the hero renders in Slate when there is no value to
 * show ("--"), and at 2.2:1 that is genuinely invisible outdoors, so even the absence of a
 * number has to survive. Ash also replaces Slate on the caption for the same reason.
 */
val DaylightReadoutPalette = ReadoutPalette(
    heroLabel = Mist,
    heroCaption = Mist,
    heroAccents = mapOf(
        // AccentMotion is Chalk and AccentHousekeeping is Ash, so those two tokens are keyed by
        // their underlying colour; adding both names would be a duplicate map key.
        Chalk to DaylightChalk,
        Ash to Mist,
        AccentMixture to DaylightMixture,
        AccentAirpath to DaylightAirpath,
        AccentThermal to DaylightThermal,
        AccentIgnition to DaylightIgnition,
        Slate to Ash,
    ),
)

val LocalReadoutPalette = staticCompositionLocalOf { StandardReadoutPalette }
