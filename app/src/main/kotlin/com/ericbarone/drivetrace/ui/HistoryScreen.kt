package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.SessionEntity
import com.ericbarone.drivetrace.export.TripSummary
import com.ericbarone.drivetrace.export.computeTripSummary
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.service.BackfillRetryWorker
import com.ericbarone.drivetrace.streaming.analysisSummaryFromJson
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.ChoiceChip
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
 * reachable for a given session.
 *
 * Two retry controls, never both at once, since at most one thing can be outstanding. "Retry
 * upload" is the "force failed uploads" ask made concrete, and "Retry analysis" covers the case
 * where the upload landed and only the server-side analysis did not, without re-sending a drive
 * the server already holds. Both queue BackfillRetryWorker for just that session, which runs even
 * after this screen (and the whole app) is closed again, see BackfillRetryWorker's own docs for
 * why WorkManager, not a plain coroutine, is what makes that guarantee possible. Both also read
 * their in-flight state back out of WorkManager rather than tracking it here.
 *
 * Laid out as a logbook: one card per drive, MPG right-aligned in a fixed column so the eye can
 * run straight down the numbers and compare drives, which is the only reason to open this screen
 * that isn't "why didn't that one upload". A divider-separated stack of text lines cannot be
 * scanned that way. Each card names its vehicle, and the list filters down to one vehicle once
 * more than one has logged a drive, because a column of MPG figures from two different cars is
 * not a column that can be compared. See docs/DESIGN_SYSTEM.md.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // The stored VehicleProfile name, or null for "All". Saveable so a rotation or a trip through
    // the note editor's IME doesn't quietly widen the list back out under the user.
    var vehicleFilter by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun reload() {
        sessions = AppDatabase.getInstance(context).sessionDao().getAllSessions()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    // Only vehicles that actually logged something, in the enum's own order so the row doesn't
    // reshuffle itself as drives come and go. A profile nobody has driven is not a filter, it is
    // a dead control.
    val loggedVehicles = remember(sessions) {
        val present = sessions.mapTo(mutableSetOf()) { it.vehicleProfile }
        VehicleProfile.entries.map { it.name }.filter { it in present } +
            present.filter { name -> VehicleProfile.entries.none { it.name == name } }.sorted()
    }
    // Nothing to filter when every drive is the same car, and a control that can only ever be in
    // one state is clutter on a screen whose job is scanning a column of figures.
    val showFilter = loggedVehicles.size > 1
    val visibleSessions = remember(sessions, vehicleFilter, showFilter) {
        if (!showFilter || vehicleFilter == null) sessions
        else sessions.filter { it.vehicleProfile == vehicleFilter }
    }

    val pendingUploads = visibleSessions.count { it.backfillStatus != "SUCCESS" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            // A card can now expand into a note field; without this the IME covers the card
            // being edited.
            .imePadding(),
    ) {
        HeaderBar(
            title = "Logbook",
            // Counts what is on screen, not what is in the database. With a filter applied the
            // subtitle describing the whole database would be answering a question nobody asked.
            subtitle = when {
                loading -> null
                visibleSessions.isEmpty() -> null
                pendingUploads > 0 -> "${visibleSessions.size} drives, $pendingUploads not uploaded"
                else -> "${visibleSessions.size} drives, all uploaded"
            },
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )

        // Pinned above the scroll rather than riding in it: a filter you have to scroll back up
        // to reach is a filter you stop using. Horizontally scrollable so a third vehicle widens
        // the row instead of squeezing the names.
        if (!loading && showFilter) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter, vertical = Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                ChoiceChip(
                    text = "All",
                    selected = vehicleFilter == null,
                    onSelect = { vehicleFilter = null },
                )
                for (name in loggedVehicles) {
                    ChoiceChip(
                        text = vehicleLabel(name),
                        selected = vehicleFilter == name,
                        onSelect = { vehicleFilter = name },
                    )
                }
            }
        }

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
                items(visibleSessions, key = { it.sessionId }) { session ->
                    SessionCard(
                        session = session,
                        // No reload() here. The old version reloaded the instant the button was
                        // tapped, which read the row back before WorkManager had even started the
                        // job, so nothing on screen changed and the button was indistinguishable
                        // from a dead one. The card now watches the work itself and reloads when
                        // it finishes, which is the moment the row on disk actually changed.
                        onRetry = { BackfillRetryWorker.enqueueRetryNow(context, session.sessionId) },
                        onRetryAnalysis = {
                            BackfillRetryWorker.enqueueAnalysisRetryNow(context, session.sessionId)
                        },
                        onRetryFinished = { scope.launch { reload() } },
                        onNoteSaved = { scope.launch { reload() } },
                    )
                }
            }
        }
    }
}

