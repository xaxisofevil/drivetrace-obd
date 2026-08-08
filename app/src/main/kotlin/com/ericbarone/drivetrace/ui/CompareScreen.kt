package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ericbarone.drivetrace.data.ComparisonCaveat
import com.ericbarone.drivetrace.data.DriveComparison
import com.ericbarone.drivetrace.data.SPEED_BIN_WIDTH_KMH
import com.ericbarone.drivetrace.data.SpeedBinDelta
import com.ericbarone.drivetrace.data.compareDrives
import com.ericbarone.drivetrace.data.vehicleDisplayName
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.DataRow
import com.ericbarone.drivetrace.ui.components.EmptyState
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.HeroReadout
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.MetricTile
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
import com.ericbarone.drivetrace.ui.components.StatusBand
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.AccentMotion
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Two drives, side by side, bucketed by speed. This is `docs/DESIGN_SYSTEM.md` idea #3 and
 * `docs/ANALYSIS_STARTING_POINTS.md` item 2; the computation and the argument for where it runs
 * are in `data/DriveComparison.kt`.
 *
 * **The screen owns every user-facing sentence.** `compareDrives` returns typed
 * [ComparisonCaveat]s rather than pre-rendered prose, so the wording lives here with the rest of
 * the app's voice instead of in the data layer.
 *
 * **The hero is the load delta at matched speed,** not the MPG delta, because the MPG delta is
 * two numbers the logbook already prints in a scannable column and this screen exists to say
 * something the logbook cannot: whether the car is working harder to hold the same speed. It falls
 * back down a chain (load, then airflow, then trim, then MPG) rather than ever printing `--` at
 * 64sp, per rule 13, and a comparison with nothing at all to report gets a band instead of a hero.
 *
 * **Nothing here is tinted by sign.** A higher load at matched speed is the lead this whole
 * project is chasing, and it is still not a fault the app is in a position to declare, so the
 * status channel stays out of it and the sign carries the direction on its own. Category accents
 * apply as they do everywhere: load is MOTION and therefore achromatic, airflow, trim and economy
 * are MIXTURE.
 */
