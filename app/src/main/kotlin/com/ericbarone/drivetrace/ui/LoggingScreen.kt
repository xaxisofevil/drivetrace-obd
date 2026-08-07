package com.ericbarone.drivetrace.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ericbarone.drivetrace.data.AdapterHealth
import com.ericbarone.drivetrace.data.DtcReport
import com.ericbarone.drivetrace.data.computeAdapterHealth
import com.ericbarone.drivetrace.data.describeDtc
import com.ericbarone.drivetrace.data.isSuspectedFramingArtifact
import com.ericbarone.drivetrace.data.readSessionDtcs
import com.ericbarone.drivetrace.export.CsvExporter
import com.ericbarone.drivetrace.export.TripSummary
import com.ericbarone.drivetrace.export.computeTripSummary
import com.ericbarone.drivetrace.service.ConnectionState
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.service.TriState
import com.ericbarone.drivetrace.ui.components.ActionBar
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.ConsoleLine
import com.ericbarone.drivetrace.ui.components.DataRow
import com.ericbarone.drivetrace.ui.components.Glyph
import com.ericbarone.drivetrace.ui.components.GlyphMark
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.HeroReadout
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.MetricTile
import com.ericbarone.drivetrace.ui.components.NoteField
import com.ericbarone.drivetrace.ui.components.PrimaryAction
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
import com.ericbarone.drivetrace.ui.components.StatusBand
import com.ericbarone.drivetrace.ui.components.StatusChip
import com.ericbarone.drivetrace.ui.components.StatusDot
import com.ericbarone.drivetrace.ui.components.StatusRow
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.AccentMotion
import com.ericbarone.drivetrace.ui.theme.AccentThermal
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.Slate
import com.ericbarone.drivetrace.ui.theme.Space
import com.ericbarone.drivetrace.ui.theme.StatusCaution
import com.ericbarone.drivetrace.ui.theme.StatusFault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Two genuinely different jobs share this screen, so they get two genuinely different layouts
 * rather than one column of rows that changes length.
 *
 *  - LIVE: read from a phone mount, in motion, often at a glance. One hero number, a band of
 *    subordinate tiles, one alert slot, one action. See docs/DESIGN_SYSTEM.md.
 *  - COMPLETE: read stationary, with attention. A trip report: headline result, supporting
 *    figures, then the pipeline's own state and the methodology caveats underneath.
 */
