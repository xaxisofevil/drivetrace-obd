package com.ericbarone.drivetrace.data

import android.content.Context
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.streaming.analysisSummaryFromJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.min

/**
 * Two drives, bucketed by speed and diffed bin by bin. This is
 * `docs/ANALYSIS_STARTING_POINTS.md` item 2 ("a `compare_drives.py` that loads two or more
 * sessions' cruise tables, buckets by speed, and diffs mean load/MAF/trim per bin"), built on the
 * phone instead of on the PC.
 *
 * **Why on-device rather than a new server endpoint.** The server already holds both drives'
 * measurements whenever backfill succeeded, and DuckDB would do this bucketing in one SQL
 * statement, which makes the endpoint the obviously cheaper implementation. It is still the wrong
 * one for this app:
 *
 *  - **Room is authoritative and the server is best-effort, everywhere else in this codebase**
 *    (see ARCHITECTURE.md's "local storage is always authoritative"). A comparison that lives on
 *    the server can only compare drives that reached it. The logbook's own left accent bar exists
 *    precisely because drives routinely have not, and the drive most worth comparing is often the
 *    one you just finished in a car park with no signal.
 *  - **Every other read-model in this app already works this way.** `computeTripSummary`,
 *    `computeAdapterHealth` and `readSessionDtcs` all derive from Room and all keep working with
 *    the server unreachable. A server-side comparison would be the first screen in the app that
 *    goes blank when the home PC is off, and it would go blank for a reason the user cannot see.
 *  - **It needs no new API surface.** No endpoint, no DuckDB schema note, no polling state
 *    machine, no second failure mode to render, and nothing added to `StreamingClient`.
 *
 * The price is real and worth naming: this is a coarser method than `analyze_drive.py`'s. There is
 * no 1-second as-of grid, no rolling-standard-deviation cruise detector and no equivalence-ratio
 * gate on the fuel figures. What it does instead is documented on [speedProfile] below, and the
 * screen says so in its own caveats. The careful version still lives on the PC, against the same
 * data; this one exists so the question can be asked at all, from the car, about any two drives
 * ever logged.
 */

// ---------------------------------------------------------------------------
// Method constants. The cruise-window numbers deliberately mirror analyze_drive.py's
// CRUISE_MIN_SPEED_KMH / CRUISE_SPEED_STD_KMH / CRUISE_WINDOW_S, so the two implementations pick
// out recognisably the same parts of a drive rather than quietly disagreeing about what "cruise"
// means.
// ---------------------------------------------------------------------------

/** Bin width. `ANALYSIS_STARTING_POINTS.md` item 2's worked example is 5 km/h; this is that. */
const val SPEED_BIN_WIDTH_KMH = 5

/** Below this, load is dominated by launch and creep rather than by holding a speed. */
private const val CRUISE_MIN_SPEED_KMH = 15.0

/** Half-width of the stability window, matching analyze_drive.py's 10s centred window. */
private const val CRUISE_WINDOW_HALF_S = 5.0

/**
 * Peak-to-peak speed allowed inside the stability window. The PC script thresholds on a rolling
 * standard deviation of 3 km/h; a peak-to-peak bound is what can be computed cheaply over an
 * irregular sample timeline with no grid, and 6 km/h is roughly the same cut for the sort of
 * near-constant-speed segment either test is trying to find.
 */
private const val CRUISE_SPEED_SPAN_KMH = 6.0

/** Warm-up gate, standing in for the PC script's `warmup_end_s`. */
private const val WARM_COOLANT_C = 70.0

/** How stale a carried-forward Tier A value may be before it stops describing this moment. */
private const val TIER_A_STALE_S = 5.0

/** Coolant is Tier B and moves slowly; a 60s-old reading still answers "is it warm". */
private const val COOLANT_STALE_S = 60.0

/** Largest gap between speed samples still counted as continuous time. */
private const val MAX_SAMPLE_GAP_S = 5.0

/**
 * A bin needs this many contributing samples in *both* drives before its delta is printed. A bin
 * one drive clipped for three seconds is noise wearing the same clothes as a result.
 */
private const val MIN_SAMPLES_PER_BIN = 5

private const val NS_PER_S = 1_000_000_000.0

