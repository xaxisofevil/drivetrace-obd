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
 * Sanity clamp: kotlin-obd-api's multi-byte formulas (RPM, module voltage) occasionally fold
 * extra trailing bytes from adjacent responses into the calculation, producing values off by
 * many orders of magnitude (observed directly: RPM read back as 3.8 trillion). Rather than fix
 * that in a third-party library under time pressure, flag anything physically impossible for
 * the PID rather than storing it as real data, per the blueprint's "detect impossible values,
 * flag without deleting" reliability rule (section 9). Deliberately generous ranges: the goal is
 * catching parser garbage, not being a strict physical model.
 */
private val PLAUSIBLE_RANGES: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
    "Engine RPM" to 0.0..10_000.0,
    "Vehicle Speed" to 0.0..300.0,
    "Control Module Power Supply" to 0.0..30.0,
    "Calculated Engine Load" to 0.0..100.0,
    "Engine Load" to 0.0..100.0,
    "Mass Air Flow" to 0.0..1000.0,
    "Commanded Equivalence Ratio" to 0.0..3.0,
    "Fuel-Air Commanded Equivalence Ratio" to 0.0..3.0,
    "Short Term Fuel Trim Bank 1" to -100.0..100.0,
    "Long Term Fuel Trim Bank 1" to -100.0..100.0,
    "Engine Coolant Temperature" to -40.0..215.0,
    "Intake Air Temperature" to -40.0..215.0,
    "Ambient Air Temperature" to -40.0..215.0,
    "Throttle Position" to 0.0..100.0,
    "Intake Manifold Pressure" to 0.0..400.0,
    "Barometric Pressure" to 50.0..150.0,
)

/** A PID that fails this many times in a row is dropped from rotation for the rest of the session. */
private const val MAX_CONSECUTIVE_FAILURES = 2

private class RotatingCommand(val factory: () -> ObdCommand) {
    var consecutiveFailures = 0
    var dropped = false
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
    private val onMeasurement: suspend (MeasurementSample) -> Unit,
    private val onEvent: suspend (SchedulerEvent) -> Unit,
    /** Shared across reconnects within a session so sequence numbers stay monotonic. */
    private val sequence: AtomicLong = AtomicLong(0),
) {

    private val tierA = PidCatalog.tierA().map { RotatingCommand(it) }.toMutableList()
    private val tierB = PidCatalog.tierB().map { RotatingCommand(it) }.toMutableList()
    private val tierC = PidCatalog.tierC().map { RotatingCommand(it) }.toMutableList()

    private var tierAIndex = 0
    private var tierBIndex = 0
    private var tierCIndex = 0
    private var lastTierBRunNs = 0L
    private var lastTierCRunNs = 0L

    private fun elapsedNs(): Long = System.nanoTime() - startElapsedNs

    /** Runs one-time metadata reads (VIN, DTCs, etc). Failures are logged, never fatal. */
    suspend fun runOneTimeReads(): Map<String, String> {
        val results = mutableMapOf<String, String>()
        for (factory in PidCatalog.oneTimeReadOnly()) {
            val command = factory()
            try {
                val response = elmSession.connection.run(command, maxRetries = 3)
                results[command.tag] = response.formattedValue
            } catch (e: Exception) {
                onEvent(
                    SchedulerEvent(
                        elapsedNs = elapsedNs(),
                        eventType = "ONE_TIME_READ_FAILED",
                        severity = "INFO",
                        message = "${command.tag}: ${e.message ?: e::class.simpleName}",
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
            if (now - lastTierBRunNs >= TIER_B_INTERVAL_MS * 1_000_000L && tierB.any { !it.dropped }) {
                pollNext(tierB) { tierBIndex }?.let { tierBIndex = it }
                lastTierBRunNs = now
            }
            if (now - lastTierCRunNs >= TIER_C_INTERVAL_MS * 1_000_000L && tierC.any { !it.dropped }) {
                pollNext(tierC) { tierCIndex }?.let { tierCIndex = it }
                lastTierCRunNs = now
            }
        }
    }

    private suspend fun pollNext(tier: MutableList<RotatingCommand>, currentIndex: () -> Int): Int? {
        val active = tier.filter { !it.dropped }
        if (active.isEmpty()) return null

        var idx = currentIndex() % tier.size
        // advance to the next non-dropped entry
        var attempts = 0
        while (tier[idx].dropped && attempts < tier.size) {
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
            if (entry.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                entry.dropped = true
                onEvent(
                    SchedulerEvent(
                        elapsedNs = startNs,
                        eventType = "PID_UNSUPPORTED",
                        severity = "INFO",
                        message = "${command.tag} dropped after repeated ${e::class.simpleName}",
                    ),
                )
            }
        } catch (e: Exception) {
            // IO-level failure (socket dropped etc). Let it propagate so the service can reconnect.
            throw e
        }
    }
}
