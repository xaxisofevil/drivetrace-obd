package com.ericbarone.drivetrace.ui

import androidx.compose.ui.graphics.Color
import com.ericbarone.drivetrace.obd.MeasurementSample
import com.ericbarone.drivetrace.ui.theme.AccentAirpath
import com.ericbarone.drivetrace.ui.theme.AccentHousekeeping
import com.ericbarone.drivetrace.ui.theme.AccentIgnition
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.AccentMotion
import com.ericbarone.drivetrace.ui.theme.AccentThermal

/**
 * How one live PID is presented: which vehicle system it belongs to, how prominent it is, and how
 * its number is written. This is the join between docs/DATA_SCHEMA.md's confirmed
 * `canonical_name` strings and docs/DESIGN_SYSTEM.md's category-accent contract, in one table, so
 * neither the screen nor the scheduler has to carry a `when` block full of PID names.
 *
 * It lives here rather than in `obd/` on purpose: nothing in this table changes what gets polled
 * or logged. A PID missing from it still records normally, it just renders with the fallback
 * treatment at the bottom of [pidDisplayFor].
 */

/**
 * Which vehicle system a value belongs to. One hue each, fixed forever (DESIGN_SYSTEM.md section
 * 3): a new PID joins an existing category rather than getting a new colour.
 *
 * Colour is never the only carrier. The live cluster prints the category's name as a
 * `SectionLabel` over the tiles it owns, so the grouping survives colour blindness and a
 * greyscale screenshot exactly as the status glyphs do.
 */
enum class PidCategory(val accent: Color) {
    /** Achromatic on purpose: the primary instrument in a cluster is white on black. */
    MOTION(AccentMotion),
    MIXTURE(AccentMixture),
    AIRPATH(AccentAirpath),
    THERMAL(AccentThermal),
    IGNITION(AccentIgnition),

    /** Grey on purpose. Bookkeeping is findable, not glanceable. */
    HOUSEKEEPING(AccentHousekeeping),
}

/**
 * Polling tier, which decides both how prominent a value is and how long it can go without an
 * update before it should be called stale.
 *
 * The thresholds come from the scheduler's own arithmetic rather than from taste. [PidScheduler]
 * polls one command per tier visit: Tier A rotates continuously through ~10 commands, so any one
 * of them comes round every couple of seconds; Tier B visits once every 3s across ~12 commands,
 * so a given Tier B PID is nominally 36s old; Tier C visits once every 20s across ~9, nominally
 * 180s. Each budget below is that nominal figure with room for one 30s cooldown on top, because a
 * PID pausing after two failures is normal behaviour and not something to paint amber.
 *
 * @see com.ericbarone.drivetrace.obd.PidScheduler
 */
enum class PidTier(val staleAfterMs: Long) {
    A(15_000),
    B(90_000),
    C(300_000),
}

/**
 * @param label Short enough to survive a third-width tile at the 11sp tracked label style, which
 *   is about nine characters. Tier C entries are only ever drawn as `DataRow`s, which have the
 *   full width of a panel, so those can afford real words.
 * @param decimals How many decimal places the value is written to. Fixed per PID rather than
 *   derived from the value, so a tile does not change width as the number moves.
 */
data class PidDisplay(
    val canonicalName: String,
    val label: String,
    val category: PidCategory,
    val tier: PidTier,
    val decimals: Int,
)

/**
 * Every PID either catalog polls, in the order the live cluster draws them.
 *
 * Order is fixed here rather than taken from arrival order, so tiles do not reshuffle underneath
 * a driver's glance as slow PIDs answer for the first time. A PID that has never answered draws
 * nothing at all, so the cluster on a vehicle that supports half this list is half this list, not
 * a wall of dashes.
 */
