package com.ericbarone.drivetrace.export

import com.ericbarone.drivetrace.data.AdapterHealth
import com.ericbarone.drivetrace.data.DtcReport
import com.ericbarone.drivetrace.data.describeDtc
import com.ericbarone.drivetrace.data.isSuspectedFramingArtifact
import com.ericbarone.drivetrace.service.LoggingUiState
import com.ericbarone.drivetrace.service.TriState
import com.ericbarone.drivetrace.streaming.AnalysisSummary
import com.ericbarone.drivetrace.ui.uploadDetail

/**
 * The Session-Complete trip report, as data rather than as a screen.
 *
 * This exists because the report now has two renderers: `CompleteBody` (LoggingScreen.kt) draws it
 * on a dark phone display, and [PdfTripReportExporter] draws it on a light printed page. Every
 * decision the report makes is a judgement call with prose attached to it, and none of them are
 * about pixels: which figure earns the hero slot, whether the drive gets an alert band and what
 * that band says, which profile tile is omitted because the hero already took it, which capture
 * lines are verdicts and which are counts. Duplicating those into a second renderer would mean
 * two copies drifting apart the first time one of them is edited, and the PDF is specifically
 * meant to be a faithful copy of what was on screen, not a second opinion about the same drive.
 *
 * That drift is not hypothetical. An earlier draft of this file was written against the report as
 * it stood before commits `c874eae`/`cdaf7f7` and was never wired to the screen; by the time it
 * was picked up again it described a capture block that no longer existed (a red `FAILED` upload,
 * every count inline, a green tick on a clean adapter, `calculating...` rows the hero already
 * says at 64sp). **So the screen renders from this model too.** A model only one renderer reads
 * is a second copy with extra steps.
 *
 * **Nothing here derives anything from raw measurements.** [buildTripReport] takes exactly what
 * `CompleteBody` is already handed: the server's [AnalysisSummary] (via [LoggingUiState]), the
 * on-device [TripSummary], [AdapterHealth], [DtcReport] and the frozen session duration. Economy,
 * distance, idle fraction and warm-up are computed once, upstream, by the same code as before.
 *
 * **Colours are named as categories and tones, not as `Color` values.** Design system rule 15
 * says a colour token is read from composable code and never stored outside it, and the semantic
 * contract ("mixture is mixture forever") is what actually has to survive the trip between
 * renderers. Each renderer resolves a category to the palette its medium needs: the screen to the
 * skin tokens in `ui/theme/Color.kt`, the PDF to their print equivalents. A model carrying
 * `Color(0xFF2ED3C6)` would force teal-on-white into the export and would pin the screen to
 * whichever skin was loaded first.
 *
 * **The upload detail is whitelisted here, once.** [buildTripReport] is the only thing that sees
 * `LoggingUiState.backfillMessage`, and it runs it through `uploadDetail` (ui/PipelineMessages.kt)
 * before it reaches the model. Design system rule 12 says a caught exception's text never reaches
 * a composable; it must not reach an exported file either, and a PDF is worse than a screen,
 * because a screen has to be photographed to leak whereas a file gets forwarded. The renderers
 * cannot break that rule because they are never given the raw string.
 *
 * **The drive note is deliberately not here.** On screen it is an editor seeded from Room
 * (`ui/DriveNote.kt`); in the export it is whatever text was stored at the moment Export was
 * tapped. Those are two different things that happen to show the same string, and the exporter
 * reads it off `SessionEntity` alongside the rest of the drive's identity (date, vehicle, adapter)
 * which the screen gets from its header bar rather than from the report.
 */
data class TripReport(
    /** The report's one alert slot. Null on a drive that went fine, which is most of them. */
    val verdict: ReportBand?,
    val hero: ReportHero,
    /** Decoded stored codes, non-empty only when the read came back with something in it. */
    val codes: List<ReportCode>,
    val tiles: List<ReportTile>,
    val flags: List<String>,
    val braking: ReportBraking?,
    /** Everything about the capture rig and the pipeline rather than about the car, in order. */
    val capture: List<CaptureItem>,
    /** The service's own last line. Console weight on both renderers, or absent. */
    val statusMessage: String,
)