@Composable
fun LoggingScreen(status: LoggingUiState, onStop: (String) -> Unit, onNewSession: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showStopConfirm by remember { mutableStateOf(false) }
    var stopNote by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var exporting by remember { mutableStateOf(false) }
    var tripSummary by remember { mutableStateOf<TripSummary?>(null) }
    var adapterHealth by remember { mutableStateOf<AdapterHealth?>(null) }
    var dtcs by remember { mutableStateOf<DtcReport?>(null) }

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
            // Both read local Room only, so the whole trip report still fills in with the server
            // unreachable. Adapter health first: it is the cheap one, and it is the figure that
            // explains a thin or missing MPG number if the drive went badly.
            adapterHealth = computeAdapterHealth(context, id)
            dtcs = readSessionDtcs(context, id)
            tripSummary = computeTripSummary(context, id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
    ) {
        HeaderBar(
            title = if (sessionComplete) "Session Complete" else "Logging",
            subtitle = if (sessionComplete) "trip report" else null,
            modifier = Modifier.padding(horizontal = Space.gutter),
            trailing = {
                if (!sessionComplete) {
                    ConnectionPill(status.connectionState)
                }
            },
        )

        // The post-drive report (server analysis, flags, on-device estimate) can easily push past
        // screen height, especially with several anomaly flags, so the body scrolls. The actions
        // now live in a pinned ActionBar below this scroll area rather than at the end of it, so
        // "New session" and "Export" can never become unreachable regardless of report length.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.section),
        ) {
            Spacer(Modifier.height(Space.xs))
            if (sessionComplete) {
                CompleteBody(status, tripSummary, adapterHealth, dtcs)
            } else {
                LiveBody(status, elapsedSeconds, lastSampleAgeSeconds)
            }
            Spacer(Modifier.height(Space.sm))
        }

        ActionBar {
            if (!sessionComplete) {
                PrimaryAction(
                    text = "Stop logging",
                    onClick = { showStopConfirm = true },
                    accent = StatusFault,
                )
            } else {
                PrimaryAction(
                    text = if (exporting) "Exporting..." else "Export CSV",
                    enabled = !exporting,
                    onClick = {
                        val sessionId = status.sessionId ?: return@PrimaryAction
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
                )
                SecondaryAction(
                    text = "New session",
                    onClick = onNewSession,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = com.ericbarone.drivetrace.ui.theme.Panel,
            titleContentColor = Chalk,
            textContentColor = Mist,
            title = { Text("Stop logging?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                // The note lives here rather than on its own screen because Stop is the only
                // moment the drive is still in the driver's head. "Cold start, highway, 93
                // octane" is exactly the context that makes two drives comparable later, and
                // nobody goes back to add it afterwards.
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Text("This ends the current drive session.")
                    SectionLabel("Note (optional)")
                    NoteField(
                        value = stopNote,
                        onValueChange = { stopNote = it },
                        placeholder = "Cold start, highway, 93 octane",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStopConfirm = false; onStop(stopNote.trim()) }) {
                    Text("STOP", style = LocalReadoutType.current.label, color = StatusFault)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("CANCEL", style = LocalReadoutType.current.label, color = Mist)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Live
// ---------------------------------------------------------------------------

/**
 * Live layout. The hierarchy is the point: elapsed time is the one number worth a glance from a
 * mount, so it gets the hero slot at 64sp; sample/fix counts are confirmation the pipeline is
 * alive, so they get tiles at roughly a third the weight; and the engine-detected check gets the
 * screen's single alert slot, because it is the only condition here that means "stop and fix
 * something before you waste the whole drive".
 */
@Composable
private fun ColumnScope.LiveBody(
    status: LoggingUiState,
    elapsedSeconds: Long,
    lastSampleAgeSeconds: Long?,
) {
    val streaming = status.connectionState == ConnectionState.LOGGING

    HeroReadout(
        label = "Session elapsed",
        value = formatDuration(elapsedSeconds),
        accent = AccentMotion,
        caption = if (streaming) "recording" else status.connectionState.name.lowercase(),
    )

    // Threshold styling only. Nothing here changes what is polled or logged; it turns a number
    // that is already on screen into something whose severity is readable without arithmetic.
    val sampleTone = when {
        lastSampleAgeSeconds == null -> Tone.UNKNOWN
        lastSampleAgeSeconds <= 5 -> Tone.NEUTRAL
        lastSampleAgeSeconds <= 15 -> Tone.CAUTION
        else -> Tone.FAULT
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Capture")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
            MetricTile(
                label = "Samples",
                value = status.measurementCount.toString(),
                accent = AccentMixture,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "GPS fixes",
                value = status.locationCount.toString(),
                accent = AccentThermal,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Last",
                value = lastSampleAgeSeconds?.toString() ?: "--",
                unit = if (lastSampleAgeSeconds != null) "s" else null,
                tone = sampleTone,
                modifier = Modifier.weight(1f),
            )
        }
        if (status.reconnectCount > 0) {
            InstrumentPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = StatusCaution,
                contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm),
            ) {
                DataRow(
                    label = "Bluetooth reconnects",
                    value = status.reconnectCount.toString(),
                    valueColor = StatusCaution,
                    leadingGlyph = Glyph.BANG,
                    glyphColor = StatusCaution,
                )
            }
        }
    }

    // The screen's one alert slot. Reserved for the engine-detected check: everything else on
    // this screen is information, this is the only thing that is a decision.
    when (status.engineDetected) {
        TriState.YES -> StatusBand(
            tone = Tone.LIVE,
            title = "Engine detected, real data",
        )
        TriState.PENDING -> StatusBand(
            tone = Tone.UNKNOWN,
            title = "Checking for real vehicle data",
        )
        TriState.NO -> StatusBand(
            tone = Tone.FAULT,
            title = "No real data from the vehicle",
            body = "A response arriving isn't proof the car's awake, this adapter can fabricate " +
                "placeholder values instead of erroring. Check the ignition and adapter connection.",
        )
    }

    if (status.statusMessage.isNotBlank()) {
        ConsoleLine(status.statusMessage)
    }
}

/** Connection state as a header badge, so it is always visible without spending a content row. */
@Composable
private fun ConnectionPill(state: ConnectionState) {
    val tone = when (state) {
        ConnectionState.LOGGING, ConnectionState.READY -> Tone.LIVE
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.RECONNECTING -> Tone.CAUTION
        ConnectionState.FAILED -> Tone.FAULT
        ConnectionState.DISCONNECTED -> Tone.UNKNOWN
    }
    val pulsing = state == ConnectionState.LOGGING ||
        state == ConnectionState.CONNECTING ||
        state == ConnectionState.INITIALIZING ||
        state == ConnectionState.RECONNECTING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.padding(start = Space.sm),
    ) {
        StatusDot(tone, pulsing = pulsing)
        Text(state.name, style = LocalReadoutType.current.label, color = tone.color)
    }
}

// ---------------------------------------------------------------------------
// Session complete
// ---------------------------------------------------------------------------

/**
 * Trip report. Read stationary, so density is affordable, but the ranking still matters: the
 * headline is the MPG number the whole project exists to move, everything below it is evidence
 * or provenance for that number.
 */
@Composable
private fun ColumnScope.CompleteBody(
    status: LoggingUiState,
    tripSummary: TripSummary?,
    adapterHealth: AdapterHealth?,
    dtcs: DtcReport?,
) {
    val analysis = status.analysisSummary
    val serverMpg = analysis?.overallMpg
    val deviceMpg = tripSummary?.overallMpg

    // Prefer the server figure: it gates on stoichiometric operation, the on-device one doesn't.
    val heroMpg = serverMpg ?: deviceMpg
    val heroSource = when {
        serverMpg != null -> "server analysis, stoichiometric-gated"
        deviceMpg != null -> "on-device estimate, ungated"
        tripSummary == null -> "calculating..."
        else -> "no fuel data captured"
    }

    HeroReadout(
        label = "Trip economy",
        value = heroMpg?.let { "%.1f".format(it) } ?: "--",
        unit = "MPG",
        accent = if (heroMpg != null) AccentMixture else Slate,
        caption = heroSource,
    )

    val distanceKm = analysis?.distanceGpsKm ?: tripSummary?.distanceKm
    if (distanceKm != null || analysis?.idleFractionPct != null || analysis?.warmupMinutes != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Drive profile")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
                if (distanceKm != null) {
                    MetricTile(
                        label = "Distance",
                        value = "%.2f".format(distanceKm),
                        unit = "km",
                        accent = AccentMotion,
                        modifier = Modifier.weight(1f),
                    )
                }
                analysis?.idleFractionPct?.let {
                    MetricTile(
                        label = "Idle",
                        value = "%.1f".format(it),
                        unit = "%",
                        accent = AccentMixture,
                        modifier = Modifier.weight(1f),
                    )
                }
                analysis?.warmupMinutes?.let {
                    MetricTile(
                        label = "Warm-up",
                        value = "%.1f".format(it),
                        unit = "min",
                        accent = AccentThermal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // Above Pipeline on purpose: this is the vehicle talking, everything below is the app talking.
    if (dtcs != null && dtcs.read) {
        DiagnosticCodesSection(dtcs)
    }

    // Pipeline provenance. Grouped into one panel because "did this drive make it off the phone"
    // is a single question with two stages, not two unrelated status lines.
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Pipeline")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            StatusRow(
                label = "Server upload (verified complete)",
                state = triStateWord(status.backfillStatus, pending = "uploading"),
                tone = toneOf(status.backfillStatus),
                detail = status.backfillMessage.takeIf { it.isNotBlank() },
                pulsing = status.backfillStatus == TriState.PENDING,
            )
            if (status.backfillStatus == TriState.YES) {
                StatusRow(
                    label = "PC analysis",
                    state = triStateWord(status.analysisStatus, pending = "running"),
                    tone = toneOf(status.analysisStatus),
                    detail = when (status.analysisStatus) {
                        TriState.PENDING -> "Waiting on the PC to analyze this drive..."
                        TriState.NO -> status.analysisMessage.takeIf { it.isNotBlank() }
                        TriState.YES -> null
                    },
                    pulsing = status.analysisStatus == TriState.PENDING,
                )
            }
        }
    }

    if (adapterHealth != null) {
        AdapterHealthSection(adapterHealth)
    }

    if (analysis != null && (analysis.brakingEventCount ?: 0) > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Braking")
            InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
                DataRow("Braking events", analysis.brakingEventCount.toString())
                analysis.brakingFuelEquivMl?.let {
                    DataRow("Est. fuel to brake heat", "%.0f mL".format(it), valueColor = AccentMixture)
                }
                analysis.brakingEventsWithoutCoast?.let {
                    if (it > 0) {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "$it of ${analysis.brakingEventCount} had no coast phase first, " +
                                "speed was carried right up to the brakes.",
                            style = LocalReadoutType.current.unit,
                            color = StatusCaution,
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Caption(
                    "Estimate only: braking is inferred from deceleration rate, not measured, and " +
                        "the mL figure assumes a fixed vehicle mass/engine efficiency. See " +
                        "analysis_report.md for details.",
                )
            }
        }
    }

    if (analysis != null && analysis.flags.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Anomaly flags")
            for (flag in analysis.flags) {
                InstrumentPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = StatusCaution,
                    contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        GlyphMark(Glyph.BANG, StatusCaution, sizeDp = 14, modifier = Modifier.padding(top = 2.dp))
                        Text(flag, style = LocalReadoutType.current.unit, color = Mist)
                    }
                }
            }
        }
    }

    // The on-device figure stays visible even when the server number is the hero, because the
    // two disagreeing is itself information (and the on-device one is the only figure that
    // exists at all when the server was never reachable).
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("On-device cross-check")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            when {
                tripSummary == null ->
                    DataRow("Trip MPG (on-device est.)", "calculating...", valueColor = Slate)
                tripSummary.overallMpg == null ->
                    DataRow("Trip MPG (on-device est.)", "n/a (no fuel data)", valueColor = Slate)
                else -> {
                    tripSummary.distanceKm?.let {
                        DataRow("Distance (GPS, on-device)", "%.2f km".format(it))
                    }
                    DataRow(
                        "Trip MPG (on-device est.)",
                        "%.1f".format(tripSummary.overallMpg),
                        valueColor = AccentMixture,
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            Caption(
                "On-device estimate is rougher: total distance / total fuel burned, not gated on " +
                    "stoichiometric operation like the PC analysis above.",
            )
        }
    }

    if (status.statusMessage.isNotBlank()) {
        ConsoleLine(status.statusMessage, color = Ash)
    }
}

/**
 * Stored trouble codes, read once at session start and until now never shown anywhere. Each code
 * gets its plain-English meaning from [describeDtc]; a code outside the generic table still gets
 * a structural decode rather than a blank line, and says so.
 *
 * On the trip report rather than the Setup screen because a DTC is per-session data read from a
 * session that does not exist yet when Setup is on screen. Setup would have to either show the
 * *previous* drive's codes (misleading: the point of a code is that it is current) or start a
 * connection of its own just to populate a panel.
 */
@Composable
private fun ColumnScope.DiagnosticCodesSection(dtcs: DtcReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Diagnostic codes")

        if (dtcs.isEmpty) {
            // Worth a row of its own. "The ECU reports nothing stored" is a real result, and it
            // is only trustworthy because DtcReport.read confirms the read actually came back.
            InstrumentPanel(modifier = Modifier.fillMaxWidth(), accent = Tone.LIVE.color) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    GlyphMark(Tone.LIVE.glyph, Tone.LIVE.color, sizeDp = 14)
                    Text(
                        "No stored trouble codes",
                        style = LocalReadoutType.current.unit,
                        color = Tone.LIVE.color,
                    )
                }
            }
            return@Column
        }

        // Current codes are the lit check-engine lamp, so they are the only fault-toned group.
        // Pending has not been confirmed across enough drive cycles to light anything, and
        // permanent is a code already cleared that the ECU is holding until its own monitors
        // pass; both are real information but neither is "stop driving".
        DtcGroup("Current", dtcs.current, Tone.FAULT)
        DtcGroup("Pending", dtcs.pending, Tone.CAUTION)
        DtcGroup("Permanent", dtcs.permanent, Tone.CAUTION)

        Caption(
            "Meanings come from the generic SAE code table built into this app. Manufacturer-" +
                "specific codes (P1xxx and some P3xxx) are decoded structurally only; check them " +
                "against the vehicle's own service data before acting on one. This app reads " +
                "codes and never clears them.",
        )
    }
}