/**
 * The stored `vehicleProfile` as the name of an actual car. Falls back to the raw stored string
 * rather than to nothing if the enum no longer has that entry, since the row's own value is then
 * the only record left of which vehicle the drive belongs to and losing it silently is worse than
 * printing an enum name.
 */
private fun vehicleLabel(storedName: String): String =
    VehicleProfile.entries.find { it.name == storedName }?.displayName ?: storedName

/**
 * True while the unique work queued under [uniqueWorkName] is enqueued or running, straight from
 * WorkManager's own store rather than from a boolean this screen sets on tap. WorkManager is
 * already the authority on whether that job is outstanding, it stays the authority after the app's
 * process is killed and restarted, and a hand-rolled flag would quietly disagree with it the first
 * time that happened.
 *
 * [onFinished] fires on the running-to-finished edge, which is the moment the session row on disk
 * actually changed and therefore the only moment a reload is worth anything. Work that finished
 * before this screen was ever opened stays finished and fires nothing: WorkManager keeps completed
 * WorkInfos around, and the initial state is only ever compared against a transition.
 */
@Composable
private fun workInFlight(uniqueWorkName: String, onFinished: () -> Unit): Boolean {
    val context = LocalContext.current
    val flow = remember(uniqueWorkName) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueWorkName)
    }
    val infos by flow.collectAsState(initial = emptyList())
    val inFlight = infos.any { !it.state.isFinished }
    val finishedCallback by rememberUpdatedState(onFinished)
    var wasInFlight by remember(uniqueWorkName) { mutableStateOf(false) }
    LaunchedEffect(inFlight) {
        if (inFlight) {
            wasInFlight = true
        } else if (wasInFlight) {
            wasInFlight = false
            finishedCallback()
        }
    }
    return inFlight
}

