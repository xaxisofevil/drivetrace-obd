package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.BadResponseException
import com.github.eltonvs.obd.command.ObdCommand
import java.util.concurrent.atomic.AtomicLong

data class MeasurementSample(
    val sequence: Long,
    val wallTimeUtc: Long,
    val elapsedNs: Long,
    val pidTag: String,
    val canonicalName: String,
    val valueNumeric: Double?,
    val valueText: String?,
    val unit: String,
    val latencyMs: Long,
    val qualityFlag: String,
    /** The unprocessed ELM response text for this exact read (before the library's own
     * whitespace/bus-init/colon stripping), never previously captured anywhere in this
     * project. Lets a DTC or any other parsed value be independently re-checked later
     * against what the adapter actually said, rather than trusting the library's parse. */
    val rawResponse: String?,
)

data class SchedulerEvent(
    val elapsedNs: Long,
    val eventType: String,
    val severity: String,
    val message: String,
)

private const val TIER_B_INTERVAL_MS = 3_000L
private const val TIER_C_INTERVAL_MS = 20_000L

/**
 * Sanity clamp, kept as defense-in-depth even after fixing the actual root cause (see
 * SafeCommands.kt and the pinned kotlin-obd-api commit in build.gradle.kts): several of the
 * library's multi-byte formulas called bytesToInt() without bounding bytesToProcess, which
 * defaults to unbounded and folds every byte in the whole response into one number instead of
 * just the PID's real data bytes (confirmed directly: RPM read back as 3.8 trillion, module
 * voltage as tens of billions). Flag anything physically impossible for the PID rather than
 * storing it as real data either way, per the blueprint's "detect impossible values, flag
 * without deleting" reliability rule (section 9). Deliberately generous ranges: the goal is
 * catching parser garbage, not being a strict physical model.
 */
private val PLAUSIBLE_RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
    "Engine RPM" to 0.0..10_000.0,
    "Vehicle Speed" to 0.0..300.0,
    "Control Module Power Supply" to 0.0..30.0,
    "Calculated Engine Load" to 0.0..100.0,
    "Engine Load" to 0.0..100.0,
    "Engine Absolute Load" to 0.0..300.0, // can legitimately exceed 100% under boost
    "Mass Air Flow" to 0.0..1000.0,
    "Commanded Equivalence Ratio" to 0.0..3.0,
    "Fuel-Air Commanded Equivalence Ratio" to 0.0..3.0,
    "Short Term Fuel Trim Bank 1" to -100.0..100.0,
    "Long Term Fuel Trim Bank 1" to -100.0..100.0,
    "Engine Coolant Temperature" to -40.0..215.0,
    "Intake Air Temperature" to -40.0..215.0,
    "Ambient Air Temperature" to -40.0..215.0,
    "Throttle Position" to 0.0..100.0,
    "Timing Advance" to -64.0..63.5, // full range the PID 0E formula can express (A/2 - 64)
    "Intake Manifold Pressure" to 0.0..400.0,
    "Barometric Pressure" to 50.0..150.0,
    "Distance traveled since codes cleared" to 0.0..100_000.0,
    "Engine Runtime" to 0.0..86_400.0,
    "Fuel Level" to 0.0..100.0,
)

/**
 * A PID that fails this many times in a row goes into cooldown rather than being permanently
 * dropped. Confirmed directly: LONG_TERM_BANK_1 failed twice 17s into a real drive (adapter
 * likely still settling right after connect) and a permanent-drop policy then blacklisted it
 * for the remaining ~8.7 minutes despite this vehicle genuinely supporting it. Retrying after a
 * cooldown costs almost nothing for a truly unsupported PID and recovers a supported one that
 * just had a transient hiccup.
 */
private const val MAX_CONSECUTIVE_FAILURES = 2
private const val COOLDOWN_NS = 30_000_000_000L // 30s

private class RotatingCommand(val factory: () -> ObdCommand) {
    var consecutiveFailures = 0
    var cooldownUntilNs = 0L
    fun isAvailable(nowNs: Long) = nowNs >= cooldownUntilNs
}

/**
 * Interleaves Tier A/B/C PID polling per the blueprint (section 6): Tier A runs continuously,
 * Tier B and Tier C are time-gated so slow PIDs never starve the fast ones. A PID that the
 * connected ECU doesn't support gets dropped after a couple of failures rather than retried
 * forever.
 */