@Composable
private fun DtcGroup(groupLabel: String, codes: List<String>, tone: Tone) {
    for (code in codes) {
        val described = describeDtc(code)
        // A code the library's unverified decode is known to fabricate gets demoted out of the
        // fault channel entirely: showing "confirmed fault" next to a parser artifact is worse
        // than showing nothing, and this app cannot yet tell the two apart. See KNOWN_ISSUES.md.
        val suspect = isSuspectedFramingArtifact(described.code)
        val codeTone = if (suspect) Tone.UNKNOWN else tone
        InstrumentPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = codeTone.color,
            contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                GlyphMark(codeTone.glyph, codeTone.color, sizeDp = 14)
                // The code leads and the meaning follows, not the other way round: the code is
                // what gets typed into a search, quoted to a mechanic, or matched against a
                // service bulletin, and it stays the same string in every one of those places.
                Text(
                    described.code,
                    style = LocalReadoutType.current.small,
                    color = codeTone.color,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(text = groupLabel, tone = codeTone)
            }
            Spacer(Modifier.height(Space.xs))
            if (suspect) {
                Text(
                    "Probably not a real code: this matches the response-framing artifact the " +
                        "library's unverified DTC decode is known to produce for this request. " +
                        "See KNOWN_ISSUES.md.",
                    style = LocalReadoutType.current.unit,
                    color = Ash,
                )
            } else {
                Text(
                    described.meaning,
                    style = LocalReadoutType.current.unit,
                    color = if (described.known) Mist else Ash,
                )
            }
        }
    }
}