@Composable
fun CompareScreen(sessionIdA: Long, sessionIdB: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var comparison by remember(sessionIdA, sessionIdB) { mutableStateOf<DriveComparison?>(null) }
    var loading by remember(sessionIdA, sessionIdB) { mutableStateOf(true) }

    // Room only, so this works for a drive that has never once reached the server. See
    // DriveComparison.kt for why that was chosen over the cheaper server-side implementation.
    LaunchedEffect(sessionIdA, sessionIdB) {
        loading = true
        comparison = compareDrives(context, sessionIdA, sessionIdB)
        loading = false
    }

    val shortFmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    val result = comparison

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
    ) {
        HeaderBar(
            title = "Compare",
            subtitle = result?.let {
                "${shortFmt.format(Date(it.earlier.session.startWallTimeUtc))} to " +
                    shortFmt.format(Date(it.later.session.startWallTimeUtc))
            },
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.section),
        ) {
            Spacer(Modifier.height(Space.xs))
            when {
                loading -> EmptyState(
                    title = "Comparing",
                    body = "Bucketing both drives by speed...",
                )
                result == null -> EmptyState(
                    title = "Drive missing",
                    body = "One of those two drives is no longer in the local database.",
                )
                else -> ComparisonBody(result)
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

// ---------------------------------------------------------------------------
// The report
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.ComparisonBody(result: DriveComparison) {
    val hero = heroFor(result)

    // At most one band, on the same terms as the trip report's: nothing else on this screen is
    // allowed to be one. It is spent on a caveat that puts the comparison itself in question,
    // never on one that merely qualifies a figure the screen can still print. Whichever caveat
    // takes it is then left out of the small print at the foot, so nothing is said twice.
    val banded = when {
        // The load-at-matched-speed comparison is the screen. Losing it is a band whether or not a
        // stored MPG figure was left over to fill the hero, because an MPG delta with no matched
        // bins under it answers a different question and would otherwise read as the answer.
        ComparisonCaveat.NO_MATCHED_BINS in result.caveats -> ComparisonCaveat.NO_MATCHED_BINS
        ComparisonCaveat.DIFFERENT_VEHICLES in result.caveats -> ComparisonCaveat.DIFFERENT_VEHICLES
        else -> null
    }
    when (banded) {
        ComparisonCaveat.NO_MATCHED_BINS -> StatusBand(
            tone = if (hero == null) Tone.FAULT else Tone.CAUTION,
            title = "No matched speed",
            body = "These two drives never held the same speed long enough, with the engine warm, " +
                "for any bin to clear the sample floor on both sides. " +
                if (hero == null) {
                    "Two drives that share some road, or any stretch of steady speed, will compare."
                } else {
                    "The figure above is the difference between the two stored analyses, which is " +
                        "not matched on speed and does not say why."
                },
        )
        ComparisonCaveat.DIFFERENT_VEHICLES -> StatusBand(
            tone = Tone.CAUTION,
            title = "Two different vehicles",
            body = "Load at a matched speed then compares two cars rather than one car against " +
                "itself, and the difference below is mostly the difference between them.",
        )
        else -> Unit
    }

    if (hero != null) {
        HeroReadout(
            label = hero.label,
            value = hero.value,
            unit = hero.unit,
            accent = hero.accent,
            caption = heroCaption(result, hero),
        )
        SecondaryBand(result, hero)
    }

    DrivesSection(result)

    if (result.bins.any { it.loadDeltaPct != null }) {
        SpeedBinSection(result)
    }

    CaveatsSection(result, banded)
}

/**
 * The figure the screen leads with, down a fixed chain rather than a fixed slot. Load at matched
 * speed is the whole method; airflow and trim are the same measurement asked of the fuel side; the
 * MPG delta is the fallback that needs no matched bins at all, because it comes off the two stored
 * analyses rather than out of the bucketing.
 */
private class CompareHero(
    val key: CompareMetric,
    val label: String,
    val value: String,
    val unit: String,
    val accent: Color,
)

private enum class CompareMetric { LOAD, MAF, TRIM, MPG }

@Composable
private fun heroFor(result: DriveComparison): CompareHero? = when {
    result.loadDeltaPct != null -> CompareHero(
        CompareMetric.LOAD, "Load at matched speed", signed(result.loadDeltaPct, 1), "pts", AccentMotion,
    )
    result.mafDeltaGs != null -> CompareHero(
        CompareMetric.MAF, "Airflow at matched speed", signed(result.mafDeltaGs, 2), "g/s", AccentMixture,
    )
    result.trimDeltaPct != null -> CompareHero(
        CompareMetric.TRIM, "Trim at matched speed", signed(result.trimDeltaPct, 1), "pts", AccentMixture,
    )
    result.mpgDelta != null -> CompareHero(
        CompareMetric.MPG, "Overall MPG", signed(result.mpgDelta, 1), "MPG", AccentMixture,
    )
    else -> null
}

private fun heroCaption(result: DriveComparison, hero: CompareHero): String {
    if (hero.key == CompareMetric.MPG) return "later drive minus earlier, from the stored PC analyses"
    val range = result.matchedSpeedRangeKmh
    val bins = result.bins.size
    return buildString {
        append("later drive minus earlier, ")
        if (range != null) append("${range.first}-${range.second} km/h, ")
        append("$bins matched bin${if (bins == 1) "" else "s"}, ")
        append("${result.matchedSamples} samples")
    }
}

/**
 * Whatever the hero did not take. Three across at most, and a metric that never answered on one of
 * the two drives is omitted rather than drawn as a dash: the caveat list at the foot of the screen
 * already says which one and why, and a tile reading `--` says only that something is missing.
 */
@Composable
private fun ColumnScope.SecondaryBand(result: DriveComparison, hero: CompareHero) {
    val tiles = buildList<@Composable RowScope.() -> Unit> {
        if (hero.key != CompareMetric.LOAD) result.loadDeltaPct?.let {
            add { Tile("Load", signed(it, 1), "pts", AccentMotion) }
        }
        if (hero.key != CompareMetric.MAF) result.mafDeltaGs?.let {
            add { Tile("Airflow", signed(it, 2), "g/s", AccentMixture) }
        }
        if (hero.key != CompareMetric.TRIM) result.trimDeltaPct?.let {
            add { Tile("Trim", signed(it, 1), "pts", AccentMixture) }
        }
        if (hero.key != CompareMetric.MPG) result.mpgDelta?.let {
            add { Tile("MPG", signed(it, 1), "MPG", AccentMixture) }
        }
    }
    if (tiles.isEmpty()) return

    // Same wrap the trip report's profile tiles use, and for the same reason: four across a phone
    // leaves each tile narrower than its own label at the label style's tracking.
    val perRow = when {
        tiles.size <= 3 -> tiles.size
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.tileGap)) {
        for (rowTiles in tiles.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
                for (tile in rowTiles) tile()
                // A short final row keeps its tiles the width of the rows above rather than
                // stretching one across the screen and reading as a second hero.
                repeat(perRow - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, unit: String, accent: Color) {
    MetricTile(
        label = label,
        value = value,
        unit = unit,
        accent = accent,
        modifier = Modifier.weight(1f),
    )
}

/**
 * Which two drives these are. Ordered earlier then later throughout the screen rather than in the
 * order they were tapped, because every delta on it is later minus earlier and tap order would
 * make the same number mean opposite things on consecutive runs.
 *
 * Cruise time is here rather than in the caveats because it is the evidence base: a drive that
 * spent ninety seconds holding a steady speed contributes ninety seconds of comparison, however
 * long it ran in total.
 */
@Composable
private fun ColumnScope.DrivesSection(result: DriveComparison) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US) }
    val sameVehicle = result.earlier.session.vehicleProfile == result.later.session.vehicleProfile

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Drives")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            DataRow("Earlier", fmt.format(Date(result.earlier.session.startWallTimeUtc)))
            DataRow("Later", fmt.format(Date(result.later.session.startWallTimeUtc)))
            if (sameVehicle) {
                DataRow("Vehicle", vehicleDisplayName(result.earlier.session.vehicleProfile))
            } else {
                DataRow(
                    "Vehicle, earlier",
                    vehicleDisplayName(result.earlier.session.vehicleProfile),
                    valueColor = Tone.CAUTION.color,
                )
                DataRow(
                    "Vehicle, later",
                    vehicleDisplayName(result.later.session.vehicleProfile),
                    valueColor = Tone.CAUTION.color,
                )
            }
            result.earlier.overallMpg?.let { DataRow("MPG, earlier", "%.1f".format(it), valueColor = AccentMixture) }
            result.later.overallMpg?.let { DataRow("MPG, later", "%.1f".format(it), valueColor = AccentMixture) }
            DataRow("Cruise time, earlier", formatMinutes(result.earlier.profile.cruiseSeconds))
            DataRow("Cruise time, later", formatMinutes(result.later.profile.cruiseSeconds))
        }
    }
}