/** Which vehicle system a figure belongs to. Fixed per system; see DESIGN_SYSTEM.md section 3. */
enum class ReportCategory { MOTION, MIXTURE, THERMAL }

/**
 * The status channel, kept strictly separate from [ReportCategory]. Every tone carries a glyph in
 * both renderers, so colour is never the sole carrier (WCAG 1.4.1).
 */
enum class ReportTone { NEUTRAL, UNKNOWN, LIVE, CAUTION, FAULT }

/** How loudly a capture-block value is stated. Maps to a colour in each renderer's own palette. */
enum class ReportEmphasis { PRIMARY, SECONDARY, ECONOMY }

enum class HeroKind { MPG, DISTANCE, DURATION, NONE }

data class ReportHero(
    val kind: HeroKind,
    val label: String,
    val value: String,
    val unit: String?,
    /**
     * Null when the value is `--`, which is neither a category nor a status: both renderers paint
     * that case in their body grey rather than in an accent, so an absence cannot be mistaken for
     * a reading. See DESIGN_SYSTEM.md section 7 on why it is the *readable* grey and not the
     * disabled one.
     */
    val category: ReportCategory?,
    val caption: String,
)

data class ReportBand(val tone: ReportTone, val title: String, val body: String)

data class ReportTile(
    val label: String,
    val value: String,
    val unit: String?,
    val category: ReportCategory,
)

/**
 * One stored trouble code, already decoded. [group] is the set it came from, which is what
 * decides how loud it is: current codes are the lit check-engine lamp, pending and permanent are
 * real information but neither is "stop driving".
 */
data class ReportCode(
    val code: String,
    val meaning: String,
    val group: String,
    val tone: ReportTone,
    /** A code the library's unverified decode is known to fabricate; [meaning] says so instead. */
    val suspect: Boolean,
    /** False when the meaning is a structural decode rather than a table lookup. */
    val known: Boolean,
)

data class ReportBraking(
    val eventCount: Int,
    val fuelEquivMl: Double?,
    val eventsWithoutCoast: Int?,
    val caption: String,
)

/**
 * One line of the capture-and-delivery block, in render order. A sealed list rather than parallel
 * lists because the order is load-bearing: analysis follows the upload it depends on, and the
 * count disclosure has to sit directly under the verdict it explains rather than at the foot of
 * the block.
 */
sealed interface CaptureItem {
    /** A pipeline stage with a state word: upload, analysis. */
    data class Stage(
        val label: String,
        val state: String,
        val tone: ReportTone,
        val detail: String?,
        val pulsing: Boolean,
    ) : CaptureItem

    /**
     * A label/value line. [tone] non-null means the value takes the tone's colour and carries its
     * glyph; null means the value is stated at [emphasis] with no mark at all, which is what the
     * normal state gets (rule 14, and ISA-101 before it).
     */
    data class Line(
        val label: String,
        val value: String,
        val emphasis: ReportEmphasis = ReportEmphasis.PRIMARY,
        val tone: ReportTone? = null,
    ) : CaptureItem

    /** A methodology caveat. Small print, and it stays small print in the export. */
    data class Note(val text: String) : CaptureItem

    /**
     * The counts, and only ever the counts. Rule 15: verdicts render inline, counts render behind
     * a tap and only when something is actually wrong.
     *
     * The screen collapses this behind a `SecondaryAction`, default closed. **The PDF prints it
     * inline, subordinate.** The rule is about the resting state of a screen someone opens after
     * every drive; a PDF has no resting state and no tap, it is generated on purpose, once, and
     * the reason anyone generates one is to hand the drive to someone else. This block only exists
     * at all when a read actually dropped, so printing it costs nothing on a good drive and is
     * exactly the page the mechanic wanted on a bad one.
     */
    data class Disclosure(val label: String, val items: List<CaptureItem>) : CaptureItem
}

