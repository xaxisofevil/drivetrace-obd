package com.ericbarone.drivetrace.ui.theme

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