/**
 * The method's actual output: one line per 5 km/h bin. Load inline, because load at matched speed
 * is what the screen is for; airflow, trim and the per-bin sample counts behind a tap, per rule 15.
 *
 * The counts matter for the same reason the adapter-health counts do. A bin both drives sat in for
 * four minutes and a bin they each clipped for ten seconds print the same way, and only the count
 * separates a result from a coincidence. That is not the question anyone opens this screen to ask,
 * so it is one tap away rather than in the resting state.
 */
@Composable
private fun ColumnScope.SpeedBinSection(result: DriveComparison) {
    var showDetail by remember { mutableStateOf(false) }
    val loadBins = result.bins.filter { it.loadDeltaPct != null }
    val mafBins = result.bins.filter { it.mafDeltaGs != null }
    val trimBins = result.bins.filter { it.trimDeltaPct != null }

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Load by speed bin")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            for (bin in loadBins) {
                DataRow(binLabel(bin), signed(bin.loadDeltaPct!!, 1), valueColor = AccentMotion)
            }

            Spacer(Modifier.height(Space.xs))
            SecondaryAction(
                text = if (showDetail) "Hide bin detail" else "Bin detail",
                onClick = { showDetail = !showDetail },
                minHeight = Space.compactTarget,
            )
            if (showDetail) {
                Spacer(Modifier.height(Space.sm))
                if (mafBins.isNotEmpty()) {
                    Caption("Mass air flow, g/s")
                    for (bin in mafBins) {
                        DataRow(binLabel(bin), signed(bin.mafDeltaGs!!, 2), valueColor = AccentMixture)
                    }
                    Spacer(Modifier.height(Space.sm))
                }
                if (trimBins.isNotEmpty()) {
                    Caption("Combined trim (short plus long term), points")
                    for (bin in trimBins) {
                        DataRow(binLabel(bin), signed(bin.trimDeltaPct!!, 1), valueColor = AccentMixture)
                    }
                    Spacer(Modifier.height(Space.sm))
                }
                Caption("Samples per bin, the thinner of the two drives")
                for (bin in result.bins) {
                    DataRow(binLabel(bin), sampleFloor(bin).toString(), valueColor = Mist)
                }
                Spacer(Modifier.height(Space.sm))
            }
        }
    }
}