private const val NAME_SPEED = "Vehicle Speed"
private const val NAME_LOAD = "Engine Load"
private const val NAME_MAF = "Mass Air Flow"
private const val NAME_STFT = "Short Term Fuel Trim Bank 1"
private const val NAME_LTFT = "Long Term Fuel Trim Bank 1"
private const val NAME_COOLANT = "Engine Coolant Temperature"

private val COMPARISON_PIDS = listOf(
    NAME_SPEED, NAME_LOAD, NAME_MAF, NAME_STFT, NAME_LTFT, NAME_COOLANT,
)

// ---------------------------------------------------------------------------
// Result shapes
// ---------------------------------------------------------------------------

/** One drive's cruise samples, averaged inside one speed bin. */
data class SpeedBinStats(
    /** Lower edge of the bin, km/h. The bin covers [binStartKmh, binStartKmh + width). */
    val binStartKmh: Int,
    val meanLoadPct: Double?,
    val loadSamples: Int,
    val meanMafGs: Double?,
    val mafSamples: Int,
    /** STFT + LTFT, the same "combined trim" `analyze_drive.py` derives. */
    val meanCombinedTrimPct: Double?,
    val trimSamples: Int,
    /** Cruise samples that fell in this bin at all, whatever else answered. */
    val speedSamples: Int,
)

/** Everything one drive contributes to a comparison. */
data class DriveSpeedProfile(
    val sessionId: Long,
    val bins: Map<Int, SpeedBinStats>,
    /** Seconds of the drive that passed the cruise test. */
    val cruiseSeconds: Double,
    /** False when the drive never reported coolant, so the warm-up gate could not be applied. */
    val warmupGated: Boolean,
)

/** One speed bin, in both drives, with the difference between them. */
data class SpeedBinDelta(
    val binStartKmh: Int,
    val binEndKmh: Int,
    val earlier: SpeedBinStats,
    val later: SpeedBinStats,
    /** Later minus earlier, percentage points. Null when either side is under the sample floor. */
    val loadDeltaPct: Double?,
    val mafDeltaGs: Double?,
    val trimDeltaPct: Double?,
    /** min(samples) of the two sides, which is what the bin is worth as evidence. */
    val loadWeight: Int,
)

/** One side of the comparison: the session row plus the figures already on it. */
data class ComparedDrive(
    val session: SessionEntity,
    val profile: DriveSpeedProfile,
    /** From the stored server analysis, so this screen and the logbook card never disagree. */
    val overallMpg: Double?,
    val distanceKm: Double?,
)

/**
 * A reason to distrust, or not to read, the numbers above it. Kept as a typed list rather than as
 * pre-rendered sentences so the screen owns the wording, per the rule that the app writes its own
 * user-facing text.
 */
enum class ComparisonCaveat {
    /** The two drives are different cars. Load at matched speed then compares two vehicles. */
    DIFFERENT_VEHICLES,

    /** Nothing overlapped: no speed bin cleared the sample floor in both drives. */
    NO_MATCHED_BINS,

    /** Fewer than three matched bins, so the headline rests on a very narrow speed range. */
    THIN_OVERLAP,

    /** Mass Air Flow never answered on one drive or the other. */
    NO_MAF,

    /** Fuel trim never answered on one drive or the other. */
    NO_TRIM,

    /** A drive reported no coolant, so its samples could not be gated on being warmed up. */
    NO_WARMUP_GATE,
}

/**
 * The comparison. [earlier] and [later] are ordered by start time rather than by the order the
 * user tapped them, and **every delta is later minus earlier**, so the sign reads as "what
 * changed" and matches the question the project exists to answer ("it used to get 34, now it gets
 * 29"). Tap order would make the same number mean opposite things on consecutive runs.
 */
data class DriveComparison(
    val earlier: ComparedDrive,
    val later: ComparedDrive,
    /** Only bins that cleared the sample floor in both drives, ascending by speed. */
    val bins: List<SpeedBinDelta>,
    /** Sample-weighted mean of the per-bin load deltas. The headline figure. */
    val loadDeltaPct: Double?,
    val mafDeltaGs: Double?,
    val trimDeltaPct: Double?,
    /** Later minus earlier, from the stored analyses; null unless both drives have one. */
    val mpgDelta: Double?,
    val caveats: List<ComparisonCaveat>,
) {
    /** Total evidence behind the headline: the summed per-bin weights. */
    val matchedSamples: Int get() = bins.sumOf { it.loadWeight }

    /** Lowest and highest matched bin, the speed range the headline actually covers. */
    val matchedSpeedRangeKmh: Pair<Int, Int>?
        get() = bins.firstOrNull()?.let { it.binStartKmh to bins.last().binEndKmh }
}

