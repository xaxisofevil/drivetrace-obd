package com.ericbarone.drivetrace.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.Color
import com.ericbarone.drivetrace.export.CsvExporter
import com.ericbarone.drivetrace.service.ConnectionState
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.service.TriState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoggingScreen(status: LoggingUiState, onStop: () -> Unit, onNewSession: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showStopConfirm by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsedSeconds = status.startedAtMs?.let { (nowMs - it) / 1000 } ?: 0
    val lastSampleAgeSeconds = status.lastSampleAtMs?.let { (nowMs - it) / 1000 }
    val sessionComplete = status.connectionState == ConnectionState.DISCONNECTED && status.sessionId != null

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (sessionComplete) "Session complete" else "Logging",
            style = MaterialTheme.typography.headlineMedium,
        )

        StatusRow("Connection", status.connectionState.name)
        StatusRow("Elapsed", formatDuration(elapsedSeconds))
        StatusRow("Measurements", status.measurementCount.toString())
        StatusRow("GPS fixes", status.locationCount.toString())
        StatusRow("Last sample", lastSampleAgeSeconds?.let { "${it}s ago" } ?: "-")
        TriStateRow("Vehicle responding (VIN)", status.vinFound)
        TriStateRow("Engine detected", status.engineDetected)
        if (status.vinFound == TriState.NO || status.engineDetected == TriState.NO) {
            Text(
                "Not getting real data from the vehicle. A response arriving isn't proof the " +
                    "car's awake, this adapter can fabricate placeholder values instead of " +
                    "erroring. Check the ignition and adapter connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status.reconnectCount > 0) {
            StatusRow("Reconnects", status.reconnectCount.toString())
        }
        if (status.statusMessage.isNotBlank()) {
            Text(status.statusMessage, style = MaterialTheme.typography.bodySmall)
        }

        if (!sessionComplete) {
            Button(onClick = { showStopConfirm = true }) { Text("Stop") }
        } else {
            Button(
                enabled = !exporting,
                onClick = {
                    val sessionId = status.sessionId ?: return@Button
                    exporting = true
                    scope.launch {
                        val zip = CsvExporter(context).export(sessionId)
                        exporting = false
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", zip,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Export drive data"))
                    }
                },
            ) {
                Text(if (exporting) "Exporting..." else "Export CSV")
            }
            OutlinedButton(onClick = onNewSession) { Text("New Session") }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop logging?") },
            text = { Text("This ends the current drive session.") },
            confirmButton = {
                TextButton(onClick = { showStopConfirm = false; onStop() }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun TriStateRow(label: String, state: TriState) {
    val (text, color) = when (state) {
        TriState.PENDING -> "checking..." to MaterialTheme.colorScheme.onSurfaceVariant
        TriState.YES -> "yes" to Color(0xFF2E7D32)
        TriState.NO -> "no" to MaterialTheme.colorScheme.error
    }
    Text("$label: $text", style = MaterialTheme.typography.bodyLarge, color = color)
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
