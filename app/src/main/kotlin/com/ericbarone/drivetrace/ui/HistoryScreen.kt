package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.SessionEntity
import com.ericbarone.drivetrace.service.BackfillRetryWorker
import com.ericbarone.drivetrace.streaming.analysisSummaryFromJson
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.ConsoleLine
import com.ericbarone.drivetrace.ui.components.EmptyState
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.StatusChip
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.Slate
import com.ericbarone.drivetrace.ui.theme.Space
import com.ericbarone.drivetrace.ui.theme.StatusCaution
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
 *
 * Laid out as a logbook: one card per drive, MPG right-aligned in a fixed column so the eye can
 * run straight down the numbers and compare drives, which is the only reason to open this screen
 * that isn't "why didn't that one upload". A divider-separated stack of text lines cannot be
 * scanned that way. See docs/DESIGN_SYSTEM.md.
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

    val pendingUploads = sessions.count { it.backfillStatus != "SUCCESS" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
    ) {
        HeaderBar(
            title = "Logbook",
            subtitle = when {
                loading -> null
                sessions.isEmpty() -> null
                pendingUploads > 0 -> "${sessions.size} drives, $pendingUploads not uploaded"
                else -> "${sessions.size} drives, all uploaded"
            },
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )

        when {
            loading -> EmptyState(title = "Loading", body = "Reading the local database...")
            sessions.isEmpty() -> EmptyState(
                title = "No trips logged yet",
                body = "Start a drive from the setup screen and it will appear here.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = Space.lg,
                    bottom = Space.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session = session,
                        onRetry = {
                            BackfillRetryWorker.enqueueRetryNow(context, session.sessionId)
                            scope.launch { reload() }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SessionEntity, onRetry: () -> Unit) {
    val type = LocalReadoutType.current
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US) }
    val summary = remember(session.analysisSummaryJson) {
        session.analysisSummaryJson?.let { analysisSummaryFromJson(it) }
    }

    val uploadTone = when (session.backfillStatus) {
        "SUCCESS" -> Tone.LIVE
        "FAILED" -> Tone.FAULT
        else -> Tone.UNKNOWN
    }
    val durationMin = session.endWallTimeUtc?.let { (it - session.startWallTimeUtc) / 60000.0 }

    InstrumentPanel(
        modifier = Modifier.fillMaxWidth(),
        // The left bar carries upload state for the whole card, so a scroll down the list shows
        // at a glance which drives still owe an upload without reading a single word.
        accent = uploadTone.color,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dateFmt.format(Date(session.startWallTimeUtc)),
                    style = type.small,
                    color = Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        durationMin?.let { append("%.0f min".format(it)); append("  /  ") }
                        append(session.completionStatus.lowercase())
                    },
                    style = type.unit,
                    color = Ash,
                    maxLines = 1,
                )
            }
            // MPG in a fixed right-hand column: this is the value the list exists to compare.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    summary?.overallMpg?.let { "%.1f".format(it) } ?: "--",
                    style = type.medium,
                    color = if (summary?.overallMpg != null) AccentMixture else Slate,
                    maxLines = 1,
                )
                Text("MPG", style = type.label, color = Ash)
            }
        }

        // The note is the whole reason SessionEntity.notes exists: "same route, different result"
        // is only answerable if something recorded what was different. Kept to two lines and set
        // in Mist so it reads as the driver's own annotation, below the machine-written figures
        // above it but above the pipeline chips, which are about the app rather than the drive.
        session.notes?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(Space.sm))
            Text(
                note,
                style = type.unit,
                color = Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(Space.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatusChip(
                text = "upload ${session.backfillStatus.lowercase()}",
                tone = uploadTone,
            )
            val analysisChip: Pair<String, Tone>? = when {
                summary?.overallMpg != null -> "analysis done" to Tone.LIVE
                session.analysisStatus == "FAILED" -> "analysis failed" to Tone.FAULT
                session.backfillStatus == "SUCCESS" -> "analysis pending" to Tone.UNKNOWN
                else -> null
            }
            analysisChip?.let { (text, tone) -> StatusChip(text = text, tone = tone) }
        }

        // Never session.backfillMessage. On the failure path that field holds the raw transport
        // exception, which names the server's hostname, public IP and port; the row keeps it for
        // diagnosis but no screen renders it. See ui/PipelineMessages.kt.
        if (session.backfillStatus == "FAILED") {
            Spacer(Modifier.height(Space.sm))
            ConsoleLine(UPLOAD_FAILED_MESSAGE, color = Tone.FAULT.color)
        }

        if (summary != null && summary.flags.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            Caption(
                "${summary.flags.size} anomaly flag${if (summary.flags.size == 1) "" else "s"}",
                color = StatusCaution,
            )
        }

        if (session.backfillStatus != "SUCCESS") {
            Spacer(Modifier.height(Space.md))
            SecondaryAction(
                text = "Retry upload",
                onClick = onRetry,
                contentColor = Mist,
                minHeight = Space.compactTarget,
            )
        }
    }
}
