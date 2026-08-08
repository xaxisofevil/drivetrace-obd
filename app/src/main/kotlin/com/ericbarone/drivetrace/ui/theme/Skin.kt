package com.ericbarone.drivetrace.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * An instrument skin: one complete set of colour tokens.
 *
 * Everything in `Color.kt` used to be a top-level `val` with a literal hex in it. Those names are
 * still the vocabulary every screen speaks (`Ink`, `Chalk`, `AccentMixture`), but they now read
 * their value off whichever [Skin] is provided at the theme root, so a second visual identity is
 * one more instance of this class rather than a second copy of the UI. See docs/DESIGN_SYSTEM.md
 * section 3 for the tokens, section 3.5 for the skins, and section 8 for the rules any skin has
 * to keep.
 *
 * **A skin may only re-pick values; it may not change the structure.** Four things are wired into
 * the design system rather than into any one palette, and they are properties on this class rather
 * than constructor parameters precisely so a new skin cannot get them wrong:
 *
 *  - [accentMotion] is [chalk]. MOTION is the achromatic category in every skin: the hero slot is
 *    identified by position and by maximum luminance contrast, and spending a hue there would take
 *    it from the diagnostic categories that need it.
 *  - [accentHousekeeping] is [ash]. Tier C is subordinate in every skin.
 *  - [statusUnknown] is [slate].
 *  - The status fills are their status colour at a fixed low alpha, so an alert always reads as
 *    tinted glass over the panel rather than as a solid block of alarm colour.
 *
 * The daylight boost is derived here too ([readoutPalette]), which is the point of putting the
 * daylight twins in the skin rather than next to it: the direct-sun mode is a property of the
 * active skin, not a second skin, and it keeps working without a line of new code when a third
 * skin is added.
 */
@Immutable
data class Skin(
    // --- surfaces ---------------------------------------------------------
    /** Window background. Never pure black: it smears on OLED during scroll and it makes the
     *  hairline borders that define every panel edge impossible to resolve. */
    val ink: Color,
    /** Standard panel / card fill. */
    val panel: Color,
    /** A tile nested inside a panel; one step up so nesting reads without a shadow. */
    val panelRaised: Color,
    /** Selected / pressed panel fill. */
    val panelActive: Color,
    /** 1dp rules and panel borders. Elevation is a bezel line, not a shadow. */
    val hairline: Color,
    /** Border of an active or outlined control. */
    val hairlineBright: Color,

    // --- text -------------------------------------------------------------
    /** Primary readouts and headings. Floor: 18:1 on [ink]. */
    val chalk: Color,
    /** Body copy and secondary values. Floor: 9:1 on [ink]. */
    val mist: Color,
    /** Labels, units, captions. Floor: 5.4:1 on [ink], and only at >=11sp semibold. */
    val ash: Color,
    /** Disabled text and inactive glyphs. Deliberately below body contrast. */
    val slate: Color,

    // --- category accents -------------------------------------------------
    // MOTION and HOUSEKEEPING are derived (see the class doc). These four are the whole hue
    // budget, and every skin has to keep all six categories mutually distinguishable.
    /** MIXTURE, and the theme's Material `primary`. */
    val accentMixture: Color,
    /** AIRPATH. */
    val accentAirpath: Color,
    /** THERMAL. Blue in every skin: the cold-engine telltale is blue in every OEM cluster, and
     *  heat is expressed by a value crossing into the status channel, not by the category hue. */
    val accentThermal: Color,
    /** IGNITION. */
    val accentIgnition: Color,

    // --- status -----------------------------------------------------------
    /** Confirmed good. Used sparingly; there is no green "everything is fine" wash. */
    val statusLive: Color,
    /** Degraded but working. */
    val statusCaution: Color,
    /** Broken. */
    val statusFault: Color,

    // --- daylight twins ---------------------------------------------------
    // Each one is its standard twin lifted toward white, never a fresh pick: the category
    // contract has to survive the mode change or the pre-attentive hue channel breaks the moment
    // a user flips the switch.
    val daylightChalk: Color,
    val daylightMixture: Color,
    val daylightAirpath: Color,
    val daylightThermal: Color,
    val daylightIgnition: Color,
) {
    /** MOTION. Achromatic by definition, in every skin. */
    val accentMotion: Color get() = chalk

    /** HOUSEKEEPING. Subordinate by definition, in every skin. */
    val accentHousekeeping: Color get() = ash

    /** Unknown / still resolving. */
    val statusUnknown: Color get() = slate

    // 0.102f and 0.122f land on the 0x1A / 0x1F alphas the hand-written fills used before skins
    // existed, so the Instrument skin renders byte-identical to what shipped.
    val statusLiveFill: Color get() = statusLive.copy(alpha = 0.102f)
    val statusCautionFill: Color get() = statusCaution.copy(alpha = 0.102f)
    val statusFaultFill: Color get() = statusFault.copy(alpha = 0.122f)
    val accentMixtureFill: Color get() = accentMixture.copy(alpha = 0.102f)

    /**
     * The mapping [com.ericbarone.drivetrace.ui.components.HeroReadout] runs its colours through.
     *
     * Derived from this skin rather than hard-coded against the default one, which is the whole
     * reason the daylight twins live in [Skin]: swapping skins swaps both palettes with it, and
     * nothing about the direct-sun mode needs re-implementing per skin.
     */
    fun readoutPalette(highContrast: Boolean): ReadoutPalette = if (!highContrast) {
        ReadoutPalette(heroLabel = ash, heroCaption = slate)
    } else {
        ReadoutPalette(
            heroLabel = mist,
            heroCaption = mist,
            heroAccents = mapOf(
                // accentMotion is chalk and accentHousekeeping is ash, so those two categories
                // are keyed by their underlying colour; naming both would be a duplicate key.
                chalk to daylightChalk,
                ash to mist,
                accentMixture to daylightMixture,
                accentAirpath to daylightAirpath,
                accentThermal to daylightThermal,
                accentIgnition to daylightIgnition,
                // The hero renders in slate when there is no value at all, and at ~2.2:1 that is
                // genuinely invisible outdoors, so even the absence of a number has to survive.
                slate to ash,
            ),
        )
    }
}