/**
 * Assemble the report from exactly what the Session-Complete screen already holds.
 *
 * [durationSeconds] is the frozen session length (LoggingScreen captures it as the session crosses
 * into the complete state rather than ticking it off a clock), and a null [tripSummary] means the
 * on-device pass has not returned yet, which is the one honest "still working it out" state.
 */
fun buildTripReport(
    status: LoggingUiState,
    tripSummary: TripSummary?,
    adapterHealth: AdapterHealth?,
    dtcs: DtcReport?,
    durationSeconds: Long?,
): TripReport {
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
    // against. The two are computed differently on purpose, which is the entire reason to show
    // both. When the server never answered, the on-device number IS the hero, and printing it
    // again in the capture block is the same number twice at two sizes.
    val crossCheckMpg = deviceMpg.takeIf { serverMpg != null }

    val hero = heroFigure(
        mpg = mpg,
        fromServer = serverMpg != null,
        distanceKm = distanceKm.takeIf { drove },
        durationSeconds = durationSeconds,
        settled = settled,
    )

    return TripReport(
        verdict = captureVerdict(
            settled = settled,
            haveMpg = mpg != null,
            drove = drove,
            health = adapterHealth,
        ),
        hero = hero,
        codes = storedCodes(dtcs),
        tiles = driveProfileTiles(
            analysis = analysis,
            distanceKm = distanceKm.takeIf { hero.kind != HeroKind.DISTANCE },
            durationSeconds = durationSeconds.takeIf { hero.kind != HeroKind.DURATION },
        ),
        flags = analysis?.flags.orEmpty(),
        braking = analysis?.takeIf { (it.brakingEventCount ?: 0) > 0 }?.let {
            ReportBraking(
                eventCount = it.brakingEventCount!!,
                fuelEquivMl = it.brakingFuelEquivMl,
                eventsWithoutCoast = it.brakingEventsWithoutCoast,
                caption = BRAKING_CAPTION,
            )
        },
        capture = captureItems(status, crossCheckMpg, adapterHealth, dtcs),
        statusMessage = status.statusMessage,
    )
}

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
 * ever started. `--` is now unreachable except in the second or two before the on-device pass
 * returns, where it is honest.
 *
 * The cost is that the hero changes identity between drives, and the design system leans on the
 * hero slot's position to identify what is in it (which is part of why MOTION is achromatic). The
 * trade is worth taking here and would not be on the LIVE screen: this one is read stationary
 * with attention, the readout already carries its own label and provenance caption, and the
 * accent still obeys the category contract, mixture for economy and MOTION for distance and
 * duration, so the hue never lies about which system the number came from. "You drove 12.4 km and
 * got no fuel data" is a result. "-- MPG" is a layout.
 */
private fun heroFigure(
    mpg: Double?,
    fromServer: Boolean,
    distanceKm: Double?,
    durationSeconds: Long?,
    settled: Boolean,
): ReportHero = when {
    mpg != null -> ReportHero(
        kind = HeroKind.MPG,
        label = "Trip economy",
        value = "%.1f".format(mpg),
        unit = "MPG",
        category = ReportCategory.MIXTURE,
        caption = if (fromServer) {
            "server analysis, stoichiometric-gated"
        } else {
            "on-device estimate, ungated"
        },
    )
    !settled -> ReportHero(
        kind = HeroKind.NONE,
        label = "Trip economy",
        value = "--",
        unit = "MPG",
        category = null,
        caption = "calculating...",
    )
    distanceKm != null -> ReportHero(
        kind = HeroKind.DISTANCE,
        label = "Distance driven",
        value = "%.2f".format(distanceKm),
        unit = "km",
        category = ReportCategory.MOTION,
        caption = "no fuel data, so no economy figure for this drive",
    )
    durationSeconds != null -> ReportHero(
        kind = HeroKind.DURATION,
        label = "Session length",
        value = formatDuration(durationSeconds),
        unit = null,
        category = ReportCategory.MOTION,
        caption = "the only figure this session produced",
    )
    else -> ReportHero(
        kind = HeroKind.NONE,
        label = "Trip economy",
        value = "--",
        unit = "MPG",
        category = null,
        caption = "no fuel data captured",
    )
}

