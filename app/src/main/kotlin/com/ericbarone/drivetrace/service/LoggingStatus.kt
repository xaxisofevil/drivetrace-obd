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

data class LoggingUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionId: Long? = null,
    val startedAtMs: Long? = null,
    val measurementCount: Int = 0,
    val locationCount: Int = 0,
    val lastSampleAtMs: Long? = null,
    val reconnectCount: Int = 0,
    val statusMessage: String = "",
)

/**
 * Simple process-wide status bus so the Compose UI can observe the foreground service without
 * needing a bound-service/AIDL setup. Fine for a single-process personal app.
 */
object LoggingStatus {
    val state = MutableStateFlow(LoggingUiState())
}
