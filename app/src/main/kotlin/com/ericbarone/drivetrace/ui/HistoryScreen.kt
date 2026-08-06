package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.SessionEntity
import com.ericbarone.drivetrace.service.BackfillRetryWorker
import com.ericbarone.drivetrace.streaming.analysisSummaryFromJson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every session ever logged, read straight from local Room (the authoritative copy, per the
 * blueprint's reliability rules), not the server, so this works even if the server's never been
 * reachable for a given session. "Retry upload" is the "force failed uploads" ask made concrete:
 * queues BackfillRetryWorker for just that session, which runs even after this screen (and the
 * whole app) is closed again, see BackfillRetryWorker's own docs for why WorkManager, not a
 * plain coroutine, is what makes that guarantee possible.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        sessions = AppDatabase.getInstance(context).sessionDao().getAllSessions()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text("Trip History", style = MaterialTheme.typography.headlineSmall)
        }

        when {
            loading -> Text("Loading...")
            sessions.isEmpty() -> Text("No trips logged yet.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionHistoryRow(
                        session = session,
                        onRetry = {
                            BackfillRetryWorker.enqueueRetryNow(context, session.sessionId)
                            scope.launch { reload() }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryRow(session: SessionEntity, onRetry: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US) }
    val summary = remember(session.analysisSummaryJson) {
        session.analysisSummaryJson?.let { analysisSummaryFromJson(it) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(dateFmt.format(Date(session.startWallTimeUtc)), style = MaterialTheme.typography.titleMedium)

        val durationMin = session.endWallTimeUtc?.let { (it - session.startWallTimeUtc) / 60000.0 }
        Text(
            durationMin?.let { "%.0f min, %s".format(it, session.completionStatus.lowercase()) }
                ?: session.completionStatus.lowercase(),
            style = MaterialTheme.typography.bodySmall,
        )

        val backfillColor = when (session.backfillStatus) {
            "SUCCESS" -> Color(0xFF2E7D32)
            "FAILED" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            "Upload: ${session.backfillStatus.lowercase()}",
            color = backfillColor,
            style = MaterialTheme.typography.bodySmall,
        )
        if (session.backfillStatus == "FAILED" && !session.backfillMessage.isNullOrBlank()) {
            Text(session.backfillMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when {
            summary?.overallMpg != null ->
                Text("Trip MPG: %.1f".format(summary.overallMpg), style = MaterialTheme.typography.bodySmall)
            session.analysisStatus == "FAILED" ->
                Text("Analysis failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            session.backfillStatus == "SUCCESS" ->
                Text("Analysis pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (session.backfillStatus != "SUCCESS") {
            Button(onClick = onRetry) { Text("Retry upload") }
        }
    }
}