/**
 * The trip report's one alert slot, and it answers the question that outranks every number on the
 * report: is any of this worth reading.
 *
 * Nothing else is allowed to be a band, exactly as on the LIVE layout. A failed upload
 * deliberately does not qualify: it retries on its own, the logbook has a Retry control, and
 * nothing about the drive is lost, so it is plumbing and it belongs in the block at the bottom
 * with the rest of the plumbing. Losing the drive's data is not plumbing.
 *
 * The body names the likely cause rather than restating the symptom, and points at the one
 * section that has the detail. That connection, "no MPG *because* the adapter dropped reads", is
 * the whole job: the old layout had both facts on screen and left the reader to join them.
 */
private fun captureVerdict(
    settled: Boolean,
    haveMpg: Boolean,
    drove: Boolean,
    health: AdapterHealth?,
): ReportBand? {
    if (!settled || haveMpg) return null

    val adapterClause = if (health != null && !health.isClean) {
        " The adapter dropped ${health.distinctPidsDropped} " +
            "PID${if (health.distinctPidsDropped == 1) "" else "s"} this drive; see capture " +
            "detail below for which ones."
    } else {
        ""
    }

    return if (drove) {
        ReportBand(
            tone = ReportTone.CAUTION,
            title = "No fuel data this drive",
            body = "Distance was recorded, but Mass Air Flow never answered, so this drive has no " +
                "economy figure and can't be compared against another one.$adapterClause",
        )
    } else {
        ReportBand(
            tone = ReportTone.FAULT,
            title = "Nothing usable recorded",
            body = "No distance and no fuel data came back, so there is nothing in this report to " +
                "compare against another drive.$adapterClause",
        )
    }
}

/**
 * Stored codes, decoded once here rather than twice in two renderers.
 *
 * Empty on a clean read, which is nearly every drive: the confirmation that the read came back
 * clean is one `Line` down in the capture block instead, because a full section reporting "normal"
 * after every drive is wallpaper by the third one (rule 14).
 */
private fun storedCodes(dtcs: DtcReport?): List<ReportCode> {
    if (dtcs == null || !dtcs.read || dtcs.isEmpty) return emptyList()
    // Current codes are the lit check-engine lamp, so they are the only fault-toned group.
    // Pending has not been confirmed across enough drive cycles to light anything, and permanent
    // is a code already cleared that the ECU is holding until its own monitors pass.
    return listOf(
        Triple("Current", dtcs.current, ReportTone.FAULT),
        Triple("Pending", dtcs.pending, ReportTone.CAUTION),
        Triple("Permanent", dtcs.permanent, ReportTone.CAUTION),
    ).flatMap { (group, codes, tone) ->
        codes.map { raw ->
            val described = describeDtc(raw)
            // A code the library's unverified decode is known to fabricate gets demoted out of the
            // fault channel entirely: showing "confirmed fault" next to a parser artifact is worse
            // than showing nothing, and this app cannot yet tell the two apart. See KNOWN_ISSUES.md.
            val suspect = isSuspectedFramingArtifact(described.code)
            ReportCode(
                code = described.code,
                meaning = if (suspect) SUSPECT_CODE_MEANING else described.meaning,
                group = group,
                tone = if (suspect) ReportTone.UNKNOWN else tone,
                suspect = suspect,
                known = described.known,
            )
        }
    }
}

/**
 * The secondary band: everything about the drive that is not the hero. Whichever figure the hero
 * took is already filtered out by the caller rather than repeated, so the report never shows the
 * same number twice at two sizes.
 */