// ---------------------------------------------------------------------------
// Computation
// ---------------------------------------------------------------------------

/**
 * Compare two logged drives. Both sides come out of Room; nothing here touches the network, so
 * this works for a drive that has never once reached the server.
 *
 * Returns null only when one of the session rows is missing entirely, which the caller should
 * treat as "that drive is gone" rather than as a failed comparison.
 */
suspend fun compareDrives(context: Context, sessionIdA: Long, sessionIdB: Long): DriveComparison? =
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).sessionDao()
        val rowA = dao.getSession(sessionIdA) ?: return@withContext null
        val rowB = dao.getSession(sessionIdB) ?: return@withContext null

        val (first, second) =
            if (rowA.startWallTimeUtc <= rowB.startWallTimeUtc) rowA to rowB else rowB to rowA

        val earlier = comparedDrive(dao, first)
        val later = comparedDrive(dao, second)

        val bins = matchedBins(earlier.profile, later.profile)

        val caveats = buildList {
            if (earlier.session.vehicleProfile != later.session.vehicleProfile) {
                add(ComparisonCaveat.DIFFERENT_VEHICLES)
            }
            when {
                bins.isEmpty() -> add(ComparisonCaveat.NO_MATCHED_BINS)
                bins.size < 3 -> add(ComparisonCaveat.THIN_OVERLAP)
            }
            if (bins.isNotEmpty() && bins.none { it.mafDeltaGs != null }) add(ComparisonCaveat.NO_MAF)
            if (bins.isNotEmpty() && bins.none { it.trimDeltaPct != null }) add(ComparisonCaveat.NO_TRIM)
            if (!earlier.profile.warmupGated || !later.profile.warmupGated) {
                add(ComparisonCaveat.NO_WARMUP_GATE)
            }
        }

        DriveComparison(
            earlier = earlier,
            later = later,
            bins = bins,
            // Weighted by min(samples) per bin rather than by a flat mean over bins. A bin both
            // drives sat in for four minutes is better evidence than one they each clipped for
            // six seconds, and an unweighted mean would let the thin bin shout as loudly.
            loadDeltaPct = weightedMean(bins) { it.loadDeltaPct },
            mafDeltaGs = weightedMean(bins) { it.mafDeltaGs },
            trimDeltaPct = weightedMean(bins) { it.trimDeltaPct },
            mpgDelta = later.overallMpg?.let { b -> earlier.overallMpg?.let { a -> b - a } },
            caveats = caveats,
        )
    }

private suspend fun comparedDrive(dao: SessionDao, session: SessionEntity): ComparedDrive {
    val summary = session.analysisSummaryJson?.let { analysisSummaryFromJson(it) }
    return ComparedDrive(
        session = session,
        profile = speedProfile(dao, session.sessionId),
        overallMpg = summary?.overallMpg,
        distanceKm = summary?.distanceGpsKm,
    )
}

/**
 * One drive's mean load / MAF / combined trim per 5 km/h speed bin, over the parts of the drive
 * that were actually holding a speed.
 *
 * **The speed samples are the grid.** `analyze_drive.py` resamples everything onto a 1-second
 * grid first and then works on that; there is no pandas here, and inventing a grid would mean
 * interpolating values the vehicle never reported. Vehicle Speed is Tier A and the fastest thing
 * in the catalog, so its own sample times are used as the timeline and every other PID is joined
 * onto them as-of (carry the last value forward, up to [TIER_A_STALE_S]). That is the same join
 * `build_snapshot` performs, with the same staleness honesty, just on an irregular grid the car
 * chose instead of a regular one.
 *
 * **Three gates decide whether a moment counts**, mirroring `find_cruise_windows`: the vehicle is
 * above [CRUISE_MIN_SPEED_KMH], speed is flat inside a +/-[CRUISE_WINDOW_HALF_S] window, and the
 * engine is warm. Accelerating hard at 50 km/h and holding 50 km/h are not the same operating
 * point, and a comparison that mixed them would measure how the two drives were driven rather
 * than what the car did.
 */