/**
 * The skins that ship, in the order the picker lists them.
 *
 * An enum rather than a sealed hierarchy because the set is closed, small, and has to round-trip
 * through SharedPreferences as a string; `entries` is also what drives the picker, so adding a
 * skin is one entry here and nothing on SettingsScreen.
 */
enum class SkinId(val displayName: String, val description: String, val skin: Skin) {
    /**
     * The default, and what every value in this design system was derived for: a blue-black
     * ground borrowed from glass-cockpit practice, near-white primaries, and a cool accent set.
     */
    INSTRUMENT(
        displayName = "Instrument",
        description = "Blue-black glass-cockpit ground, cool accents",
        skin = Skin(
            ink = Color(0xFF06090E),
            panel = Color(0xFF0D131B),
            panelRaised = Color(0xFF141C26),
            panelActive = Color(0xFF1A2431),
            hairline = Color(0xFF202B39),
            hairlineBright = Color(0xFF33455A),
            chalk = Color(0xFFF2F6FA),
            mist = Color(0xFF9AA9BA),
            ash = Color(0xFF7C8B9E),
            slate = Color(0xFF4A5768),
            accentMixture = Color(0xFF2ED3C6),
            accentAirpath = Color(0xFF8E7BFF),
            accentThermal = Color(0xFF5AC8FA),
            accentIgnition = Color(0xFFFF66C4),
            statusLive = Color(0xFF31C56A),
            statusCaution = Color(0xFFFFC53D),
            statusFault = Color(0xFFFF4D4F),
            daylightChalk = Color(0xFFFFFFFF),
            daylightMixture = Color(0xFF7DF5E8),
            daylightAirpath = Color(0xFFC0B4FF),
            daylightThermal = Color(0xFFA8E4FF),
            daylightIgnition = Color(0xFFFFA8DC),
        ),
    ),

    /**
     * A seventies cluster: warm near-black ground, amber-phosphor primaries, and a hue budget
     * re-derived for a warm ground rather than the cool one recoloured.
     *
     * The reasoning per token is in docs/DESIGN_SYSTEM.md section 3.5. The short version: the
     * warmth is spent on the ground, the chrome and the achromatic MOTION readout, which is where
     * a real amber cluster puts it, and the four diagnostic hues then sit in the half of the hue
     * circle that ground leaves open. Every one of section 3's contrast floors is met or beaten,
     * and the six categories separate further from each other here than they do in Instrument.
     */
    AMBER(
        displayName = "Amber",
        description = "Seventies cluster. Warm ground, amber phosphor primaries",
        skin = Skin(
            ink = Color(0xFF0A0805),
            panel = Color(0xFF15100A),
            panelRaised = Color(0xFF1F1810),
            panelActive = Color(0xFF2A2015),
            hairline = Color(0xFF3A2C1B),
            hairlineBright = Color(0xFF5C4629),
            chalk = Color(0xFFFFF3E0),
            mist = Color(0xFFC7AC84),
            ash = Color(0xFFA08A65),
            slate = Color(0xFF63543B),
            accentMixture = Color(0xFF1FD8CC),
            accentAirpath = Color(0xFFA78BFF),
            accentThermal = Color(0xFF5CB8FF),
            accentIgnition = Color(0xFFFF70C0),
            statusLive = Color(0xFF34C765),
            statusCaution = Color(0xFFFFA51F),
            statusFault = Color(0xFFFF5340),
            daylightChalk = Color(0xFFFFFFFF),
            daylightMixture = Color(0xFF79F2E9),
            daylightAirpath = Color(0xFFCDBFFF),
            daylightThermal = Color(0xFFAEDCFF),
            daylightIgnition = Color(0xFFFFB2DA),
        ),
    ),
}

/**
 * The active skin.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf` for the same reason
 * [LocalReadoutType] is one: this changes about once a year, in response to a deliberate tap on
 * a settings row, so paying for fine-grained invalidation tracking on every colour read in the
 * app to make that one moment cheaper is exactly the wrong trade. A static local recomposes the
 * whole tree when it changes, which is what a reskin wants anyway.
 */
val LocalSkin = staticCompositionLocalOf { SkinId.INSTRUMENT.skin }