private fun driveProfileTiles(
    analysis: AnalysisSummary?,
    distanceKm: Double?,
    durationSeconds: Long?,
): List<ReportTile> = buildList {
    if (distanceKm != null) {
        add(ReportTile("Distance", "%.2f".format(distanceKm), "km", ReportCategory.MOTION))
    }
    if (durationSeconds != null) {
        add(ReportTile("Duration", formatDuration(durationSeconds), null, ReportCategory.MOTION))
    }
    analysis?.idleFractionPct?.let {
        add(ReportTile("Idle", "%.1f".format(it), "%", ReportCategory.MIXTURE))
    }
    analysis?.warmupMinutes?.let {
        add(ReportTile("Warm-up", "%.1f".format(it), "min", ReportCategory.THERMAL))
    }
}

/**
 * Everything that is about the capture rig and the pipeline rather than about the car or the
 * drive, as one subordinate block.
 *
 * The rule that sorts it (rule 15): **the block states verdicts, and the disclosure holds
 * counts.** A verdict is a word that changes what the reader does next ("complete", "will retry",
 * "all answered", "13 PIDs dropped", "none"). A count is a number that only means something once
 * you have already decided to debug the rig, and no count answers the question this report exists
 * to answer. So on a clean drive this is a handful of achromatic lines and no disclosure at all.
 */
private fun captureItems(
    status: LoggingUiState,
    crossCheckMpg: Double?,
    health: AdapterHealth?,
    dtcs: DtcReport?,
): List<CaptureItem> = buildList {
    val uploaded = when (status.backfillStatus) {
        TriState.YES -> true
        TriState.NO -> false
        TriState.PENDING -> null
    }
    // Never the raw status.backfillMessage: on the failure path it is the transport exception,
    // naming the server's hostname, public IP and port. See ui/PipelineMessages.kt for why this is
    // a whitelist rather than a scrubber, and the class comment above for why the whitelist runs
    // here rather than in each renderer.
    val uploadMessage = uploadDetail(uploaded = uploaded, rawMessage = status.backfillMessage)

    add(
        CaptureItem.Stage(
            label = "Upload",
            state = when (status.backfillStatus) {
                TriState.PENDING -> "uploading"
                TriState.YES -> "complete"
                TriState.NO -> "will retry"
            },
            // A failed upload is CAUTION, not FAULT: it retries on its own, the logbook has a
            // control for it, and no data is lost. Red for a self-healing state is the wolf-crying
            // ISA-101 exists to prevent.
            tone = if (uploaded == false) ReportTone.CAUTION else toneOf(status.backfillStatus),
            detail = uploadMessage.takeIf { uploaded == false },
            pulsing = status.backfillStatus == TriState.PENDING,
        ),
    )
    // Nesting rule unchanged: analysis cannot have an outcome until the upload landed.
    if (status.backfillStatus == TriState.YES) {
        add(
            CaptureItem.Stage(
                label = "PC analysis",
                state = triStateWord(status.analysisStatus, pending = "running"),
                tone = toneOf(status.analysisStatus),
                // No pending detail: the state word RUNNING and the pulsing dot already say it,
                // twice. A failed analysis keeps its server-authored message, because that is a
                // real cause and the server is the only thing that knows it.
                detail = status.analysisMessage
                    .takeIf { status.analysisStatus == TriState.NO && it.isNotBlank() },
                pulsing = status.analysisStatus == TriState.PENDING,
            ),
        )
    }

    if (health != null) {
        add(
            CaptureItem.Line(
                label = "Adapter reads",
                value = if (health.isClean) {
                    "all answered"
                } else {
                    "${health.distinctPidsDropped} " +
                        "PID${if (health.distinctPidsDropped == 1) "" else "s"} dropped"
                },
                // Clean is glyphless and achromatic: the normal state does not get a mark.
                tone = when {
                    health.isClean -> null
                    health.distinctPidsDropped <= 2 -> ReportTone.CAUTION
                    else -> ReportTone.FAULT
                },
            ),
        )
    }

    // The counts, attached directly under the verdict they explain rather than at the foot of the
    // block, so the control and its result are one thought. Offered only when something actually
    // dropped: on a clean drive the block carries no disclosure and therefore no controls at all.
    if (health != null && !health.isClean) {
        add(
            CaptureItem.Disclosure(
                label = "Capture detail",
                items = buildList {
                    add(CaptureItem.Line("Failed reads", health.failedReads.toString(), ReportEmphasis.SECONDARY))
                    add(CaptureItem.Line("Cooldown pauses", health.cooldowns.toString(), ReportEmphasis.SECONDARY))
                    for ((pidTag, count) in health.worstOffenders) {
                        add(CaptureItem.Line(pidTag, count.toString(), ReportEmphasis.SECONDARY))
                    }
                    add(CaptureItem.Note(ADAPTER_HEALTH_CAPTION))
                    // The upload's success detail is three integers the app counted itself, which
                    // makes it a count. COMPLETE already carried the verdict.
                    uploadMessage.takeIf { uploaded == true }?.let { add(CaptureItem.Note("Delivered: $it.")) }
                },
            ),
        )
    }

    // The clean-read confirmation, demoted out of its own section. Achromatic rather than green:
    // "nothing stored" is the normal state and normal state does not get a colour.
    if (dtcs != null && dtcs.read && dtcs.isEmpty) {
        add(CaptureItem.Line("Stored trouble codes", "none"))
    }

    if (crossCheckMpg != null) {
        add(
            CaptureItem.Line(
                "On-device MPG cross-check",
                "%.1f".format(crossCheckMpg),
                ReportEmphasis.ECONOMY,
            ),
        )
        add(CaptureItem.Note(CROSS_CHECK_CAPTION))
    }
}

