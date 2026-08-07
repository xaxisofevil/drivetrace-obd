package com.ericbarone.drivetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.service.BackfillRetryWorker
import com.ericbarone.drivetrace.service.DriveLoggingService
import com.ericbarone.drivetrace.service.LoggingStatus
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.ui.DisplaySettings
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
        // Before setContent, so the first frame is already in the right display mode rather than
        // rendering the standard palette and correcting itself a frame later.
        DisplaySettings.load(applicationContext)

        setContent {
            val highContrast by DisplaySettings.highContrast.collectAsState()
            DriveTraceTheme(highContrast = highContrast) {
                val status by LoggingStatus.state.collectAsState()
                // rememberSaveable, not remember: this screen used to warp back to Setup on
                // rotation (see KNOWN_ISSUES.md) because plain remember state doesn't survive
                // Activity recreation. android:screenOrientation="portrait" in the manifest now
                // stops rotation from causing that recreation at all, but this stays
                // rememberSaveable anyway as defense-in-depth against the same class of loss from
                // process death under memory pressure while the drive-logging foreground service
                // keeps running in the background through a long drive.
                var showHistory by rememberSaveable { mutableStateOf(false) }
                when {
                    status.sessionId != null -> LoggingScreen(
                        status = status,
                        onStop = { note -> startService(DriveLoggingService.stopIntent(this, note)) },
                        onNewSession = { LoggingStatus.state.value = LoggingUiState() },
                    )
                    showHistory -> HistoryScreen(onBack = { showHistory = false })
                    else -> SetupScreen(
                        onStartLogging = { address, vehicleProfile ->
                            ContextCompat.startForegroundService(
                                this, DriveLoggingService.startIntent(this, address, vehicleProfile),
                            )
                        },
                        onShowHistory = { showHistory = true },
                    )
                }
            }
        }
    }
}