val LIVE_PID_DISPLAY: List<PidDisplay> = listOf(
    // MOTION. The hero takes Engine RPM and the primary row takes Vehicle Speed; the rest fall
    // through to the grid as achromatic tiles.
    PidDisplay("Engine RPM", "RPM", PidCategory.MOTION, PidTier.A, 0),
    PidDisplay("Vehicle Speed", "Speed", PidCategory.MOTION, PidTier.A, 0),
    PidDisplay("Engine Load", "Load", PidCategory.MOTION, PidTier.A, 1),
    // The library has been seen naming PID 04 both ways (see PLAUSIBLE_RANGES in PidScheduler);
    // DATA_SCHEMA.md confirms "Engine Load" is what this project actually receives. Listed anyway
    // so the other spelling lands in MOTION rather than in the fallback bucket. No catalog can
    // produce both, so this can never draw two Load tiles.
    PidDisplay("Calculated Engine Load", "Load", PidCategory.MOTION, PidTier.A, 1),
    PidDisplay("Throttle Position", "Throttle", PidCategory.MOTION, PidTier.A, 1),
    PidDisplay("Engine Absolute Load", "Abs load", PidCategory.MOTION, PidTier.B, 1),

    // MIXTURE. The signal this project exists to chase, so short-term trim gets the primary row
    // next to speed and the rest of the fuel picture follows in the grid.
    PidDisplay("Short Term Fuel Trim Bank 1", "STFT B1", PidCategory.MIXTURE, PidTier.A, 1),
    PidDisplay("Long Term Fuel Trim Bank 1", "LTFT B1", PidCategory.MIXTURE, PidTier.A, 1),
    // Bank 2 exists on the Subaru's two-bank boxer and not on the Mazda's single-bank inline-4.
    PidDisplay("Short Term Fuel Trim Bank 2", "STFT B2", PidCategory.MIXTURE, PidTier.A, 1),
    PidDisplay("Long Term Fuel Trim Bank 2", "LTFT B2", PidCategory.MIXTURE, PidTier.A, 1),
    PidDisplay("Mass Air Flow", "MAF", PidCategory.MIXTURE, PidTier.A, 2),
    PidDisplay("Fuel-Air Commanded Equivalence Ratio", "Eq ratio", PidCategory.MIXTURE, PidTier.A, 3),
    PidDisplay("Fuel Consumption Rate", "Fuel rate", PidCategory.MIXTURE, PidTier.B, 2),
    PidDisplay("Fuel Rail Pressure", "Rail pres", PidCategory.MIXTURE, PidTier.B, 0),

    // AIRPATH.
    PidDisplay("Intake Manifold Pressure", "MAP", PidCategory.AIRPATH, PidTier.B, 0),
    PidDisplay("Intake Manifold Pressure Desired", "MAP tgt", PidCategory.AIRPATH, PidTier.B, 0),
    PidDisplay("Turbocharger A Compressor Inlet Pressure", "Turbo A", PidCategory.AIRPATH, PidTier.B, 0),
    PidDisplay("Turbocharger B Compressor Inlet Pressure", "Turbo B", PidCategory.AIRPATH, PidTier.B, 0),

    // THERMAL.
    PidDisplay("Engine Coolant Temperature", "Coolant", PidCategory.THERMAL, PidTier.B, 0),
    PidDisplay("Air Intake Temperature", "IAT", PidCategory.THERMAL, PidTier.B, 0),

    // IGNITION.
    PidDisplay("Timing Advance", "Timing", PidCategory.IGNITION, PidTier.B, 1),
    PidDisplay("Knock Retard", "Knock ret", PidCategory.IGNITION, PidTier.B, 1),
    PidDisplay("Knock Control System", "Knock sys", PidCategory.IGNITION, PidTier.B, 2),

    // Tier C and housekeeping: slow-changing context, drawn as DataRows at the bottom rather than
    // as tiles (rule 6). The category hue survives the demotion, so ambient air is still thermal
    // blue and barometric is still airpath violet on the line they end up on.
    PidDisplay("Engine Oil Temperature", "Oil temp", PidCategory.THERMAL, PidTier.C, 0),
    PidDisplay("Catalyst Temperature Bank 1 Sensor 1", "Catalyst temp", PidCategory.THERMAL, PidTier.C, 0),
    PidDisplay("Ambient Air Temperature", "Ambient air", PidCategory.THERMAL, PidTier.C, 0),
    PidDisplay("Barometric Pressure", "Barometric", PidCategory.AIRPATH, PidTier.C, 1),
    PidDisplay("Fuel Level", "Fuel level", PidCategory.MIXTURE, PidTier.C, 0),
    PidDisplay("Control Module Power Supply", "Module voltage", PidCategory.HOUSEKEEPING, PidTier.A, 1),
    PidDisplay("Engine Runtime", "Engine runtime", PidCategory.HOUSEKEEPING, PidTier.C, 0),
    PidDisplay("Commanded EGR", "Commanded EGR", PidCategory.HOUSEKEEPING, PidTier.C, 1),
    PidDisplay("EGR Error", "EGR error", PidCategory.HOUSEKEEPING, PidTier.C, 1),
    PidDisplay("Distance traveled since codes cleared", "Distance since codes", PidCategory.HOUSEKEEPING, PidTier.C, 0),
)

private val byCanonicalName: Map<String, PidDisplay> = LIVE_PID_DISPLAY.associateBy { it.canonicalName }

/**
 * The presentation for one PID, or a housekeeping fallback for a name this table has never heard
 * of. Falling back rather than dropping the value matters: a PID added to a catalog without a
 * matching entry here still shows up on the live screen, as a grey line at the bottom, instead of
 * silently vanishing from a screen that is supposed to be showing everything the car said.
 */
fun pidDisplayFor(canonicalName: String): PidDisplay =
    byCanonicalName[canonicalName]
        ?: PidDisplay(canonicalName, canonicalName, PidCategory.HOUSEKEEPING, PidTier.C, 1)

/**
 * The presentation for every PID that has actually reported, in draw order: the table's own order
 * first, then anything unrecognised, alphabetically, on the end. Splitting it this way is what
 * keeps the cluster stable under a glance, since the known PIDs never move relative to each other
 * no matter which of them answered first or at all.
 */
fun livePidDisplays(reportedNames: Set<String>): List<PidDisplay> {
    val known = LIVE_PID_DISPLAY.filter { it.canonicalName in reportedNames }
    val unknown = reportedNames
        .filterNot { it in byCanonicalName }
        .sorted()
        .map(::pidDisplayFor)
    return known + unknown
}

/**
 * True when this sample's number is parser garbage rather than a reading: outside a physically
 * sane range for its PID, so [PidScheduler] stored the raw text and left `valueNumeric` null.
 * The live cluster refuses to print such a value as if it were real; see LoggingScreen's LiveBody.
 *
 * @see com.ericbarone.drivetrace.obd.PidScheduler
 */
val MeasurementSample.isImplausible: Boolean
    get() = qualityFlag == "IMPLAUSIBLE"

/**
 * The sample's number written for display, or null when there is no number to write (implausible,
 * or a PID whose value never parsed as one). Never concatenated with its unit: the unit is always
 * a separate `Text` so the numeral keeps a fixed left edge and baseline.
 */
fun MeasurementSample.formattedValue(display: PidDisplay): String? =
    valueNumeric?.takeUnless { isImplausible }?.let { "%.${display.decimals}f".format(it) }

/** The unit as the adapter reported it, or null when it reported none. */
val MeasurementSample.displayUnit: String?
    get() = unit.trim().takeIf { it.isNotEmpty() }
