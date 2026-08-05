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
            val numeric = response.value.toDoubleOrNull()
            onMeasurement(
                MeasurementSample(
                    sequence = sequence.incrementAndGet(),
                    wallTimeUtc = wallTime,
                    elapsedNs = startNs,
                    pidTag = command.tag,
                    canonicalName = command.name,
                    valueNumeric = numeric,
                    valueText = if (numeric == null) response.value else null,
                    unit = response.unit,
                    latencyMs = latencyMs,
                    qualityFlag = "OK",
                ),
            )
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
