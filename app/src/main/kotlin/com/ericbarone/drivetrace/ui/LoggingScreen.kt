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
import com.ericbarone.drivetrace.data.describeDtc
import com.ericbarone.drivetrace.data.isSuspectedFramingArtifact
import com.ericbarone.drivetrace.data.readSessionDtcs
import com.ericbarone.drivetrace.export.CsvExporter
import com.ericbarone.drivetrace.export.TripSummary
import com.ericbarone.drivetrace.export.computeTripSummary
import com.ericbarone.drivetrace.obd.MeasurementSample
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
 *  2. **The best number this drive actually produced** (see [heroFigure]). Not "MPG or a dash".
 *  3. **What the car said** — stored codes, but only when there are some.
 *  4. **How the drive went** — the profile tiles, then anomaly flags and braking.
 *  5. **What you want to remember about it** — the note.
 *  6. **How much to trust all of the above, and whether the app owes you anything** — one
 *     subordinate `DataRow` block at the bottom, carrying verdicts only, with the counts behind
 *     a tap that only appears when something went wrong. Upload, analysis, adapter health, the
 *     clean-code confirmation and the on-device cross-check used to be four separate full-weight
 *     sections; they are all the same question and none of them is about the car or the drive.
 *     See [CaptureAndDeliverySection] for what each line had to earn to stay.
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
    // The on-device figure is only a *cross-check* when there is a server figure to check it
    // against. The two are computed differently on purpose (the server gates on stoichiometric
    // operation, this one doesn't), which is the entire reason to show both. When the server
    // never answered, the on-device number IS the hero, and printing it again in the capture
    // block is the same number twice at two sizes, which is exactly what DriveProfileSection
    // already goes out of its way to avoid. That was the state of the real screenshot that
    // prompted this pass: `23.8` at 64sp, then `23.8` again eleven rows down.
    val crossCheckMpg = deviceMpg.takeIf { serverMpg != null }

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

    CaptureAndDeliverySection(status, crossCheckMpg, adapterHealth, dtcs)

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
 * readable. Both greys are registered in every skin's daylight palette, so rule 11 still holds.
 *
 * `@Composable` only because the colour tokens it names are now skin-dependent (see
 * `ui/theme/Color.kt`). Nothing about what it decides changed.
 */
