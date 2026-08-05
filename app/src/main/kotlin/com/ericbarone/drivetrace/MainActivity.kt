package com.ericbarone.drivetrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.service.DriveLoggingService
import com.ericbarone.drivetrace.service.LoggingStatus
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.ui.LoggingScreen
import com.ericbarone.drivetrace.ui.SetupScreen
import com.ericbarone.drivetrace.ui.theme.DriveTraceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DriveTraceTheme {
                val status by LoggingStatus.state.collectAsState()
                if (status.sessionId != null) {
                    LoggingScreen(
                        status = status,
                        onStop = { startService(DriveLoggingService.stopIntent(this)) },
                        onNewSession = { LoggingStatus.state.value = LoggingUiState() },
                    )
                } else {
                    SetupScreen(
                        onStartLogging = { address ->
                            ContextCompat.startForegroundService(this, DriveLoggingService.startIntent(this, address))
                        },
                    )
                }
            }
        }
    }
}