// ---------------------------------------------------------------------------
// Fixed prose. Here rather than in either renderer, for the same reason every other decision is:
// two copies of a caveat drift, and the caveat is the part that keeps a figure honest.
// ---------------------------------------------------------------------------

const val ADAPTER_HEALTH_CAPTION =
    "A PID this ECU genuinely doesn't support fails every attempt too, so one PID with a high " +
        "count is more likely unsupported than a bad adapter. Several different PIDs failing is " +
        "the adapter or the Bluetooth link."

const val CROSS_CHECK_CAPTION =
    "The hero figure is the PC's, gated on stoichiometric operation. This one is the phone's: " +
        "total distance over total fuel burned, ungated. They only part company when a real share " +
        "of the drive ran outside closed loop."

const val BRAKING_CAPTION =
    "Estimate only: braking is inferred from deceleration rate, not measured, and the mL figure " +
        "assumes a fixed vehicle mass/engine efficiency. See analysis_report.md for details."

const val DTC_CAPTION =
    "Meanings come from the generic SAE code table built into this app. Manufacturer-specific " +
        "codes (P1xxx and some P3xxx) are decoded structurally only; check them against the " +
        "vehicle's own service data before acting on one. This app reads codes and never clears " +
        "them."

const val SUSPECT_CODE_MEANING =
    "Probably not a real code: this matches the response-framing artifact the library's " +
        "unverified DTC decode is known to produce for this request. See KNOWN_ISSUES.md."

// ---------------------------------------------------------------------------

internal fun toneOf(state: TriState): ReportTone = when (state) {
    TriState.PENDING -> ReportTone.UNKNOWN
    TriState.YES -> ReportTone.LIVE
    TriState.NO -> ReportTone.FAULT
}

internal fun triStateWord(state: TriState, pending: String): String = when (state) {
    TriState.PENDING -> pending
    TriState.YES -> "complete"
    TriState.NO -> "failed"
}

/** Shared by the live screen's hero, the report's hero and the Duration tile. */
fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
