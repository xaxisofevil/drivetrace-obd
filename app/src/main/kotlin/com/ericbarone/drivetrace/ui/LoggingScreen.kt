package com.ericbarone.drivetrace.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.Color
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
import com.ericbarone.drivetrace.streaming.AnalysisSummary
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

    // Two layouts, two scroll positions. They used to share one rememberScrollState, which meant
    // the trip report opened at whatever pixel offset the live screen happened to have been left
    // at, with no affordance anywhere on the screen saying it was scrolled. That is how the report
    // ends up under the header with its hero above the fold; see docs/KNOWN_ISSUES.md's
    // "hero readout looked blank" entry for the real screenshot that led here. A ScrollState
    // anchors on a pixel offset, not on content, and these two layouts share neither their
    // content nor their length, so an offset carried from one to the other never means anything.
    val liveScroll = rememberScrollState()
    val reportScroll = rememberScrollState()
    // Frozen at the moment the session ended rather than ticking off `nowMs`, which would keep
    // counting while the report sits on screen. LoggingUiState carries no end timestamp (the
    // service writes one onto the row, not into the status bus), so the report captures the
    // elapsed value as it crosses into the complete state and holds it.
    var completedDurationSeconds by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sessionComplete, status.sessionId) {
        completedDurationSeconds = if (sessionComplete && status.startedAtMs != null) {
            (System.currentTimeMillis() - status.startedAtMs) / 1000
        } else {
            null
        }
    }

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
            .systemBarsPadding()
            // The trip report now holds a text field partway down a scroll. Without this the IME
            // covers it and the pinned ActionBar both.
            .imePadding(),
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
                .verticalScroll(if (sessionComplete) reportScroll else liveScroll)
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.section),
        ) {
            Spacer(Modifier.height(Space.xs))
            if (sessionComplete) {
                CompleteBody(status, tripSummary, adapterHealth, dtcs, completedDurationSeconds)
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
 * Trip report, ordered by what someone actually wants to know thirty seconds after parking.
 *
 * The order this replaced was hero-MPG, drive profile, codes, pipeline, adapter health, braking,
 * flags, cross-check: seven equal-weight labelled sections, four of which are the app talking
 * about itself. A real screenshot of it, from a drive that captured nothing and failed to upload,
 * spent its first two screens on a "--" hero, a 0.00 km tile, a red panel about a socket timeout,
 * and a green panel about the adapter, and never once said the thing that was actually true:
 * this drive recorded nothing usable. The reader had to assemble that from three scattered pieces
 * of evidence. The ranking below is the fix.
 *
 *  1. **Did this drive record anything.** Existential, and it outranks every figure on the screen
 *     because it decides whether the figures mean anything. When the answer is bad it takes the
 *     screen's one alert band. When it is fine there is no band at all: the band's power comes
 *     entirely from being the only one, same rule the LIVE layout already lives by.
 *  2. **The best number this drive actually produced** (see [heroFigure]). Not "MPG or a dash".
 *  3. **What the car said** — stored codes, but only when there are some.
 *  4. **How the drive went** — the profile tiles, then anomaly flags and braking.
 *  5. **What you want to remember about it** — the note.
 *  6. **How much to trust all of the above, and whether the app owes you anything** — one
 *     subordinate `DataRow` block at the bottom. Upload, analysis, adapter health, the clean-code
 *     confirmation and the on-device cross-check used to be four separate full-weight sections;
 *     they are all the same question and none of them is about the car or the drive.
 */
@Composable
private fun ColumnScope.CompleteBody(
    status: LoggingUiState,
    tripSummary: TripSummary?,
    adapterHealth: AdapterHealth?,
    dtcs: DtcReport?,
    durationSeconds: Long?,
) {
    val analysis = status.analysisSummary
    val serverMpg = analysis?.overallMpg
    val deviceMpg = tripSummary?.overallMpg
    // Prefer the server figure: it gates on stoichiometric operation, the on-device one doesn't.
    val mpg = serverMpg ?: deviceMpg
    val distanceKm = analysis?.distanceGpsKm ?: tripSummary?.distanceKm
    // Nothing is knowable until the on-device pass has run; before then the honest answer to
    // every question below is "still working it out", not "it failed".
    val settled = tripSummary != null
    val drove = (distanceKm ?: 0.0) >= 0.05

    CaptureVerdictBand(settled = settled, haveMpg = mpg != null, drove = drove, health = adapterHealth)

    val hero = heroFigure(
        mpg = mpg,
        fromServer = serverMpg != null,
        distanceKm = distanceKm.takeIf { drove },
        durationSeconds = durationSeconds,
        settled = settled,
    )
    HeroReadout(
        label = hero.label,
        value = hero.value,
        unit = hero.unit,
        accent = hero.accent,
        caption = hero.caption,
    )

    // The vehicle talking, and the highest-consequence thing this screen can carry, so it sits
    // directly under the hero. Only when there is something to say: a full accent-barred panel
    // reading "no stored trouble codes" after every single drive is the "everything is fine"
    // green wash ISA-101 rules out, it becomes wallpaper by the third drive, and it costs a whole
    // section of vertical space that pushes real content below the fold. The clean read is still
    // reported, as one line in the capture block at the bottom, because a confirmed clean read is
    // a real result and losing it entirely would be worse than over-showing it.
    if (dtcs != null && dtcs.read && !dtcs.isEmpty) {
        DiagnosticCodesSection(dtcs)
    }

    DriveProfileSection(
        analysis = analysis,
        distanceKm = distanceKm.takeIf { hero.kind != HeroKind.DISTANCE },
        durationSeconds = durationSeconds.takeIf { hero.kind != HeroKind.DURATION },
    )

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

    // Last item that is about the drive, first item the thumb reaches, and directly under the
    // evidence it is a reaction to: you annotate a drive after reading what happened on it, not
    // before. Above the capture block on purpose, because the driver's own words rank over the
    // app's account of its own plumbing, the same ranking the logbook card already uses.
    status.sessionId?.let { sessionId ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Drive note")
            DriveNoteEditor(sessionId = sessionId)
        }
    }

    CaptureAndDeliverySection(status, tripSummary, adapterHealth, dtcs)

    if (status.statusMessage.isNotBlank()) {
        ConsoleLine(status.statusMessage, color = Ash)
    }
}

// ---------------------------------------------------------------------------
// Session complete: the hero
// ---------------------------------------------------------------------------

private enum class HeroKind { MPG, DISTANCE, DURATION, NONE }

private data class HeroFigure(
    val kind: HeroKind,
    val label: String,
    val value: String,
    val unit: String?,
    val accent: Color,
    val caption: String,
)

/**
 * Which number owns the trip report.
 *
 * The old answer was "MPG, or a dash if there isn't one", which spends the largest thing on the
 * screen, 64sp of it, on the absence of data. A hero reading `--` is a hero with no content: it
 * occupies the slot, defeats the hierarchy it is supposed to create, and still leaves the reader
 * hunting for a number that does exist.
 *
 * So the hero is the best figure the drive actually produced, down a fixed chain: economy if
 * there is any, otherwise distance, otherwise session length, which exists for every session that
 * ever started. `--` is now unreachable in practice.
 *
 * The cost is that the hero changes identity between drives, and the design system leans on the
 * hero slot's position to identify what is in it (which is part of why MOTION is achromatic). The
 * trade is worth taking here and would not be on the LIVE screen: this one is read stationary
 * with attention, the readout already carries its own label and provenance caption, and the
 * accent still obeys the category contract, teal for economy and MOTION white for distance and
 * duration, so the hue never lies about which system the number came from. "You drove 12.4 km and
 * got no fuel data" is a result. "-- MPG" is a layout.
 *
 * **The two `--` branches used to paint themselves `Slate`,** which is the disabled-text grey and
 * sits at about 2.2:1 on `Ink`. At 64sp Light weight that is a hero which is technically drawn
 * and practically not there, and it is the one state where the screen most needs to be legible
 * about what it does not know. `Ash` (~5.4:1, the same grey the hero's own label already uses)
 * costs nothing, cannot be confused with a real reading because it is achromatic, and keeps rule
 * 13 honest end to end: the hero avoids `--` where it can, and where it cannot the `--` is
 * readable. Both greys are registered in `DaylightReadoutPalette`, so rule 11 still holds.
 */
private fun heroFigure(
    mpg: Double?,
    fromServer: Boolean,
    distanceKm: Double?,
    durationSeconds: Long?,
    settled: Boolean,
): HeroFigure = when {
    mpg != null -> HeroFigure(
        kind = HeroKind.MPG,
        label = "Trip economy",
        value = "%.1f".format(mpg),
        unit = "MPG",
        accent = AccentMixture,
        caption = if (fromServer) {
            "server analysis, stoichiometric-gated"
        } else {
            "on-device estimate, ungated"
        },
    )
    !settled -> HeroFigure(
        kind = HeroKind.NONE,
        label = "Trip economy",
        value = "--",
        unit = "MPG",
        accent = Ash,
        caption = "calculating...",
    )
    distanceKm != null -> HeroFigure(
        kind = HeroKind.DISTANCE,
        label = "Distance driven",
        value = "%.2f".format(distanceKm),
        unit = "km",
        accent = AccentMotion,
        caption = "no fuel data, so no economy figure for this drive",
    )
    durationSeconds != null -> HeroFigure(
        kind = HeroKind.DURATION,
        label = "Session length",
        value = formatDuration(durationSeconds),
        unit = null,
        accent = AccentMotion,
        caption = "the only figure this session produced",
    )
    else -> HeroFigure(
        kind = HeroKind.NONE,
        label = "Trip economy",
        value = "--",
        unit = "MPG",
        accent = Ash,
        caption = "no fuel data captured",
    )
}

/**
 * The trip report's one alert slot, and it answers the question that outranks every number on the
 * screen: is any of this worth reading.
 *
 * Nothing else on this screen is allowed to be a band, exactly as on the LIVE layout. A failed
 * upload deliberately does not qualify: it retries on its own, the logbook has a Retry control,
 * and nothing about the drive is lost, so it is plumbing and it belongs in the block at the
 * bottom with the rest of the plumbing. Losing the drive's data is not plumbing.
 *
 * The body names the likely cause rather than restating the symptom, and points at the one
 * section that has the detail. That connection, "no MPG *because* the adapter dropped reads", is
 * the whole job: the old layout had both facts on screen and left the reader to join them.
 */
@Composable
private fun ColumnScope.CaptureVerdictBand(
    settled: Boolean,
    haveMpg: Boolean,
    drove: Boolean,
    health: AdapterHealth?,
) {
    if (!settled || haveMpg) return

    val adapterClause = if (health != null && !health.isClean) {
        " The adapter dropped ${health.distinctPidsDropped} " +
            "PID${if (health.distinctPidsDropped == 1) "" else "s"} this drive, see Capture and " +
            "delivery below."
    } else {
        ""
    }

    if (drove) {
        StatusBand(
            tone = Tone.CAUTION,
            title = "No fuel data this drive",
            body = "Distance was recorded, but Mass Air Flow never answered, so this drive has no " +
                "economy figure and can't be compared against another one.$adapterClause",
        )
    } else {
        StatusBand(
            tone = Tone.FAULT,
            title = "Nothing usable recorded",
            body = "No distance and no fuel data came back, so there is nothing in this report to " +
                "compare against another drive.$adapterClause",
        )
    }
}

// ---------------------------------------------------------------------------
// Session complete: the drive
// ---------------------------------------------------------------------------

/**
 * The secondary band: everything about the drive that is not the hero, at roughly a third its
 * weight. Whichever figure the hero took is omitted here rather than repeated, so the screen
 * never shows the same number twice at two sizes.
 *
 * Session length is new. It was on the LIVE screen as the hero and then vanished the moment the
 * drive ended, which meant the report could not answer "how long was that" at all: on the real
 * screenshot, "35 min and 0.00 km" would have explained the whole session in one line and the
 * report simply did not have it.
 */
@Composable
private fun ColumnScope.DriveProfileSection(
    analysis: AnalysisSummary?,
    distanceKm: Double?,
    durationSeconds: Long?,
) {
    val tiles = buildList<@Composable RowScope.() -> Unit> {
        if (distanceKm != null) {
            add {
                MetricTile(
                    label = "Distance",
                    value = "%.2f".format(distanceKm),
                    unit = "km",
                    accent = AccentMotion,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (durationSeconds != null) {
            add {
                MetricTile(
                    label = "Duration",
                    value = formatDuration(durationSeconds),
                    accent = AccentMotion,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        analysis?.idleFractionPct?.let { idle ->
            add {
                MetricTile(
                    label = "Idle",
                    value = "%.1f".format(idle),
                    unit = "%",
                    accent = AccentMixture,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        analysis?.warmupMinutes?.let { warmup ->
            add {
                MetricTile(
                    label = "Warm-up",
                    value = "%.1f".format(warmup),
                    unit = "min",
                    accent = AccentThermal,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (tiles.isEmpty()) return

    // Four tiles across a phone leaves each one about 78dp, which is narrower than the word
    // "WARM-UP" at the label style's tracking. Wrapping at three, and splitting four into 2+2
    // rather than 3+1 so neither row looks like a leftover, keeps every tile legible without
    // introducing a new container type: these are still plain MetricTiles in Rows.
    val perRow = when {
        tiles.size <= 3 -> tiles.size
        tiles.size == 4 -> 2
        else -> 3
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Drive profile")
        for (rowTiles in tiles.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
                for (tile in rowTiles) tile()
                // Short final row keeps its tiles the same width as the rows above it rather than
                // stretching a lone tile across the screen and reading as a second hero.
                repeat(perRow - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session complete: the app talking about itself
// ---------------------------------------------------------------------------

/**
 * Everything that is about the capture rig and the pipeline rather than about the car or the
 * drive, in one subordinate block of `DataRow`-weight lines.
 *
 * This replaces four full sections: **Pipeline**, **Adapter health**, the clean half of
 * **Diagnostic codes**, and **On-device cross-check**. Each had its own `SectionLabel` and its own
 * panel frame, and together they were most of the report's scroll length while answering exactly
 * one question between them: how much should I trust what is above, and does the app still owe me
 * anything. Rule 6 already says Tier C data goes in a `DataRow` rather than a tile, and all of
 * this is Tier C by the document's own definition of the term.
 *
 * **Adapter health specifically.** It was an accent-barred panel under its own section label,
 * which on a clean drive meant a green tick at panel weight competing with the panels that carry
 * the drive's actual result. It is diagnostic meta-information about the rig; on a good drive it
 * is the least interesting true statement on the screen. Here it is one line, and it earns tone
 * colour and a glyph only when it is genuinely degraded, at which point the verdict band above
 * has already told the reader to come looking for it.
 *
 * **The cross-check gains from this rather than losing.** Its whole argument was that the server
 * and on-device figures disagreeing is itself information; sitting as adjacent `DataRow`s in the
 * same panel as the upload state makes that comparison easier to run, not harder, because the
 * provenance of each number is now next to the number.
 */
@Composable
private fun ColumnScope.CaptureAndDeliverySection(
    status: LoggingUiState,
    tripSummary: TripSummary?,
    health: AdapterHealth?,
    dtcs: DtcReport?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Capture and delivery")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            StatusRow(
                label = "Server upload (verified complete)",
                state = triStateWord(status.backfillStatus, pending = "uploading"),
                tone = toneOf(status.backfillStatus),
                // Never the raw status.backfillMessage: on the failure path it is the transport
                // exception, naming the server's hostname, public IP and port. See
                // ui/PipelineMessages.kt for why this is a whitelist rather than a scrubber.
                detail = uploadDetail(
                    uploaded = when (status.backfillStatus) {
                        TriState.YES -> true
                        TriState.NO -> false
                        TriState.PENDING -> null
                    },
                    rawMessage = status.backfillMessage,
                ),
                pulsing = status.backfillStatus == TriState.PENDING,
            )
            // Nesting rule unchanged: analysis cannot have an outcome until the upload landed.
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

            if (health != null) {
                val tone = when {
                    health.isClean -> Tone.LIVE
                    health.distinctPidsDropped <= 2 -> Tone.CAUTION
                    else -> Tone.FAULT
                }
                DataRow(
                    label = "Adapter reads",
                    value = if (health.isClean) {
                        "all answered"
                    } else {
                        "${health.distinctPidsDropped} " +
                            "PID${if (health.distinctPidsDropped == 1) "" else "s"} dropped"
                    },
                    valueColor = tone.color,
                    leadingGlyph = tone.glyph,
                    glyphColor = tone.color,
                )
                if (!health.isClean) {
                    DataRow("Failed reads", health.failedReads.toString(), valueColor = Mist)
                    DataRow("Cooldown pauses", health.cooldowns.toString(), valueColor = Mist)
                    for ((pidTag, count) in health.worstOffenders) {
                        DataRow(pidTag, count.toString(), valueColor = Mist)
                    }
                    Caption(
                        "A PID this ECU genuinely doesn't support fails every attempt too, so one " +
                            "PID with a high count is more likely unsupported than a bad adapter. " +
                            "Several different PIDs failing is the adapter or the Bluetooth link.",
                    )
                }
            }

            // The clean-read confirmation, demoted out of its own section. Achromatic rather than
            // green: "nothing stored" is the normal state and normal state does not get a colour.
            if (dtcs != null && dtcs.read && dtcs.isEmpty) {
                DataRow("Stored trouble codes", "none", valueColor = Chalk)
            }

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
                    "stoichiometric operation like the PC analysis is.",
            )
        }
    }
}

/**
 * Stored trouble codes, read once at session start. Each code gets its plain-English meaning from
 * [describeDtc]; a code outside the generic table still gets a structural decode rather than a
 * blank line, and says so.
 *
 * On the trip report rather than the Setup screen because a DTC is per-session data read from a
 * session that does not exist yet when Setup is on screen. Setup would have to either show the
 * *previous* drive's codes (misleading: the point of a code is that it is current) or start a
 * connection of its own just to populate a panel.
 *
 * Only rendered when there are codes. The clean case, which is nearly every drive, moved to a
 * single `DataRow` in the capture block; see [CaptureAndDeliverySection] for why. Codes present is
 * the highest-consequence statement this screen can make, so when it does render it renders
 * immediately under the hero.
 */
@Composable
private fun ColumnScope.DiagnosticCodesSection(dtcs: DtcReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Diagnostic codes")

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
