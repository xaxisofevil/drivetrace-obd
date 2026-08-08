package com.ericbarone.drivetrace.ui

import android.content.Context
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ericbarone.drivetrace.data.readSessionDtcs
import com.ericbarone.drivetrace.export.CaptureItem
import com.ericbarone.drivetrace.export.CsvExporter
import com.ericbarone.drivetrace.export.DTC_CAPTION
import com.ericbarone.drivetrace.export.PdfTripReportExporter
import com.ericbarone.drivetrace.export.ReportBraking
import com.ericbarone.drivetrace.export.ReportCategory
import com.ericbarone.drivetrace.export.ReportCode
import com.ericbarone.drivetrace.export.ReportEmphasis
import com.ericbarone.drivetrace.export.ReportTile
import com.ericbarone.drivetrace.export.ReportTone
import com.ericbarone.drivetrace.export.TripReport
import com.ericbarone.drivetrace.export.TripSummary
import com.ericbarone.drivetrace.export.buildTripReport
import com.ericbarone.drivetrace.export.computeTripSummary
import com.ericbarone.drivetrace.export.formatDuration
import com.ericbarone.drivetrace.obd.MeasurementSample
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
import com.ericbarone.drivetrace.ui.theme.Space
import com.ericbarone.drivetrace.ui.theme.StatusCaution
import com.ericbarone.drivetrace.ui.theme.StatusFault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    var exportingCsv by remember { mutableStateOf(false) }
    var exportingPdf by remember { mutableStateOf(false) }
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

    // Built once, here, and handed to both renderers. `CompleteBody` draws it on the phone and
    // `PdfTripReportExporter` draws it on a printed page, so the exported file is literally the
    // report that was on screen rather than a second reading of the same inputs. See
    // export/TripReport.kt for why the model exists at all and what happens when it drifts.
    // Keyed on its inputs rather than rebuilt per recomposition: the one-second `nowMs` tick keeps
    // running while the report is on screen, and the export lambda below closes over this object,
    // so a stable identity is worth the `remember`.
    val report = remember(status, tripSummary, adapterHealth, dtcs, completedDurationSeconds) {
        if (sessionComplete) {
            buildTripReport(status, tripSummary, adapterHealth, dtcs, completedDurationSeconds)
        } else {
            null
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
            if (report != null) {
                CompleteBody(report, status.sessionId)
            } else {
                LiveBody(status, elapsedSeconds, lastSampleAgeSeconds, nowMs)
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
                    text = if (exportingCsv) "Exporting..." else "Export CSV",
                    enabled = !exportingCsv,
                    onClick = {
                        val sessionId = status.sessionId ?: return@PrimaryAction
                        exportingCsv = true
                        scope.launch {
                            val zip = CsvExporter(context).export(sessionId)
                            exportingCsv = false
                            share(context, zip, "application/zip", "Export drive data")
                        }
                    },
                )
                // The PDF sits beside "New session" rather than taking a second 56dp primary row.
                // Rule 1 allows one hero and rule 8 pins one primary action; the CSV bundle keeps
                // it because it is the drive's data of record, the thing the analysis script and
                // the server both consume. The PDF is the *readable* copy of the screen above it,
                // which is a secondary-rank action however useful it is, and pairing it with the
                // other non-primary control keeps the bar exactly as tall as it already was.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    SecondaryAction(
                        text = "Report PDF",
                        busy = exportingPdf,
                        onClick = {
                            val sessionId = status.sessionId ?: return@SecondaryAction
                            val current = report ?: return@SecondaryAction
                            exportingPdf = true
                            scope.launch {
                                val pdf = PdfTripReportExporter(context).export(sessionId, current)
                                exportingPdf = false
                                share(context, pdf, "application/pdf", "Share trip report")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryAction(
                        text = "New session",
                        onClick = onNewSession,
                        modifier = Modifier.weight(1f),
                    )
                }
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

/** Canonical names this layout addresses by hand; the rest come from the table in PidDisplay.kt. */
private const val CANONICAL_RPM = "Engine RPM"
private const val CANONICAL_SPEED = "Vehicle Speed"

/** Short-term trim, whichever bank this vehicle has. Bank 1 wins when both are present, because
 *  [LIVE_PID_DISPLAY] lists it first and the search below preserves that order. */
private val SHORT_TERM_TRIMS = setOf(
    "Short Term Fuel Trim Bank 1",
    "Short Term Fuel Trim Bank 2",
)

/**
 * The category grid is always three tiles across, even for a category holding one or two.
 *
 * The drive report's tile rows widen to fill (see DriveProfileSection), which is right there:
 * every tile on that screen is the same rank. Here they are not. A two-tile row would render at
 * the same half-width as the speed/trim row directly above it, and that row is deliberately the
 * second tier of the hierarchy. Fixing the grid at a third-width keeps the three ranks, hero,
 * primary pair, grid, readable by size alone from a mount.
 */
private const val GRID_COLUMNS = 3

/**
 * Live layout: the gauge cluster this theme was built for, read from a mount, in motion, often at
 * a glance. Feature idea #1 in docs/DESIGN_SYSTEM.md, now that [LoggingUiState.latestValues]
 * exists to feed it.
 *
 * The layout it replaces had session elapsed in the hero and sample/GPS counts in the tiles, which
 * was the honest ranking when the state object carried no vehicle data at all: the app's own
 * bookkeeping was genuinely the most interesting true thing on the screen. It is not any more.
 * Ranked by what a driver glances at:
 *
 *  1. **Engine RPM in the hero,** 64sp and achromatic, because MOTION is the achromatic category
 *     and the tachometer is what a cluster's largest instrument shows. Falls back to session
 *     elapsed when RPM has not answered yet or read back implausible: rule 13, a hero never says
 *     `--`, and elapsed exists from the first second of every session.
 *  2. **Speed and short-term fuel trim** as a half-width pair. Speed is the other thing a driver
 *     already expects to see; short-term trim is the signal this whole project exists to chase,
 *     and watching it move in real time is the entire argument for a live screen over an export.
 *  3. **The one alert slot,** unchanged in form and still reserved for the engine-detected check.
 *     Moved up to sit under the primary readouts rather than after them, because everything below
 *     it is now several screens of gauges and an alert under all of that is an alert nobody sees.
 *  4. **The category grid:** every other Tier A/B PID as a `MetricTile`, grouped under a
 *     `SectionLabel` naming its category and coloured with that category's fixed accent. The label
 *     is the redundant carrier the hue needs (WCAG 1.4.1); the accent is what lets "which system
 *     is this" be answered without reading it.
 *  5. **Tier C and housekeeping** collapsed into `DataRow`s in one **Context** panel, per rule 6.
 *     Category hue survives the demotion, so ambient air is still thermal blue on its line.
 *  6. **Session:** elapsed, sample count, GPS fixes, last-sample age, reconnects, as `DataRow`s in
 *     one panel at the bottom. None of it is gone and all of it is still real information; it is
 *     just the app talking about itself, which is exactly the demotion the trip report's own
 *     "Capture and delivery" block makes for the same reason. Elapsed is omitted from the block
 *     while it is the hero, so the screen never prints one number twice at two sizes.
 *
 * **A value the scheduler flagged `IMPLAUSIBLE` never renders as a number.** `valueNumeric` is
 * null on those rows by design (see PidScheduler's PLAUSIBLE_RANGES), so the tile prints `--` in
 * `Tone.FAULT`, which brings the cross glyph with it: the existing status vocabulary already has
 * a word for "no real data from the vehicle" and this is that word, not a new one. A tile that has
 * simply gone quiet longer than its tier's polling cadence allows gets `Tone.CAUTION` instead,
 * matching the status table's "stale samples" row. A PID that has never answered at all draws
 * nothing, so `--` in this cluster only ever means "the number that came back was garbage".
 */
@Composable
private fun ColumnScope.LiveBody(
    status: LoggingUiState,
    elapsedSeconds: Long,
    lastSampleAgeSeconds: Long?,
    nowMs: Long,
) {
    // Table order, not arrival order, so nothing reshuffles under a glance as slow PIDs answer.
    val gauges: List<Pair<PidDisplay, MeasurementSample>> =
        livePidDisplays(status.latestValues.keys)
            .mapNotNull { display -> status.latestValues[display.canonicalName]?.let { display to it } }

    val hero = liveHero(
        rpm = status.latestValues[CANONICAL_RPM],
        elapsedSeconds = elapsedSeconds,
        connectionState = status.connectionState,
        nowMs = nowMs,
    )
    HeroReadout(
        label = hero.label,
        value = hero.value,
        unit = hero.unit,
        accent = AccentMotion,
        caption = hero.caption,
    )

    val speed = gauges.firstOrNull { it.first.canonicalName == CANONICAL_SPEED }
    val shortTrim = gauges.firstOrNull { it.first.canonicalName in SHORT_TERM_TRIMS }
    if (speed != null || shortTrim != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
            // A missing half keeps its space rather than letting the survivor stretch across the
            // screen and read as a second hero, the same rule the report's short tile rows follow.
            if (speed != null) {
                GaugeTile(speed.first, speed.second, nowMs)
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (shortTrim != null) {
                GaugeTile(shortTrim.first, shortTrim.second, nowMs)
            } else {
                Spacer(Modifier.weight(1f))
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

    // Whatever the hero and the primary pair already drew is not drawn again below.
    val claimed = setOfNotNull(
        CANONICAL_RPM.takeIf { hero.kind == LiveHeroKind.RPM },
        speed?.first?.canonicalName,
        shortTrim?.first?.canonicalName,
    )
    val grid = gauges
        .filter { (display, _) ->
            display.tier != PidTier.C &&
                display.category != PidCategory.HOUSEKEEPING &&
                display.canonicalName !in claimed
        }
        .groupBy { it.first.category }

    // PidCategory's own order, not the map's, so a category appearing late in the drive slots
    // into the same place it always occupies rather than onto the end.
    for (category in PidCategory.entries) {
        val entries = grid[category] ?: continue
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel(category.name)
            for (rowEntries in entries.chunked(GRID_COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
                    for ((display, sample) in rowEntries) GaugeTile(display, sample, nowMs)
                    repeat(GRID_COLUMNS - rowEntries.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    val context = gauges.filter { (display, _) ->
        display.tier == PidTier.C || display.category == PidCategory.HOUSEKEEPING
    }
    if (context.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            // "Slow-changing context" is PidCatalog's own description of Tier C, and it is what
            // this block is: real data, worth finding, never worth a glance while moving.
            SectionLabel("Context")
            InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
                for ((display, sample) in context) ContextRow(display, sample, nowMs)
            }
        }
    }

    SessionBlock(
        status = status,
        elapsedSeconds = elapsedSeconds,
        lastSampleAgeSeconds = lastSampleAgeSeconds,
        showElapsed = hero.kind != LiveHeroKind.ELAPSED,
    )

    if (status.statusMessage.isNotBlank()) {
        ConsoleLine(status.statusMessage)
    }
}

// ---------------------------------------------------------------------------
// Live: the hero
// ---------------------------------------------------------------------------

private enum class LiveHeroKind { RPM, ELAPSED }

private data class LiveHero(
    val kind: LiveHeroKind,
    val label: String,
    val value: String,
    val unit: String?,
    val caption: String,
)

/**
 * Which number owns the live screen.
 *
 * RPM, whenever there is a real one. It is the tachometer's job in every cluster this design
 * borrows from, it is MOTION so it is achromatic, and it changes fast enough that a glance at it
 * confirms the whole pipeline is alive without reading a count.
 *
 * Session elapsed is the fallback rather than a `--` tachometer, on the same reasoning as the trip
 * report's own hero chain: rule 13 says a hero never says `--`, and spending the screen's largest
 * element on an absence defeats the hierarchy it exists to create. Elapsed exists from the first
 * second of every session, so the chain cannot run out. The caption says which case this is, and
 * an implausible RPM still appears, as a fault-toned tile in the MOTION group, because the fact
 * that the adapter answered with garbage is itself worth seeing.
 */
private fun liveHero(
    rpm: MeasurementSample?,
    elapsedSeconds: Long,
    connectionState: ConnectionState,
    nowMs: Long,
): LiveHero {
    val display = pidDisplayFor(CANONICAL_RPM)
    val connectionWord = if (connectionState == ConnectionState.LOGGING) {
        "recording"
    } else {
        connectionState.name.lowercase()
    }
    val formatted = rpm?.formattedValue(display)
    if (rpm != null && formatted != null) {
        val ageMs = nowMs - rpm.wallTimeUtc
        return LiveHero(
            kind = LiveHeroKind.RPM,
            label = "Engine speed",
            value = formatted,
            unit = rpm.displayUnit ?: "RPM",
            caption = if (ageMs > display.tier.staleAfterMs) {
                "last answered ${ageMs / 1000}s ago"
            } else {
                connectionWord
            },
        )
    }
    return LiveHero(
        kind = LiveHeroKind.ELAPSED,
        label = "Session elapsed",
        value = formatDuration(elapsedSeconds),
        unit = null,
        caption = when {
            rpm != null -> "engine rpm read back implausible"
            connectionState == ConnectionState.LOGGING -> "waiting for engine rpm"
            else -> connectionWord
        },
    )
}

// ---------------------------------------------------------------------------
// Live: one gauge
// ---------------------------------------------------------------------------

/**
 * The status tone a live reading has earned, or null when it has earned none and should therefore
 * keep its category hue. Both cases below already have a word in the status table
 * (docs/DESIGN_SYSTEM.md section 3); neither invents one.
 *
 *  - **No number at all** is FAULT. Either the scheduler flagged the value `IMPLAUSIBLE` and
 *    stored the raw text instead, or it never parsed as a number. Both mean the same thing to a
 *    driver, "no real data from the vehicle", which is exactly what FAULT means here.
 *  - **A number that has stopped arriving** is CAUTION, the table's "stale samples" row. The
 *    threshold is the PID's own tier budget; see PidTier.
 */
private fun liveTone(sample: MeasurementSample, display: PidDisplay, nowMs: Long): Tone? = when {
    sample.formattedValue(display) == null -> Tone.FAULT
    nowMs - sample.wallTimeUtc > display.tier.staleAfterMs -> Tone.CAUTION
    else -> null
}

@Composable
private fun RowScope.GaugeTile(display: PidDisplay, sample: MeasurementSample, nowMs: Long) {
    val text = sample.formattedValue(display)
    MetricTile(
        label = display.label,
        value = text ?: "--",
        // No unit next to a dash: "-- kPa" reads as a measurement that happens to be missing,
        // which is precisely the wrong impression for a reading that came back as garbage.
        unit = if (text != null) sample.displayUnit else null,
        accent = display.category.accent,
        tone = liveTone(sample, display, nowMs),
        modifier = Modifier.weight(1f),
    )
}

/** The same reading at `DataRow` weight, for Tier C and housekeeping. */
@Composable
private fun ContextRow(display: PidDisplay, sample: MeasurementSample, nowMs: Long) {
    val text = sample.formattedValue(display)
    val tone = liveTone(sample, display, nowMs)
    DataRow(
        label = display.label,
        // A DataRow holds one Text by design, so the unit joins the value here rather than getting
        // its own slot. The separate-unit rule exists to keep a large numeral's left edge and
        // baseline fixed; nothing at this size is being scanned as a column of digits.
        value = if (text == null) "--" else listOfNotNull(text, sample.displayUnit).joinToString(" "),
        valueColor = tone?.color ?: display.category.accent,
        leadingGlyph = tone?.glyph,
        glyphColor = tone?.color ?: Ash,
    )
}

// ---------------------------------------------------------------------------
// Live: the app talking about itself
// ---------------------------------------------------------------------------

/**
 * Elapsed time, capture counts and link health, demoted from the hero and the tile band to one
 * subordinate panel of `DataRow`s at the bottom of the screen.
 *
 * Not deleted, demoted. Every figure here is still true and still worth having: elapsed answers
 * "how long have I been driving", the counts are the only proof the GPS collector is running at
 * all, and the last-sample age is what distinguishes "the car is idling" from "the link died three
 * minutes ago". They were the top of this screen only because nothing better existed. Now that
 * real telemetry does, they are the same class of information as the trip report's "Capture and
 * delivery" block, which is the app's account of its own plumbing, and they get the same treatment.
 *
 * The reconnect count keeps its caution tone but loses its separate accent-barred panel: a
 * dedicated panel for one integer, sitting among panels that carry the drive's actual data, was
 * exactly the weight mismatch the trip report's adapter-health line already corrected.
 */
@Composable
private fun ColumnScope.SessionBlock(
    status: LoggingUiState,
    elapsedSeconds: Long,
    lastSampleAgeSeconds: Long?,
    showElapsed: Boolean,
) {
    // Threshold styling only. Nothing here changes what is polled or logged; it turns a number
    // that is already on screen into something whose severity is readable without arithmetic.
    val sampleTone = when {
        lastSampleAgeSeconds == null -> Tone.UNKNOWN
        lastSampleAgeSeconds <= 5 -> Tone.NEUTRAL
        lastSampleAgeSeconds <= 15 -> Tone.CAUTION
        else -> Tone.FAULT
    }
    val sampleDegraded = sampleTone == Tone.CAUTION || sampleTone == Tone.FAULT

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Session")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            // Omitted while the hero is showing it, so the screen never prints the same number
            // twice at two sizes.
            if (showElapsed) {
                DataRow("Elapsed", formatDuration(elapsedSeconds))
            }
            DataRow("Samples", status.measurementCount.toString())
            DataRow("GPS fixes", status.locationCount.toString())
            DataRow(
                label = "Last sample",
                value = lastSampleAgeSeconds?.let { "$it s" } ?: "none yet",
                valueColor = if (sampleDegraded) sampleTone.color else Chalk,
                leadingGlyph = if (sampleDegraded) sampleTone.glyph else null,
                glyphColor = sampleTone.color,
            )
            if (status.reconnectCount > 0) {
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
 *  2. **The best number this drive actually produced.** Not "MPG or a dash".
 *  3. **What the car said** - stored codes, but only when there are some.
 *  4. **How the drive went** - the profile tiles, then anomaly flags and braking.
 *  5. **What you want to remember about it** - the note.
 *  6. **How much to trust all of the above, and whether the app owes you anything** - one
 *     subordinate `DataRow` block at the bottom, carrying verdicts only, with the counts behind
 *     a tap that only appears when something went wrong.
 *
 * **Every one of those decisions now lives in `export/TripReport.kt` rather than here,** and this
 * function is what is left once they do: a renderer. Which figure earns the hero, whether the
 * drive gets a band and what it says, which tile the hero already claimed, which capture lines are
 * verdicts and which are counts, all of it is decided once, in one place, and drawn twice: here on
 * a dark phone screen, and again by [com.ericbarone.drivetrace.export.PdfTripReportExporter] on a
 * light printed page. The PDF is meant to be a faithful copy of this screen rather than a second
 * opinion about the same drive, and the only way to guarantee that is for both to draw the same
 * object. See TripReport.kt for what the alternative cost the last time it was tried.
 *
 * What stays here is everything that genuinely is about *this* medium: the skin's colour tokens
 * (rule 15 keeps those inside composition), the component vocabulary, the tile wrapping widths,
 * the scroll, and the note editor, which is an editor here and a line of stored text in the export.
 */
@Composable
private fun ColumnScope.CompleteBody(report: TripReport, sessionId: Long?) {
    report.verdict?.let { band ->
        StatusBand(tone = band.tone.asTone, title = band.title, body = band.body)
    }

    HeroReadout(
        label = report.hero.label,
        value = report.hero.value,
        // No category means the value is `--`, which is neither a reading nor a status. Ash rather
        // than the disabled grey: the hero avoids `--` where it can, and where it cannot the `--`
        // has to be readable. See DESIGN_SYSTEM.md section 7.
        accent = report.hero.category?.accent ?: Ash,
        unit = report.hero.unit,
        caption = report.hero.caption,
    )

    // The vehicle talking, and the highest-consequence thing this screen can carry, so it sits
    // directly under the hero. Only when there is something to say: a full accent-barred panel
    // reading "no stored trouble codes" after every single drive is the "everything is fine"
    // green wash ISA-101 rules out. The clean read is still reported, as one line in the capture
    // block at the bottom.
    if (report.codes.isNotEmpty()) {
        DiagnosticCodesSection(report.codes)
    }

    if (report.tiles.isNotEmpty()) {
        DriveProfileSection(report.tiles)
    }

    if (report.flags.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Anomaly flags")
            for (flag in report.flags) {
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

    report.braking?.let { BrakingSection(it) }

    // Last item that is about the drive, first item the thumb reaches, and directly under the
    // evidence it is a reaction to: you annotate a drive after reading what happened on it, not
    // before. Above the capture block on purpose, because the driver's own words rank over the
    // app's account of its own plumbing, the same ranking the logbook card already uses.
    sessionId?.let { id ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            SectionLabel("Drive note")
            DriveNoteEditor(sessionId = id)
        }
    }

    if (report.capture.isNotEmpty()) {
        CaptureAndDeliverySection(report.capture)
    }

    if (report.statusMessage.isNotBlank()) {
        ConsoleLine(report.statusMessage, color = Ash)
    }
}

// ---------------------------------------------------------------------------
// Session complete: the drive
// ---------------------------------------------------------------------------

/**
 * The secondary band: everything about the drive that is not the hero, at roughly a third its
 * weight. Whichever figure the hero took was already filtered out upstream rather than repeated,
 * so the screen never shows the same number twice at two sizes.
 */
@Composable
private fun ColumnScope.DriveProfileSection(tiles: List<ReportTile>) {
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
                for (tile in rowTiles) {
                    MetricTile(
                        label = tile.label,
                        value = tile.value,
                        unit = tile.unit,
                        accent = tile.category.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Short final row keeps its tiles the same width as the rows above it rather than
                // stretching a lone tile across the screen and reading as a second hero.
                repeat(perRow - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.BrakingSection(braking: ReportBraking) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Braking")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            DataRow("Braking events", braking.eventCount.toString())
            braking.fuelEquivMl?.let {
                DataRow("Est. fuel to brake heat", "%.0f mL".format(it), valueColor = AccentMixture)
            }
            braking.eventsWithoutCoast?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(Space.xs))
                Text(
                    "$it of ${braking.eventCount} had no coast phase first, " +
                        "speed was carried right up to the brakes.",
                    style = LocalReadoutType.current.unit,
                    color = StatusCaution,
                )
            }
            Spacer(Modifier.height(Space.sm))
            Caption(braking.caption)
        }
    }
}

// ---------------------------------------------------------------------------
// Session complete: the app talking about itself
// ---------------------------------------------------------------------------

/**
 * Everything about the capture rig and the pipeline rather than about the car or the drive, as one
 * subordinate block of `DataRow`-weight lines replacing what used to be four full sections.
 *
 * **The block states verdicts and the disclosure holds counts** (rule 15). A verdict is a word
 * that changes what the reader does next ("complete", "will retry", "all answered", "13 PIDs
 * dropped", "none"). A count is a number that only means something once you have already decided
 * to debug the rig, and no count answers the question this screen exists to answer. So on a clean
 * drive this is a few achromatic lines and no controls; on a bad one it grows a tone, a glyph and
 * one collapsed control. Which line is which is decided in `buildTripReport`, not here.
 *
 * There is at most one [CaptureItem.Disclosure] in the block, so its open/closed state is hoisted
 * to the section rather than remembered per item: a `remember` inside the loop would key on
 * position and quietly reopen when the list shape changed.
 */
@Composable
private fun ColumnScope.CaptureAndDeliverySection(items: List<CaptureItem>) {
    // rememberSaveable, on the same reasoning 120e0e0 applied to MainActivity's showHistory: this
    // screen outlives a process death under memory pressure often enough to be worth it, and
    // silently re-collapsing a panel the user opened is the kind of small wrongness nobody
    // reports and everybody notices.
    var showDetail by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Capture and delivery")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            for (item in items) {
                if (item is CaptureItem.Disclosure) {
                    // Attached directly under the verdict it explains rather than at the foot of
                    // the panel, so the tap and its result are one thought.
                    Spacer(Modifier.height(Space.xs))
                    SecondaryAction(
                        text = if (showDetail) "Hide ${item.label.lowercase()}" else item.label,
                        onClick = { showDetail = !showDetail },
                        minHeight = Space.compactTarget,
                    )
                    if (showDetail) {
                        Spacer(Modifier.height(Space.xs))
                        for (child in item.items) CaptureLine(child)
                        Spacer(Modifier.height(Space.sm))
                    }
                } else {
                    CaptureLine(item)
                }
            }
        }
    }
}

/** One leaf of the capture block. Never a [CaptureItem.Disclosure]; those do not nest. */
@Composable
private fun CaptureLine(item: CaptureItem) {
    when (item) {
        is CaptureItem.Stage -> StatusRow(
            label = item.label,
            state = item.state,
            tone = item.tone.asTone,
            detail = item.detail,
            pulsing = item.pulsing,
        )

        is CaptureItem.Line -> {
            // A null tone means this line has earned no status, so it keeps its emphasis colour and
            // draws no mark at all. Rule 5's other half: the normal state does not get a glyph.
            val tone = item.tone?.asTone
            DataRow(
                label = item.label,
                value = item.value,
                valueColor = tone?.color ?: item.emphasis.color,
                leadingGlyph = tone?.glyph,
                glyphColor = tone?.color ?: Ash,
            )
        }

        is CaptureItem.Note -> {
            Spacer(Modifier.height(Space.xs))
            Caption(item.text)
        }

        is CaptureItem.Disclosure -> Unit
    }
}

/**
 * Stored trouble codes, read once at session start and decoded upstream by `buildTripReport`. One
 * accent-barred panel per code, the code leading and its plain-English meaning under it, the set it
 * came from as a `StatusChip`.
 *
 * On the trip report rather than the Setup screen because a DTC is per-session data read from a
 * session that does not exist yet when Setup is on screen. Setup would have to either show the
 * *previous* drive's codes (misleading: the point of a code is that it is current) or start a
 * connection of its own just to populate a panel.
 */
@Composable
private fun ColumnScope.DiagnosticCodesSection(codes: List<ReportCode>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Diagnostic codes")
        for (code in codes) {
            val tone = code.tone.asTone
            InstrumentPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = tone.color,
                contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    GlyphMark(tone.glyph, tone.color, sizeDp = 14)
                    // The code leads and the meaning follows, not the other way round: the code is
                    // what gets typed into a search, quoted to a mechanic, or matched against a
                    // service bulletin, and it stays the same string in every one of those places.
                    Text(
                        code.code,
                        style = LocalReadoutType.current.small,
                        color = tone.color,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(text = code.group, tone = tone)
                }
                Spacer(Modifier.height(Space.xs))
                Text(
                    code.meaning,
                    style = LocalReadoutType.current.unit,
                    color = if (code.known && !code.suspect) Mist else Ash,
                )
            }
        }
        Caption(DTC_CAPTION)
    }
}

// ---------------------------------------------------------------------------
// Model to medium
//
// The report model names categories and tones; this screen resolves them to the running skin's
// tokens. Composable getters rather than stored values, per rule 15: a colour cached outside
// composition pins itself to whichever skin happened to load first and silently stops following
// the setting.
// ---------------------------------------------------------------------------

private val ReportCategory.accent: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        ReportCategory.MOTION -> AccentMotion
        ReportCategory.MIXTURE -> AccentMixture
        ReportCategory.THERMAL -> AccentThermal
    }

private val ReportEmphasis.color: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        ReportEmphasis.PRIMARY -> Chalk
        ReportEmphasis.SECONDARY -> Mist
        ReportEmphasis.ECONOMY -> AccentMixture
    }

/** The status channel is the same five states in both media, so this is a rename, not a mapping. */
private val ReportTone.asTone: Tone
    get() = when (this) {
        ReportTone.NEUTRAL -> Tone.NEUTRAL
        ReportTone.UNKNOWN -> Tone.UNKNOWN
        ReportTone.LIVE -> Tone.LIVE
        ReportTone.CAUTION -> Tone.CAUTION
        ReportTone.FAULT -> Tone.FAULT
    }

// ---------------------------------------------------------------------------

/**
 * Hand an exported file to whatever the user picks. Both exports go out the same door: the same
 * `exports/` directory the FileProvider already publishes, and the same chooser, so the CSV bundle
 * and the PDF differ only in their MIME type.
 */
private fun share(context: Context, file: File, mimeType: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}
