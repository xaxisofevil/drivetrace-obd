package com.ericbarone.drivetrace.service

import com.ericbarone.drivetrace.obd.MeasurementSample
import com.ericbarone.drivetrace.streaming.AnalysisSummary
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    READY,
    LOGGING,
    RECONNECTING,
    FAILED,
}

/**
 * Some cheap ELM327 clones don't return a clean error when the ECU is asleep; they fabricate
 * plausible-looking placeholder frames instead (all-zero values, 0xFFFF sentinels). "A response
 * arrived" isn't proof the vehicle is actually awake, so this is a cheap check instead: has RPM
 * ever shown a plausible non-zero value. Used to also cross-check against a VIN read, dropped
 * (see KNOWN_ISSUES.md) since VIN has never once worked on the test vehicle, an always-"no"
 * signal carries no information and was just clutter in the UI.
 */
enum class TriState { PENDING, YES, NO }

data class LoggingUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionId: Long? = null,
    val startedAtMs: Long? = null,
    val measurementCount: Int = 0,
    val locationCount: Int = 0,
    val lastSampleAtMs: Long? = null,
    val reconnectCount: Int = 0,
    val statusMessage: String = "",
    val engineDetected: TriState = TriState.PENDING,
    /**
     * The most recent sample from every PID that has answered this session, keyed on
     * `canonicalName` (the real strings, see docs/DATA_SCHEMA.md). This is what makes a live
     * gauge cluster possible at all: before it, this object carried session bookkeeping only and
     * the Logging screen could not show RPM, speed or trim while driving no matter how it was
     * styled.
     *
     * Deliberately [MeasurementSample], the scheduler's own poll-result type, rather than a
     * parallel UI shape. It already carries everything a live readout needs, value, unit,
     * `qualityFlag` and `wallTimeUtc` for staleness, and reusing it means the number on screen is
     * literally the row that went into Room rather than a re-derived copy that can drift from it.
     *
     * A PID that has never answered is simply absent, not present with a null value: the UI's
     * rule is that a gauge which has nothing to say does not occupy a slot. Note the map holds
     * the latest sample whatever its quality, including `IMPLAUSIBLE` ones, so the UI can say
     * "this reading is currently garbage" rather than quietly showing the last good number as if
     * it were current.
     */
    val latestValues: Map<String, MeasurementSample> = emptyMap(),
    /** Set once the post-Stop backfill (see StreamingClient.backfillSession) finishes. */
    val backfillStatus: TriState = TriState.PENDING,
    val backfillMessage: String = "",
    /** Set once the server-side analysis (see analysis_worker.py), triggered right after a
     * successful backfill, finishes or times out. */
    val analysisStatus: TriState = TriState.PENDING,
    val analysisSummary: AnalysisSummary? = null,
    val analysisMessage: String = "",
)

/**
 * Simple process-wide status bus so the Compose UI can observe the foreground service without
 * needing a bound-service/AIDL setup. Fine for a single-process personal app.
 */
object LoggingStatus {
    val state = MutableStateFlow(LoggingUiState())
}