class PidScheduler(
    private val elmSession: ElmSession,
    private val startElapsedNs: Long,
    /** Which vehicle's PID list to poll; see PidCatalog.kt for why this is per-vehicle rather
     * than a single hardcoded catalog. */
    private val catalog: PidCatalog,
    private val onMeasurement: suspend (MeasurementSample) -> Unit,
    private val onEvent: suspend (SchedulerEvent) -> Unit,
    /** Shared across reconnects within a session so sequence numbers stay monotonic. */
    private val sequence: AtomicLong = AtomicLong(0),
) {

    private val tierA = catalog.tierA().map { RotatingCommand(it) }.toMutableList()
    private val tierB = catalog.tierB().map { RotatingCommand(it) }.toMutableList()
    private val tierC = catalog.tierC().map { RotatingCommand(it) }.toMutableList()

    private var tierAIndex = 0
    private var tierBIndex = 0
    private var tierCIndex = 0
    private var lastTierBRunNs = 0L
    private var lastTierCRunNs = 0L

    private fun elapsedNs(): Long = System.nanoTime() - startElapsedNs

    /** One successful one-time read: the library's parsed/formatted value plus the verbatim raw
     * ELM text it came from, so a DTC (or any other one-time read) can be independently
     * re-checked later against what the adapter actually said, not just the parsed result. */
    data class OneTimeReadResult(val value: String, val rawResponse: String)

    /** Runs one-time metadata reads (VIN, DTCs, etc). Failures are logged, never fatal. */
    suspend fun runOneTimeReads(): Map<String, OneTimeReadResult> {
        val results = mutableMapOf<String, OneTimeReadResult>()
        for (factory in catalog.oneTimeReadOnly()) {
            val command = factory()
            try {
                val response = elmSession.connection.run(command, maxRetries = 3)
                results[command.tag] = OneTimeReadResult(response.formattedValue, response.rawResponse.value)
            } catch (e: Exception) {
                // e.toString(), not e.message: BadResponseException and its subtypes never set a
                // message (they override toString() instead), so e.message is always null and the
                // event used to silently degrade to just the exception's class name, losing the
                // one piece of information (the raw response text) that would explain why.
                onEvent(
                    SchedulerEvent(
                        elapsedNs = elapsedNs(),
                        eventType = "ONE_TIME_READ_FAILED",
                        severity = "INFO",
                        message = "${command.tag}: $e",
                    ),
                )
            }
        }
        return results
    }

    /** Runs continuously until [shouldContinue] returns false. Suspends naturally on each IO call. */
    suspend fun run(shouldContinue: () -> Boolean) {
        while (shouldContinue()) {
            pollNext(tierA) { tierAIndex }?.let { tierAIndex = it }

            val now = elapsedNs()
            if (now - lastTierBRunNs >= TIER_B_INTERVAL_MS * 1_000_000L && tierB.any { it.isAvailable(now) }) {
                pollNext(tierB) { tierBIndex }?.let { tierBIndex = it }
                lastTierBRunNs = now
            }
            if (now - lastTierCRunNs >= TIER_C_INTERVAL_MS * 1_000_000L && tierC.any { it.isAvailable(now) }) {
                pollNext(tierC) { tierCIndex }?.let { tierCIndex = it }
                lastTierCRunNs = now
            }
        }
    }

    private suspend fun pollNext(tier: MutableList<RotatingCommand>, currentIndex: () -> Int): Int? {
        val now = elapsedNs()
        if (tier.none { it.isAvailable(now) }) return null

        var idx = currentIndex() % tier.size
        // advance to the next entry not currently in cooldown
        var attempts = 0
        while (!tier[idx].isAvailable(now) && attempts < tier.size) {
            idx = (idx + 1) % tier.size
            attempts++
        }
        val entry = tier[idx]
        pollOne(entry)
        return (idx + 1) % tier.size
    }

    private suspend fun pollOne(entry: RotatingCommand) {
        val command = entry.factory()
        val startNs = elapsedNs()
        val wallTime = System.currentTimeMillis()
        try {
            val response = elmSession.connection.run(command, maxRetries = 3)
            entry.consecutiveFailures = 0
            val latencyMs = response.rawResponse.elapsedTime
            val rawNumeric = response.value.toDoubleOrNull()
            val range = PLAUSIBLE_RANGES[command.name]
            val implausible = rawNumeric != null && range != null && rawNumeric !in range
            onMeasurement(
                MeasurementSample(
                    sequence = sequence.incrementAndGet(),
                    wallTimeUtc = wallTime,
                    elapsedNs = startNs,
                    pidTag = command.tag,
                    canonicalName = command.name,
                    valueNumeric = if (implausible) null else rawNumeric,
                    valueText = if (rawNumeric == null || implausible) response.value else null,
                    unit = response.unit,
                    latencyMs = latencyMs,
                    qualityFlag = if (implausible) "IMPLAUSIBLE" else "OK",
                    rawResponse = response.rawResponse.value,
                ),
            )
            if (implausible) {
                onEvent(
                    SchedulerEvent(
                        elapsedNs = startNs,
                        eventType = "IMPLAUSIBLE_VALUE",
                        severity = "WARNING",
                        message = "${command.name} read $rawNumeric, outside plausible range $range; stored as raw text, not numeric",
                    ),
                )
            }
        } catch (e: BadResponseException) {
            entry.consecutiveFailures++
            // e.toString() is the library's own format ("ExceptionName while executing command
            // [tag], response [value]"); command/response aren't exposed as public properties, so
            // this is the only way to recover the raw ELM text. Logged on every single failed
            // attempt, not just the one that triggers cooldown below, so a PID that fails
            // consistently (e.g. LONG_TERM_BANK_1, see KNOWN_ISSUES.md) leaves a full forensic
            // trail of exactly what the adapter said each time, not just a exception class name.
            onEvent(
                SchedulerEvent(
                    elapsedNs = startNs,
                    eventType = "PID_NO_DATA",
                    severity = "INFO",
                    message = "${command.tag}: $e",
                ),
            )
            if (entry.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                entry.cooldownUntilNs = startNs + COOLDOWN_NS
                entry.consecutiveFailures = 0
                onEvent(
                    SchedulerEvent(
                        elapsedNs = startNs,
                        eventType = "PID_COOLDOWN",
                        severity = "INFO",
                        message = "${command.tag} paused ${COOLDOWN_NS / 1_000_000_000}s after repeated ${e::class.simpleName}, will retry",
                    ),
                )
            }
        } catch (e: Exception) {
            // IO-level failure (socket dropped etc). Let it propagate so the service can reconnect.
            throw e
        }
    }
}