/**
 * Adapter health, built entirely from PID_NO_DATA / PID_COOLDOWN events the scheduler already
 * logs every session (see data/SessionDiagnostics.kt). Without this the user has no way to tell a
 * clean drive from one where a cheap ELM327 clone dropped a third of its reads: the samples
 * counter only ever counts what succeeded.
 *
 * Sits below Pipeline on purpose. It describes the capture rig, not the car and not the drive, so
 * it ranks under both the vehicle's own numbers and "did this drive make it off the phone".
 */
@Composable
private fun ColumnScope.AdapterHealthSection(health: AdapterHealth) {
    // Distinct PIDs, not raw failures, drives the tone: one PID failing repeatedly is an
    // unsupported PID cycling through its 30s cooldown, which is expected and nearly free (see
    // KNOWN_ISSUES.md). Several different PIDs failing is the adapter or the link.
    val tone = when {
        health.isClean -> Tone.LIVE
        health.distinctPidsDropped <= 2 -> Tone.CAUTION
        else -> Tone.FAULT
    }
    val headline = if (health.isClean) {
        "Every read answered"
    } else {
        "Adapter dropped ${health.distinctPidsDropped} " +
            "PID${if (health.distinctPidsDropped == 1) "" else "s"} this drive"
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Adapter health")
        InstrumentPanel(modifier = Modifier.fillMaxWidth(), accent = tone.color) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                GlyphMark(tone.glyph, tone.color, sizeDp = 14)
                Text(headline, style = LocalReadoutType.current.unit, color = tone.color)
            }
            if (!health.isClean) {
                Spacer(Modifier.height(Space.sm))
                DataRow("Failed reads", health.failedReads.toString())
                DataRow("Cooldown pauses", health.cooldowns.toString())
                for ((pidTag, count) in health.worstOffenders) {
                    DataRow(pidTag, count.toString(), valueColor = Mist)
                }
                Spacer(Modifier.height(Space.sm))
                Caption(
                    "A PID this ECU genuinely doesn't support fails every attempt too, so one PID " +
                        "with a high count is more likely unsupported than a bad adapter. Several " +
                        "different PIDs failing is the adapter or the Bluetooth link.",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

private fun toneOf(state: TriState): Tone = when (state) {
    TriState.PENDING -> Tone.UNKNOWN
    TriState.YES -> Tone.LIVE
    TriState.NO -> Tone.FAULT
}

private fun triStateWord(state: TriState, pending: String): String = when (state) {
    TriState.PENDING -> pending
    TriState.YES -> "complete"
    TriState.NO -> "failed"
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