@Composable
private fun SessionCard(
    session: SessionEntity,
    onRetry: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onRetryFinished: () -> Unit,
    onNoteSaved: () -> Unit,
) {
    val context = LocalContext.current
    val type = LocalReadoutType.current
    var editingNote by remember(session.sessionId) { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US) }
    val summary = remember(session.analysisSummaryJson) {
        session.analysisSummaryJson?.let { analysisSummaryFromJson(it) }
    }
    // Falls back to the same on-device estimate the trip report itself shows when the server
    // never finished analysing this drive (never reached it at all, or died mid-session, both
    // confirmed real: the analysis JSON stays null forever in that case, not just "pending"). The
    // report already has an honest "server preferred, on-device as fallback" chain for exactly
    // this; the logbook card had never inherited it, so a drive with a perfectly good real number
    // sitting in Room showed a bare "--" here even after the report itself displayed it
    // correctly. Only computed when actually needed: a card with a real server MPG already never
    // touches Room again for this.
    val deviceFallback by produceState<TripSummary?>(initialValue = null, session.sessionId, summary) {
        value = if (summary?.overallMpg == null) computeTripSummary(context, session.sessionId) else null
    }
    val displayMpg = summary?.overallMpg ?: deviceFallback?.overallMpg

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
                        append(vehicleLabel(session.vehicleProfile))
                        durationMin?.let { append("  /  "); append("%.0f min".format(it)) }
                        // Only when it is not the ordinary case. "completed" was on every card,
                        // which is rule 14 applied at line level: a field that reads the same
                        // after every drive is wallpaper, and dropping it is what makes room for
                        // the vehicle without growing the card. An interrupted drive is a real
                        // result and still says so.
                        if (!session.completionStatus.equals("COMPLETED", ignoreCase = true)) {
                            append("  /  ")
                            append(session.completionStatus.lowercase())
                        }
                    },
                    style = type.unit,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // MPG in a fixed right-hand column: this is the value the list exists to compare.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    displayMpg?.let { "%.1f".format(it) } ?: "--",
                    style = type.medium,
                    // Same colour whether the figure came from the server or the on-device
                    // fallback: a stronger tint on one over the other would read as "trust this
                    // number more", which isn't true, they're just two sources for the same
                    // question. The report screen's caption text is where that distinction
                    // belongs, not colour on a scannable list.
                    color = if (displayMpg != null) AccentMixture else Slate,
                    maxLines = 1,
                )
                Text("MPG", style = type.label, color = Ash)
            }
        }

        // The note is the whole reason SessionEntity.notes exists: "same route, different result"
        // is only answerable if something recorded what was different. Kept to two lines and set
        // in Mist so it reads as the driver's own annotation, below the machine-written figures
        // above it but above the pipeline chips, which are about the app rather than the drive.
        //
        // Editable from here as well as at Stop, because the logbook is where a drive from days
        // ago gets looked at again and it is where "oh, that was the one with the new tyres"
        // actually occurs to someone. Collapsed behind a tap by default: the list's whole job is
        // to be scanned down a column of MPG figures, and a text field on every card would
        // triple every row's height for an interaction that happens once in twenty views.
        val note = session.notes?.takeIf { it.isNotBlank() }
        Spacer(Modifier.height(Space.sm))
        if (editingNote) {
            DriveNoteEditor(
                sessionId = session.sessionId,
                initialNote = session.notes.orEmpty(),
                onSaved = { editingNote = false; onNoteSaved() },
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                if (note != null) {
                    Text(
                        note,
                        style = type.unit,
                        color = Mist,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                SecondaryAction(
                    text = if (note != null) "Edit note" else "Add note",
                    onClick = { editingNote = true },
                    contentColor = Ash,
                    minHeight = Space.compactTarget,
                )
            }
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

        // At most one retry control, because at most one thing can be owed. The two states are
        // mutually exclusive by their own conditions: an upload that has not succeeded is the
        // only thing worth asking for until it does, and only once it has does an analysis that
        // never landed become the outstanding item. So the analysis control replaces the upload
        // one in that slot rather than sitting beside it, and the card's height does not change
        // between the two.
        if (session.backfillStatus != "SUCCESS") {
            val retrying = workInFlight(
                BackfillRetryWorker.retryWorkName(session.sessionId),
                onFinished = onRetryFinished,
            )
            Spacer(Modifier.height(Space.md))
            SecondaryAction(
                text = if (retrying) "Uploading..." else "Retry upload",
                onClick = onRetry,
                busy = retrying,
                contentColor = Mist,
                minHeight = Space.compactTarget,
            )
        } else if (session.analysisStatus != "DONE") {
            val analyzing = workInFlight(
                BackfillRetryWorker.analysisRetryWorkName(session.sessionId),
                onFinished = onRetryFinished,
            )
            Spacer(Modifier.height(Space.md))
            SecondaryAction(
                text = if (analyzing) "Analyzing..." else "Retry analysis",
                onClick = onRetryAnalysis,
                busy = analyzing,
                contentColor = Mist,
                minHeight = Space.compactTarget,
            )
        }
    }
}