private suspend fun speedProfile(dao: SessionDao, sessionId: Long): DriveSpeedProfile {
    // Only the six PIDs this needs. A long drive logs tens of thousands of measurement rows and
    // reading all of them to keep six names is the kind of scan that makes a screen feel broken;
    // same reasoning as SessionDao.getEventsOfTypes.
    val rows = dao.getMeasurementsOfTypes(sessionId, COMPARISON_PIDS)
    val speed = seriesOf(rows, NAME_SPEED)
    if (speed.isEmpty()) {
        return DriveSpeedProfile(sessionId, emptyMap(), 0.0, warmupGated = false)
    }
    val load = seriesOf(rows, NAME_LOAD)
    val maf = seriesOf(rows, NAME_MAF)
    val stft = seriesOf(rows, NAME_STFT)
    val ltft = seriesOf(rows, NAME_LTFT)
    val coolant = seriesOf(rows, NAME_COOLANT)

    val cruise = cruiseMask(speed)
    val tierAStaleNs = (TIER_A_STALE_S * NS_PER_S).toLong()
    val coolantStaleNs = (COOLANT_STALE_S * NS_PER_S).toLong()
    val maxGapNs = (MAX_SAMPLE_GAP_S * NS_PER_S).toLong()

    val acc = HashMap<Int, BinAccumulator>()
    var cruiseNs = 0L

    for (i in speed.indices) {
        if (!cruise[i]) continue
        val t = speed[i].elapsedNs
        // Skipped rather than ungated when coolant exists but is cold; skipped-as-a-whole only
        // when the drive has no coolant series at all, which is reported as a caveat instead of
        // silently comparing a cold drive against a warm one.
        if (coolant.isNotEmpty()) {
            val temp = coolant.asOf(t, coolantStaleNs) ?: continue
            if (temp < WARM_COOLANT_C) continue
        }

        val bin = binOf(speed[i].value)
        val slot = acc.getOrPut(bin) { BinAccumulator() }
        slot.speedSamples++

        load.asOf(t, tierAStaleNs)?.let { slot.addLoad(it) }
        maf.asOf(t, tierAStaleNs)?.let { slot.addMaf(it) }
        val short = stft.asOf(t, tierAStaleNs)
        val long = ltft.asOf(t, tierAStaleNs)
        if (short != null && long != null) slot.addTrim(short + long)

        if (i + 1 < speed.size) {
            val gap = speed[i + 1].elapsedNs - t
            if (gap in 1..maxGapNs) cruiseNs += gap
        }
    }

    return DriveSpeedProfile(
        sessionId = sessionId,
        bins = acc.mapValues { (bin, a) -> a.toStats(bin) },
        cruiseSeconds = cruiseNs / NS_PER_S,
        warmupGated = coolant.isNotEmpty(),
    )
}

/** Bins present in both drives with enough samples on both sides to be worth printing. */
private fun matchedBins(a: DriveSpeedProfile, b: DriveSpeedProfile): List<SpeedBinDelta> =
    a.bins.keys.intersect(b.bins.keys).sorted().mapNotNull { bin ->
        val ea = a.bins.getValue(bin)
        val lb = b.bins.getValue(bin)
        val loadDelta = pairedDelta(ea.meanLoadPct, ea.loadSamples, lb.meanLoadPct, lb.loadSamples)
        val mafDelta = pairedDelta(ea.meanMafGs, ea.mafSamples, lb.meanMafGs, lb.mafSamples)
        val trimDelta =
            pairedDelta(ea.meanCombinedTrimPct, ea.trimSamples, lb.meanCombinedTrimPct, lb.trimSamples)
        // A bin with no metric answering on both sides is not a matched bin, it is two drives
        // that happened to pass through the same speed.
        if (loadDelta == null && mafDelta == null && trimDelta == null) return@mapNotNull null
        SpeedBinDelta(
            binStartKmh = bin,
            binEndKmh = bin + SPEED_BIN_WIDTH_KMH,
            earlier = ea,
            later = lb,
            loadDeltaPct = loadDelta,
            mafDeltaGs = mafDelta,
            trimDeltaPct = trimDelta,
            loadWeight = if (loadDelta != null) min(ea.loadSamples, lb.loadSamples) else 0,
        )
    }

