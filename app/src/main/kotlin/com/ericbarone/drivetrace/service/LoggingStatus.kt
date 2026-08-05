package com.ericbarone.drivetrace.service

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
 * arrived" isn't proof the vehicle is actually awake, so this tracks two independent, cheap
 * checks instead: does the VIN read back as a real identifier, and has RPM ever shown a
 * plausible non-zero value.
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
    val vinFound: TriState = TriState.PENDING,
    val engineDetected: TriState = TriState.PENDING,
    /** Set once the post-Stop backfill (see StreamingClient.backfillSession) finishes. */
    val backfillStatus: TriState = TriState.PENDING,
    val backfillMessage: String = "",
)

/**
 * Simple process-wide status bus so the Compose UI can observe the foreground service without
 * needing a bound-service/AIDL setup. Fine for a single-process personal app.
 */
object LoggingStatus {
    val state = MutableStateFlow(LoggingUiState())
}
