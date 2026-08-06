package com.ericbarone.drivetrace.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.ericbarone.drivetrace.export.TripSummary
import com.ericbarone.drivetrace.export.computeTripSummary
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
    var tripSummary by remember { mutableStateOf<TripSummary?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsedSeconds = status.startedAtMs?.let { (nowMs - it) / 1000 } ?: 0
    val lastSampleAgeSeconds = status.lastSampleAtMs?.let { (nowMs - it) / 1000 }
    val sessionComplete = status.connectionState == ConnectionState.DISCONNECTED && status.sessionId != null

    LaunchedEffect(sessionComplete, status.sessionId) {
        val id = status.sessionId
        if (sessionComplete && id != null) {
            tripSummary = computeTripSummary(context, id)
        }
    }

    // The post-drive summary (PC analysis, flags, on-device estimate) can easily push past
    // screen height, especially with several anomaly flags; without scroll, the New Session /
    // Export buttons below become genuinely unreachable, not just hidden until you resize.
    // systemBarsPadding() first: content was drawing straight under the status bar and the
    // on-screen nav bar (confirmed from a real screenshot, "New Session" nearly touching the
    // nav bar), not just missing a few dp, the 24dp padding after it is on top of that, not a
    // replacement for it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
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
        TriStateRow("Engine detected", status.engineDetected)
        if (status.engineDetected == TriState.NO) {
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
        if (sessionComplete) {
            TriStateRow("Server backfill (upload verified complete)", status.backfillStatus)
            if (status.backfillMessage.isNotBlank()) {
                Text(
                    status.backfillMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.backfillStatus == TriState.NO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.backfillStatus == TriState.YES) {
                TriStateRow("PC analysis", status.analysisStatus)
                val analysis = status.analysisSummary
                when {
                    status.analysisStatus == TriState.PENDING ->
                        Text("Waiting on the PC to analyze this drive...", style = MaterialTheme.typography.bodySmall)
                    status.analysisStatus == TriState.NO ->
                        Text(status.analysisMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    analysis != null -> {
                        analysis.overallMpg?.let { StatusRow("Trip MPG (PC, careful)", "%.1f".format(it)) }
                        analysis.distanceGpsKm?.let { StatusRow("Distance", "%.2f km".format(it)) }
                        analysis.idleFractionPct?.let { StatusRow("Idle fraction", "%.1f%%".format(it)) }
                        analysis.warmupMinutes?.let { StatusRow("Warm-up", "%.1f min".format(it)) }
                        if ((analysis.brakingEventCount ?: 0) > 0) {
                            StatusRow("Braking events", analysis.brakingEventCount.toString())
                            analysis.brakingFuelEquivMl?.let {
                                StatusRow("Est. fuel to brake heat", "%.0f mL".format(it))
                            }
                            analysis.brakingEventsWithoutCoast?.let {
                                if (it > 0) {
                                    Text(
                                        "$it of ${analysis.brakingEventCount} had no coast phase first, " +
                                            "speed was carried right up to the brakes.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Text(
                                "Estimate only: braking is inferred from deceleration rate, not " +
                                    "measured, and the mL figure assumes a fixed vehicle mass/engine " +
                                    "efficiency. See analysis_report.md for details.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        for (flag in analysis.flags) {
                            Text("- $flag", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            val summary = tripSummary
            when {
                summary == null -> StatusRow("Trip MPG (on-device est.)", "calculating...")
                summary.overallMpg == null -> StatusRow("Trip MPG (on-device est.)", "n/a (no fuel data)")
                else -> {
                    StatusRow("Distance (GPS, on-device)", "%.2f km".format(summary.distanceKm))
                    StatusRow("Trip MPG (on-device est.)", "%.1f".format(summary.overallMpg))
                }
            }
            Text(
                "On-device estimate is rougher: total distance / total fuel burned, not gated " +
                    "on stoichiometric operation like the PC analysis above.",
                style = MaterialTheme.typography.bodySmall,
            )
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
