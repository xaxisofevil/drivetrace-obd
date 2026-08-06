package com.ericbarone.drivetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.service.BackfillRetryWorker
import com.ericbarone.drivetrace.service.DriveLoggingService
import com.ericbarone.drivetrace.service.LoggingStatus
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.ui.HistoryScreen
import com.ericbarone.drivetrace.ui.LoggingScreen
import com.ericbarone.drivetrace.ui.SetupScreen
import com.ericbarone.drivetrace.ui.theme.DriveTraceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opportunistic: catches anything stranded from a previous run (app killed before a
        // failed backfill could retry, phone rebooted, etc.) every time the app is opened, on
        // top of WorkManager's own network-regained retries. Cheap and safe to call every launch,
        // enqueueUniqueWork + KEEP means this just no-ops if a sweep is already queued/running.
        BackfillRetryWorker.enqueueSweep(applicationContext)

        setContent {
            DriveTraceTheme {
                val status by LoggingStatus.state.collectAsState()
                var showHistory by remember { mutableStateOf(false) }
                when {
                    status.sessionId != null -> LoggingScreen(
                        status = status,
                        onStop = { startService(DriveLoggingService.stopIntent(this)) },
                        onNewSession = { LoggingStatus.state.value = LoggingUiState() },
                    )
                    showHistory -> HistoryScreen(onBack = { showHistory = false })
                    else -> SetupScreen(
                        onStartLogging = { address ->
                            ContextCompat.startForegroundService(this, DriveLoggingService.startIntent(this, address))
                        },
                        onShowHistory = { showHistory = true },
                    )
                }
            }
        }
    }
}
