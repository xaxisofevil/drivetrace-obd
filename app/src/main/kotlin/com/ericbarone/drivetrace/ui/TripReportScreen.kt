package com.ericbarone.drivetrace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.SessionEntity
import com.ericbarone.drivetrace.data.computeAdapterHealth
import com.ericbarone.drivetrace.data.readSessionDtcs
import com.ericbarone.drivetrace.data.vehicleDisplayName
import com.ericbarone.drivetrace.export.CsvExporter
import com.ericbarone.drivetrace.export.PdfTripReportExporter
import com.ericbarone.drivetrace.export.TripReport
import com.ericbarone.drivetrace.export.buildTripReport
import com.ericbarone.drivetrace.export.computeTripSummary
import com.ericbarone.drivetrace.export.durationSeconds
import com.ericbarone.drivetrace.export.reportSource
import com.ericbarone.drivetrace.ui.components.ActionBar
import com.ericbarone.drivetrace.ui.components.EmptyState
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.PrimaryAction
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The trip report for a drive that is over, opened by tapping its card in the logbook.
 *
 * **Nothing about the report itself is new here.** The body is `CompleteBody`, the same composable
 * the Session-Complete screen draws, fed by the same `buildTripReport` from the same four inputs;
 * the only difference upstream is that the pipeline's own state arrives as
 * `SessionEntity.reportSource()` off a Room row instead of `LoggingUiState.reportSource()` off the
 * status bus. Every judgement the report makes, which figure earns the hero, whether the drive gets
 * a band, which tile the hero already claimed, which capture lines are verdicts and which are
 * counts, is made once, in `export/TripReport.kt`, for both callers. A second screen that re-decided
 * any of that would be a second opinion about the same drive, which is exactly what the PDF exporter
 * already exists as a warning about.
 *
 * **Everything genuinely different is chrome.** This screen owns a header with a back affordance
 * (the drive it names is in the past, so it can say whose drive and when), and a pinned `ActionBar`
 * carrying two of the three actions the just-finished report offers:
 *
 *  - **Export CSV** and **Report PDF** work unchanged. Both only ever needed a `sessionId` and, for
 *    the PDF, the built report, and both read Room, so they work on a drive from any night.
 *  - **Stop logging** is not offered, because there is nothing running to stop.
 *  - **New session** is not offered either. It resets the live status bus, which is a statement
 *    about the drive that just ended; from the logbook it would mean nothing and it is not the way
 *    out of this screen.
 *
 * The way out is the header's back chevron, which is how `HistoryScreen`, `SettingsScreen` and
 * `CompareScreen` are all already left. A third button in the bar repeating it would be the only
 * Back button in the app.
 *
 * **The whole report is loaded before any of it is drawn,** unlike the live screen, which fills the
 * report in as the on-device pass returns and honestly says `calculating...` in the hero meanwhile.
 * Nothing is being calculated here in that sense: the drive ended, the numbers are all sitting in
 * Room, and a hero that says `calculating...` for the half-second Room takes to answer would be
 * describing this screen's own disk read rather than anything about the drive.
 */
@Composable
fun TripReportScreen(sessionId: Long, onBack: () -> Unit) {
    // Same wiring every other non-root screen uses, and for the reason HistoryScreen's own comment
    // gives: with no navigation library, the system back gesture is never told this screen exists
    // and falls through to finishing the Activity. One callback serves it and the header chevron
    // both, so the two can never disagree about what "back" means here.
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var session by remember(sessionId) { mutableStateOf<SessionEntity?>(null) }
    var report by remember(sessionId) { mutableStateOf<TripReport?>(null) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var exportingCsv by remember(sessionId) { mutableStateOf(false) }
    var exportingPdf by remember(sessionId) { mutableStateOf(false) }

    // Room only, every one of them, so a drive that has never once reached the server reopens with
    // its full report: economy, adapter health and stored codes all derive from local rows. The
    // pipeline block is then the only part that has anything to say about the server at all, which
    // is exactly where the app's best-effort posture belongs.
    LaunchedEffect(sessionId) {
        loading = true
        val row = AppDatabase.getInstance(context).sessionDao().getSession(sessionId)
        report = row?.let {
            buildTripReport(
                it.reportSource(),
                computeTripSummary(context, sessionId),
                computeAdapterHealth(context, sessionId),
                readSessionDtcs(context, sessionId),
                it.durationSeconds(),
            )
        }
        session = row
        loading = false
    }

    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US) }
    val current = report

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            // The report carries the drive-note editor partway down its scroll, same as it does on
            // the live screen, and without this the IME covers both it and the pinned bar.
            .imePadding(),
    ) {
        HeaderBar(
            title = "Trip report",
            subtitle = session?.let {
                "${vehicleDisplayName(it.vehicleProfile)}  /  ${dateFmt.format(Date(it.startWallTimeUtc))}"
            },
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.section),
        ) {
            Spacer(Modifier.height(Space.xs))
            when {
                loading -> EmptyState(title = "Loading", body = "Reading the local database...")
                current == null -> EmptyState(
                    title = "Drive missing",
                    body = "That drive is no longer in the local database.",
                )
                else -> CompleteBody(current, sessionId)
            }
            Spacer(Modifier.height(Space.sm))
        }

        // No bar at all for a drive that is not there: an Export control over an empty state is a
        // button with nothing behind it.
        if (current != null) {
            ActionBar {
                PrimaryAction(
                    text = if (exportingCsv) "Exporting..." else "Export CSV",
                    enabled = !exportingCsv,
                    onClick = {
                        exportingCsv = true
                        scope.launch {
                            val zip = CsvExporter(context).export(sessionId)
                            exportingCsv = false
                            share(context, zip, "application/zip", "Export drive data")
                        }
                    },
                )
                // Full width rather than half, because the row it used to share with "New session"
                // has nothing else in it here. Still secondary: the CSV bundle is the drive's data
                // of record and the PDF is the readable copy of the screen above it, which is the
                // same ranking the just-finished report gives them.
                SecondaryAction(
                    text = "Report PDF",
                    busy = exportingPdf,
                    onClick = {
                        exportingPdf = true
                        scope.launch {
                            val pdf = PdfTripReportExporter(context).export(sessionId, current)
                            exportingPdf = false
                            share(context, pdf, "application/pdf", "Share trip report")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