/**
 * What not to conclude from the figures above. Every one of these is a real limit on the
 * comparison rather than small print about the app, which is why they are stated at all; they sit
 * at the foot of the screen in `Caption` weight because none of them changes what the reader does
 * next, which is the line rule 15 draws.
 */
@Composable
private fun ColumnScope.CaveatsSection(result: DriveComparison, banded: ComparisonCaveat?) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionLabel("Method")
        for (caveat in result.caveats) {
            // Whichever one took the band said it once, at full weight, and is not repeated here.
            if (caveat == banded) continue
            Caption(describe(caveat), color = Mist)
        }
        Caption(
            "Speed bins are $SPEED_BIN_WIDTH_KMH km/h wide. A moment counts only if the vehicle " +
                "was above 15 km/h, held its speed inside a 10-second window, and the engine was " +
                "warm, so accelerating through a speed and holding it are never averaged together. " +
                "Every figure on this screen is the later drive minus the earlier one.",
        )
        Caption(
            "This is the phone's coarse version of the PC's cruise-window analysis: no 1-second " +
                "resampled grid, no rolling standard-deviation cruise detector, and no " +
                "equivalence-ratio gate on the fuel figures. Run scripts/analyze_drive.py against " +
                "both drives for the careful version of the same question.",
        )
    }
}

private fun describe(caveat: ComparisonCaveat): String = when (caveat) {
    ComparisonCaveat.DIFFERENT_VEHICLES ->
        "These are two different vehicles, so load at a matched speed compares two cars rather " +
            "than one car against itself."
    ComparisonCaveat.NO_MATCHED_BINS ->
        "No speed bin cleared the sample floor in both drives, so nothing here is matched on speed."
    ComparisonCaveat.THIN_OVERLAP ->
        "Fewer than three speed bins cleared the sample floor in both drives, so the headline " +
            "rests on a narrow band of speed rather than on the whole drive."
    ComparisonCaveat.NO_MAF ->
        "Mass air flow never answered on one of the two drives, so there is no airflow figure."
    ComparisonCaveat.NO_TRIM ->
        "Fuel trim never answered on one of the two drives, so there is no trim figure."
    ComparisonCaveat.NO_WARMUP_GATE ->
        "One drive reported no coolant temperature, so its samples could not be gated on the " +
            "engine having warmed up. A cold engine runs richer, which shows up as load and trim " +
            "that are high for reasons that have nothing to do with the other drive."
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** Always signed. The sign is the entire message on a screen where every number is a difference. */
private fun signed(value: Double, decimals: Int): String = "%+.${decimals}f".format(value)

private fun binLabel(bin: SpeedBinDelta): String = "${bin.binStartKmh}-${bin.binEndKmh} km/h"

private fun sampleFloor(bin: SpeedBinDelta): Int = min(
    bin.earlier.speedSamples,
    bin.later.speedSamples,
)

private fun formatMinutes(seconds: Double): String = when {
    seconds < 60 -> "%.0f s".format(seconds)
    else -> "%.0f min".format(seconds / 60.0)
}