@Composable
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
            "PID${if (health.distinctPidsDropped == 1) "" else "s"} this drive; open Capture " +
            "detail below for which ones."
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
 * Everything about the capture rig and the pipeline rather than about the car or the drive.
 *
 * The first pass at this merged four full sections (**Pipeline**, **Adapter health**, the clean
 * half of **Diagnostic codes**, **On-device cross-check**) into one `DataRow`-weight block. That
 * fixed the visual weight and left the *substance* alone, which turned out to be the actual
 * problem. A photograph of the merged version on a real completed drive still read, in full:
 * `13 PIDs dropped`, `Failed reads 259`, `Cooldown pauses 123`, `LONG_TERM_BANK_2 78`,
 * `SHORT_TERM_BANK_2 78`, `FUEL_CONSUMPTION_RATE 43`, a three-line caption about unsupported
 * PIDs, then a distance and an MPG that were both already on the screen above. That is a QA log
 * for the capture rig, on the default first-look state of the screen whose entire job is "how was
 * my MPG", on every drive, whether or not anything went wrong.
 *
 * The rule that sorts it: **the block states verdicts, and the disclosure holds counts.** A
 * verdict is a word that changes what the reader does next ("complete", "will retry", "all
 * answered", "13 PIDs dropped", "none"). A count is a number that only means something once
 * you have already decided to debug the rig, and no count answers the question this screen
 * exists to answer. So on a clean drive this is four achromatic lines and no controls, and on a
 * bad one it grows a tone, a glyph, and one collapsed control.
 *
 * Per item, because "made it smaller" is not a reason:
 *
 *  - **Upload.** Kept, one line. It is the only thing here the app still owes the user, and the
 *    only one where the answer changes what they do (nothing, mostly, which is the point). The
 *    label lost `(verified complete)`, which described the delivery protocol to nobody. **A
 *    failed upload is now `CAUTION`, not `FAULT`,** matching the reasoning the verdict band
 *    already uses to refuse it a band: it retries on its own, the logbook has a control for it,
 *    and no data is lost. `WILL RETRY` in amber is what that is. `FAILED` in red was the status
 *    table's "broken" tone spent on a state that heals itself, and red for a self-healing state
 *    is precisely the wolf-crying ISA-101 exists to prevent.
 *  - **The upload's success detail** (`"412 measurements, 88 GPS, 19 events"`) moved into the
 *    disclosure. It is the app counting its own rows. `COMPLETE` already carries the verdict.
 *  - **PC analysis.** Kept, one line, still nested under a successful upload. Its `PENDING`
 *    detail line ("Waiting on the PC to analyze this drive...") is gone: the state word `RUNNING`
 *    and the pulsing dot next to it already say that, twice. A *failed* analysis keeps its
 *    server-authored message, because that one is a real cause and the server is the only thing
 *    that knows it.
 *  - **Adapter reads.** Kept as the trust signal, one line, verdict only. Clean is now
 *    `NEUTRAL` and glyphless rather than a green tick: rule 14 and ISA-101 both say the normal
 *    state is achromatic, and a tick on the one row that is fine, next to rows that carry no
 *    glyph at all, reads as decoration.
 *  - **Failed reads, cooldown pauses, the per-PID breakdown and their caption.** Behind a tap,
 *    collapsed by default, and only offered at all when something actually dropped. Not deleted:
 *    idea #9 is right that distinguishing "one unsupported PID cycling through cooldown" from
 *    "several different PIDs failing, so it's the adapter or the link" is the whole reason
 *    adapter-health reporting was built, and that distinction lives entirely in these counts.
 *    It is just never the answer to "how was my MPG". Collapsing it behind a `SecondaryAction`
 *    is the same move, and the same component, the logbook already uses for its per-card note
 *    editor, so this adds no vocabulary.
 *  - **The clean-DTC line.** Kept, unchanged. One achromatic line for a confirmed clean read of
 *    the highest-consequence thing this screen reports is the cheapest true statement on it.
 *  - **The on-device cross-check.** Kept only when it is one. Two figures computed two ways
 *    disagreeing is real information; the same figure printed twice is not. The caller passes a
 *    number here only when the server also produced one, so on a drive where the server never
 *    answered (which is every drive where this block used to be at its longest) the row and its
 *    caption both disappear rather than restating the hero.
 *  - **The on-device distance row is gone outright.** It was never a cross-check. Both figures
 *    are computed from the same GPS fixes out of the same Room table, one on the phone and one
 *    on the PC after the phone uploaded them; agreement between them tests the upload, not the
 *    measurement. Distance already has a hero slot and a `MetricTile`, and this was its third
 *    appearance on one screen.
 *  - **The `calculating...` and `n/a (no fuel data)` rows are gone.** The hero says both, at
 *    64sp, before the reader gets this far.
 */
@Composable
private fun ColumnScope.CaptureAndDeliverySection(
    status: LoggingUiState,
    crossCheckMpg: Double?,
    health: AdapterHealth?,
    dtcs: DtcReport?,
) {
    // rememberSaveable, on the same reasoning 120e0e0 applied to MainActivity's showHistory: this
    // screen outlives a process death under memory pressure often enough to be worth it, and
    // silently re-collapsing a panel the user opened is the kind of small wrongness nobody
    // reports and everybody notices.
    var showDetail by rememberSaveable { mutableStateOf(false) }

    // Never the raw status.backfillMessage: on the failure path that is the transport exception,
    // naming the server's hostname, public IP and port. ui/PipelineMessages.kt is the single door
    // and it is a whitelist, not a scrubber; rule 12. On success the string it returns is three
    // integers the app counted itself, which is a count, so it goes in the disclosure.
    val uploaded = when (status.backfillStatus) {
        TriState.YES -> true
        TriState.NO -> false
        TriState.PENDING -> null
    }
    val uploadMessage = uploadDetail(uploaded = uploaded, rawMessage = status.backfillMessage)
    val degraded = health != null && !health.isClean

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionLabel("Capture and delivery")
        InstrumentPanel(modifier = Modifier.fillMaxWidth()) {
            StatusRow(
                label = "Upload",
                state = when (status.backfillStatus) {
                    TriState.PENDING -> "uploading"
                    TriState.YES -> "complete"
                    TriState.NO -> "will retry"
                },
                tone = if (uploaded == false) Tone.CAUTION else toneOf(status.backfillStatus),
                detail = uploadMessage.takeIf { uploaded == false },
                pulsing = status.backfillStatus == TriState.PENDING,
            )
            // Nesting rule unchanged: analysis cannot have an outcome until the upload landed.
            if (status.backfillStatus == TriState.YES) {
                StatusRow(
                    label = "PC analysis",
                    state = triStateWord(status.analysisStatus, pending = "running"),
                    tone = toneOf(status.analysisStatus),
                    detail = status.analysisMessage
                        .takeIf { status.analysisStatus == TriState.NO && it.isNotBlank() },
                    pulsing = status.analysisStatus == TriState.PENDING,
                )
            }

            if (health != null) {
                val tone = when {
                    health.isClean -> Tone.NEUTRAL
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
                    // Glyph only when it carries something. Clean is the normal state and the
                    // normal state does not get a mark; see rule 5's other half.
                    leadingGlyph = tone.glyph.takeIf { !health.isClean },
                    glyphColor = tone.color,
                )
            }

            // The counts, off by default, attached directly under the verdict they explain rather
            // than at the foot of the panel, so the tap and its result are one thought.
            if (degraded) {
                Spacer(Modifier.height(Space.xs))
                SecondaryAction(
                    text = if (showDetail) "Hide capture detail" else "Capture detail",
                    onClick = { showDetail = !showDetail },
                    minHeight = Space.compactTarget,
                )
                if (showDetail) {
                    Spacer(Modifier.height(Space.xs))
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
                    uploadMessage.takeIf { uploaded == true }?.let {
                        Spacer(Modifier.height(Space.xs))
                        Caption("Delivered: $it.")
                    }
                    Spacer(Modifier.height(Space.sm))
                }
            }

            // The clean-read confirmation, demoted out of its own section. Achromatic rather than
            // green: "nothing stored" is the normal state and normal state does not get a colour.
            if (dtcs != null && dtcs.read && dtcs.isEmpty) {
                DataRow("Stored trouble codes", "none", valueColor = Chalk)
            }

            if (crossCheckMpg != null) {
                DataRow(
                    "On-device MPG cross-check",
                    "%.1f".format(crossCheckMpg),
                    valueColor = AccentMixture,
                )
                Spacer(Modifier.height(Space.sm))
                Caption(
                    "The hero figure is the PC's, gated on stoichiometric operation. This one is " +
                        "the phone's: total distance over total fuel burned, ungated. They only " +
                        "part company when a real share of the drive ran outside closed loop.",
                )
            }
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