private fun pairedDelta(a: Double?, aN: Int, b: Double?, bN: Int): Double? =
    if (a != null && b != null && aN >= MIN_SAMPLES_PER_BIN && bN >= MIN_SAMPLES_PER_BIN) b - a
    else null

private fun weightedMean(bins: List<SpeedBinDelta>, pick: (SpeedBinDelta) -> Double?): Double? {
    var num = 0.0
    var den = 0
    for (b in bins) {
        val v = pick(b) ?: continue
        val w = maxOf(b.loadWeight, 1)
        num += v * w
        den += w
    }
    return if (den == 0) null else num / den
}

private fun binOf(speedKmh: Double): Int =
    (floor(speedKmh / SPEED_BIN_WIDTH_KMH).toInt()) * SPEED_BIN_WIDTH_KMH

// ---------------------------------------------------------------------------
// Series plumbing
// ---------------------------------------------------------------------------

private class Sample(val elapsedNs: Long, val value: Double)

private fun seriesOf(rows: List<MeasurementEntity>, canonicalName: String): List<Sample> =
    rows.asSequence()
        .filter { it.canonicalName == canonicalName && it.valueNumeric != null }
        .map { Sample(it.elapsedNs, it.valueNumeric!!) }
        .toList()

/**
 * The as-of join: the most recent value at or before [elapsedNs], as long as it is not more than
 * [maxStaleNs] old. Returning null past the cap rather than carrying an ancient value forever is
 * what the PC script's `age_s_<key>` columns exist to let a human do by eye.
 *
 * The list is already sorted by `elapsed_ns` because the DAO query orders by it.
 */
private fun List<Sample>.asOf(elapsedNs: Long, maxStaleNs: Long): Double? {
    if (isEmpty()) return null
    val hit = binarySearch { it.elapsedNs.compareTo(elapsedNs) }
    val idx = if (hit >= 0) hit else -hit - 2
    if (idx < 0) return null
    val sample = this[idx]
    return if (elapsedNs - sample.elapsedNs > maxStaleNs) null else sample.value
}

/**
 * Which speed samples sit inside a stable, above-creep stretch. The window bounds only ever move
 * forward, so the two pointers walk the list once; the peak-to-peak scan inside the window is a
 * few dozen samples wide at Tier A cadence and not worth a monotonic deque.
 */
private fun cruiseMask(speed: List<Sample>): BooleanArray {
    val mask = BooleanArray(speed.size)
    val halfWindowNs = (CRUISE_WINDOW_HALF_S * NS_PER_S).toLong()
    var lo = 0
    var hi = 0
    for (i in speed.indices) {
        if (speed[i].value < CRUISE_MIN_SPEED_KMH) continue
        val t = speed[i].elapsedNs
        while (lo < i && speed[lo].elapsedNs < t - halfWindowNs) lo++
        if (hi < i) hi = i
        while (hi + 1 < speed.size && speed[hi + 1].elapsedNs <= t + halfWindowNs) hi++
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        for (j in lo..hi) {
            val v = speed[j].value
            if (v < lowest) lowest = v
            if (v > highest) highest = v
        }
        mask[i] = highest - lowest <= CRUISE_SPEED_SPAN_KMH
    }
    return mask
}

private class BinAccumulator {
    var speedSamples = 0
    private var loadSum = 0.0
    private var loadN = 0
    private var mafSum = 0.0
    private var mafN = 0
    private var trimSum = 0.0
    private var trimN = 0

    fun addLoad(v: Double) { loadSum += v; loadN++ }
    fun addMaf(v: Double) { mafSum += v; mafN++ }
    fun addTrim(v: Double) { trimSum += v; trimN++ }

    fun toStats(bin: Int) = SpeedBinStats(
        binStartKmh = bin,
        meanLoadPct = if (loadN > 0) loadSum / loadN else null,
        loadSamples = loadN,
        meanMafGs = if (mafN > 0) mafSum / mafN else null,
        mafSamples = mafN,
        meanCombinedTrimPct = if (trimN > 0) trimSum / trimN else null,
        trimSamples = trimN,
        speedSamples = speedSamples,
    )
}

/**
 * The stored `vehicleProfile` as the name of an actual car, falling back to the raw stored string
 * so a profile the enum no longer has still identifies its drives. Same rule the logbook uses.
 */
fun vehicleDisplayName(storedName: String): String =
    VehicleProfile.entries.find { it.name == storedName }?.displayName ?: storedName
