package com.ericbarone.drivetrace.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.ericbarone.drivetrace.BuildConfig
import com.ericbarone.drivetrace.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Session-Complete trip report as a one-page printable document, drawn with the platform's
 * own [PdfDocument] onto a [Canvas]. Feature idea #2.
 *
 * **Why a second renderer at all, and why it is not a screenshot.** A screenshot of the report is
 * a dark-mode phone screen: 1080px wide, cropped to whatever was on screen, unreadable when
 * printed and enormous when emailed. What this feature is actually for is the artifact you hand a
 * mechanic or attach to a forum post, which means real text (searchable, selectable, copy-pastable
 * into a service ticket), a page size a printer understands, and a layout that assumes a reader
 * sitting still rather than a driver glancing.
 *
 * **It draws [TripReport], the same object `CompleteBody` draws.** Not the same *data*, the same
 * object: `LoggingScreen` builds one report and hands it to both, so the PDF cannot claim
 * something the screen did not, and neither can drift from the other when the report's rules
 * change again. See TripReport.kt for what happened the last time a model was written for one
 * renderer and wired to neither.
 *
 * ---------------------------------------------------------------------------
 * **Light ground, and this is not a contradiction of the dark-first rule.**
 *
 * DESIGN_SYSTEM.md section 2 locks the app to a negative-polarity display, and every reason it
 * gives is about the medium: a bright panel destroys dark adaptation at night, it reflects off
 * the windscreen, and automotive clusters and glass cockpits are light-on-dark for exactly those
 * reasons. Not one of those reasons survives the trip to a printed page. Nobody reads this in a
 * moving car in the dark; it is read stationary, on a desk, on a laptop screen or on paper, and
 * on paper "dark ground" means an ink-flooded sheet that most printers render as a grey smear and
 * that costs a cartridge to produce. The dark-first decision is a claim about glare and dark
 * adaptation, not a brand, so applying it here would be cargo-culting the conclusion past the
 * argument.
 *
 * **Everything else about the design does carry over,** because none of it was about polarity:
 *
 *  - **The category contract is absolute** (rule 4). Mixture is teal, thermal is blue, MOTION is
 *    achromatic, on paper as on glass. The hues are darkened to clear 4.5:1 against white instead
 *    of against `Ink`; the *assignment* is untouched, so the hero's colour still says which system
 *    the number came from.
 *  - **Status stays separate from category** (rule 5) and still never travels without its glyph,
 *    which matters more here than on the phone: this page gets printed on monochrome office
 *    lasers and photocopied, and colour is the first thing that channel loses.
 *  - **Normal is achromatic** (ISA-101). A clean drive prints as black text on white with no
 *    colour anywhere except the hero's accent.
 *  - **Tabular figures** (rule 2), via `Paint.fontFeatureSettings = "tnum"` on every numeric
 *    paint, so the figures line up in a column exactly as they do on screen.
 *  - **The unit is a separate draw call** (rule 3), positioned off the numeral's measured width,
 *    so the numeral's left edge and baseline are fixed.
 *  - **Elevation is a hairline** (rule 7). No shadows, no fills except the faintest panel tint.
 *
 * The sizes are in points rather than dp, since a PDF point *is* 1/72 inch and the page is 612 x
 * 792 of them (US Letter). The type scale is the app's, re-proportioned for a page read at arm's
 * length rather than at a glance from a mount: the hero drops from 64sp to 46pt because it no
 * longer has to survive a two-second glance, and the body sizes rise relative to it because they
 * now have to survive being read properly.
 *
 * **Pagination.** One page is the design and one page is what a normal drive produces. The writer
 * still breaks to a second page rather than clipping, because the alternative is a report that
 * silently loses its capture block on exactly the bad drive that needed it: a session with three
 * stored codes, several anomaly flags and a dropped-PID breakdown genuinely does not fit, and
 * "one page" is not worth deleting the evidence for.
 */
class PdfTripReportExporter(private val context: Context) {

    suspend fun export(sessionId: Long, report: TripReport): File =
        withContext(Dispatchers.IO) {
            val session = AppDatabase.getInstance(context).sessionDao().getSession(sessionId)
                ?: error("Session $sessionId not found")

            val exportDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                .format(Date(session.startWallTimeUtc))
            val file = File(exportDir, "drivetrace_${stamp}_session-${sessionId}_report.pdf")

            val document = PdfDocument()
            try {
                val writer = PageWriter(document)
                writer.drawHeader(
                    title = HEADER_DATE.format(Date(session.startWallTimeUtc)),
                    identity = listOfNotNull(
                        session.vehicleProfile,
                        "session $sessionId",
                        session.adapterName,
                    ).joinToString("  ·  "),
                )
                writer.drawReport(report, note = session.notes?.takeIf { it.isNotBlank() })
                writer.finish()
                file.outputStream().use { document.writeTo(it) }
            } finally {
                document.close()
            }
            file
        }

    private companion object {
        val HEADER_DATE = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault())
        val FOOTER_DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    // -----------------------------------------------------------------------
    // The page
    // -----------------------------------------------------------------------

    /**
     * A cursor over one or more pages. Every `draw*` advances [y] by exactly what it consumed, and
     * every block that could land across the fold asks [space] for room first.
     */
    private inner class PageWriter(private val document: PdfDocument) {
        private var pageNumber = 0
        private var page: PdfDocument.Page = newPage()
        private var canvas: Canvas = page.canvas
        private var y = MARGIN

        private fun newPage(): PdfDocument.Page {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNumber).create()
            return document.startPage(info)
        }

        /** Ensure [height] points remain above the footer, breaking the page if they do not. */
        private fun space(height: Float) {
            if (y + height <= PAGE_H - MARGIN - FOOTER_RESERVE) return
            drawFooter()
            document.finishPage(page)
            page = newPage()
            canvas = page.canvas
            y = MARGIN
        }

        fun finish() {
            drawFooter()
            document.finishPage(page)
        }

        // -------------------------------------------------------------------
        // Chrome
        // -------------------------------------------------------------------

        fun drawHeader(title: String, identity: String) {
            canvas.drawText("DRIVETRACE", MARGIN, y + 9f, Paints.wordmark)
            canvas.drawText(
                "TRIP REPORT",
                PAGE_W - MARGIN - Paints.label.measureText("TRIP REPORT"),
                y + 9f,
                Paints.label,
            )
            y += 26f
            canvas.drawText(title, MARGIN, y, Paints.title)
            y += 15f
            canvas.drawText(identity, MARGIN, y, Paints.mono)
            y += 12f
            rule()
            y += SECTION
        }

        private fun drawFooter() {
            val footerY = PAGE_H - MARGIN + 4f
            canvas.drawLine(MARGIN, footerY - 14f, PAGE_W - MARGIN, footerY - 14f, Paints.hairline)
            canvas.drawText(
                "DriveTrace ${BuildConfig.VERSION_NAME}  ·  generated ${FOOTER_DATE.format(Date())}",
                MARGIN,
                footerY,
                Paints.footer,
            )
            val n = "page $pageNumber"
            canvas.drawText(n, PAGE_W - MARGIN - Paints.footer.measureText(n), footerY, Paints.footer)
        }

        // -------------------------------------------------------------------
        // The report
        // -------------------------------------------------------------------

        fun drawReport(report: TripReport, note: String?) {
            report.verdict?.let { drawBand(it) }
            drawHero(report.hero)
            if (report.codes.isNotEmpty()) {
                sectionLabel("Diagnostic codes")
                for (code in report.codes) drawCode(code)
                caption(DTC_CAPTION)
                y += SECTION
            }
            if (report.tiles.isNotEmpty()) {
                sectionLabel("Drive profile")
                drawTiles(report.tiles)
                y += SECTION
            }
            if (report.flags.isNotEmpty()) {
                sectionLabel("Anomaly flags")
                for (flag in report.flags) drawFlag(flag)
                y += SECTION
            }
            report.braking?.let { drawBraking(it) }
            if (note != null) {
                sectionLabel("Drive note")
                space(24f)
                y += paragraph(note, Paints.body, MARGIN, CONTENT_W)
                y += SECTION
            }
            if (report.capture.isNotEmpty()) {
                sectionLabel("Capture and delivery")
                drawCaptureItems(report.capture, MARGIN, CONTENT_W)
                y += SECTION
            }
            if (report.statusMessage.isNotBlank()) {
                space(14f)
                y += paragraph(report.statusMessage, Paints.mono, MARGIN, CONTENT_W)
            }
        }

        /**
         * The report's one alert band, with all three of the screen band's redundant carriers kept:
         * an accent bar, a tint, and a drawn glyph. The tint is far lighter than the screen's
         * because a 12% fill that reads as tinted glass on near-black reads as a solid block of
         * colour on white and costs real ink to print.
         */
        private fun drawBand(band: ReportBand) {
            val colour = band.tone.print
            val lines = wrap(band.body, Paints.body, CONTENT_W - 2 * PANEL_PAD - 12f)
            val height = PANEL_PAD * 2 + 12f + lines.size * BODY_LEADING
            space(height + SECTION)
            Paints.fill.color = band.tone.printFill
            canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + height, Paints.fill)
            Paints.fill.color = colour
            canvas.drawRect(MARGIN, y, MARGIN + ACCENT_BAR, y + height, Paints.fill)
            Paints.hairline.color = colour
            canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + height, Paints.hairline)
            Paints.hairline.color = HAIRLINE

            val x = MARGIN + PANEL_PAD + 6f
            var ty = y + PANEL_PAD + 7f
            drawGlyph(canvas, band.tone.glyph, x, ty - 3f, 8f, colour)
            Paints.label.color = colour
            canvas.drawText(band.title.uppercase(), x + 14f, ty, Paints.label)
            Paints.label.color = MUTED
            ty += 14f
            for (line in lines) {
                canvas.drawText(line, x, ty, Paints.body)
                ty += BODY_LEADING
            }
            y += height + SECTION
        }

        /**
         * The one figure that owns the page. Label above, numeral and unit on one baseline, the
         * provenance caption under it, exactly the `HeroReadout` stack.
         */
        private fun drawHero(hero: ReportHero) {
            space(84f)
            canvas.drawText(hero.label.uppercase(), MARGIN, y + 8f, Paints.label)
            y += 22f
            Paints.hero.color = hero.category?.print ?: BODY
            val baseline = y + 34f
            canvas.drawText(hero.value, MARGIN, baseline, Paints.hero)
            if (hero.unit != null) {
                canvas.drawText(
                    hero.unit,
                    MARGIN + Paints.hero.measureText(hero.value) + 7f,
                    baseline,
                    Paints.heroUnit,
                )
            }
            y = baseline + 13f
            canvas.drawText(hero.caption, MARGIN, y, Paints.mono)
            y += SECTION + 4f
        }

        /** One stored code: accent bar, the code itself leading, the set it came from, the meaning. */
        private fun drawCode(code: ReportCode) {
            val colour = code.tone.print
            val lines = wrap(code.meaning, Paints.body, CONTENT_W - 2 * PANEL_PAD - 12f)
            val height = PANEL_PAD * 2 + 12f + lines.size * BODY_LEADING
            space(height + 8f)
            Paints.fill.color = colour
            canvas.drawRect(MARGIN, y, MARGIN + ACCENT_BAR, y + height, Paints.fill)
            Paints.hairline.color = HAIRLINE
            canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + height, Paints.hairline)

            val x = MARGIN + PANEL_PAD + 6f
            var ty = y + PANEL_PAD + 8f
            drawGlyph(canvas, code.tone.glyph, x, ty - 3f, 8f, colour)
            // The code leads and the meaning follows: the code is what gets typed into a search,
            // quoted to a mechanic or matched against a service bulletin.
            Paints.rowValue.color = colour
            canvas.drawText(code.code, x + 14f, ty, Paints.rowValue)
            Paints.rowValue.color = INK
            val chip = code.group.uppercase()
            Paints.label.color = colour
            canvas.drawText(
                chip,
                MARGIN + CONTENT_W - PANEL_PAD - Paints.label.measureText(chip),
                ty,
                Paints.label,
            )
            Paints.label.color = MUTED
            ty += 14f
            Paints.body.color = if (code.known || code.suspect) BODY else MUTED
            for (line in lines) {
                canvas.drawText(line, x, ty, Paints.body)
                ty += BODY_LEADING
            }
            Paints.body.color = BODY
            y += height + 8f
        }

        /**
         * The profile tiles, wrapping at three per row and splitting four as 2+2 rather than 3+1,
         * the same rule the screen uses and for the same reason: a short final row that stretches
         * reads as a second hero.
         */
        private fun drawTiles(tiles: List<ReportTile>) {
            val perRow = when {
                tiles.size <= 3 -> tiles.size
                tiles.size == 4 -> 2
                else -> 3
            }
            val gap = 10f
            val width = (CONTENT_W - gap * (perRow - 1)) / perRow
            for (row in tiles.chunked(perRow)) {
                space(TILE_H + gap)
                row.forEachIndexed { i, tile ->
                    val x = MARGIN + i * (width + gap)
                    Paints.fill.color = PANEL
                    canvas.drawRect(x, y, x + width, y + TILE_H, Paints.fill)
                    canvas.drawRect(x, y, x + width, y + TILE_H, Paints.hairline)
                    canvas.drawText(tile.label.uppercase(), x + 10f, y + 16f, Paints.label)
                    Paints.tileValue.color = tile.category.print
                    val baseline = y + 36f
                    canvas.drawText(tile.value, x + 10f, baseline, Paints.tileValue)
                    if (tile.unit != null) {
                        canvas.drawText(
                            tile.unit,
                            x + 10f + Paints.tileValue.measureText(tile.value) + 4f,
                            baseline,
                            Paints.unit,
                        )
                    }
                }
                y += TILE_H + gap
            }
            y -= gap
        }

        private fun drawFlag(flag: String) {
            val lines = wrap(flag, Paints.body, CONTENT_W - PANEL_PAD - 20f)
            val height = 12f + lines.size * BODY_LEADING
            space(height + 6f)
            Paints.fill.color = ReportTone.CAUTION.print
            canvas.drawRect(MARGIN, y, MARGIN + ACCENT_BAR, y + height, Paints.fill)
            var ty = y + 12f
            drawGlyph(canvas, ReportTone.CAUTION.glyph, MARGIN + 10f, ty - 3f, 8f, ReportTone.CAUTION.print)
            for ((i, line) in lines.withIndex()) {
                canvas.drawText(line, MARGIN + 24f, ty + i * BODY_LEADING, Paints.body)
            }
            y += height + 6f
        }

        private fun drawBraking(braking: ReportBraking) {
            sectionLabel("Braking")
            dataRow("Braking events", braking.eventCount.toString(), INK, MARGIN, CONTENT_W)
            braking.fuelEquivMl?.let {
                dataRow(
                    "Est. fuel to brake heat",
                    "%.0f mL".format(it),
                    ReportCategory.MIXTURE.print,
                    MARGIN,
                    CONTENT_W,
                )
            }
            braking.eventsWithoutCoast?.takeIf { it > 0 }?.let {
                space(BODY_LEADING)
                Paints.body.color = ReportTone.CAUTION.print
                y += paragraph(
                    "$it of ${braking.eventCount} had no coast phase first, speed was carried " +
                        "right up to the brakes.",
                    Paints.body,
                    MARGIN,
                    CONTENT_W,
                )
                Paints.body.color = BODY
            }
            caption(braking.caption)
            y += SECTION
        }

        /**
         * The capture block. [CaptureItem.Disclosure] is what the screen hides behind a tap; here
         * it renders inline, indented and hairline-marked so it reads as subordinate to the verdict
         * above it rather than as more of the report. See its doc comment for why a page gets the
         * counts a screen does not.
         */
        private fun drawCaptureItems(items: List<CaptureItem>, x: Float, width: Float) {
            for (item in items) {
                when (item) {
                    is CaptureItem.Stage -> {
                        space(ROW_H)
                        val colour = item.tone.print
                        drawGlyph(canvas, item.tone.glyph, x, y + 5f, 7f, colour)
                        canvas.drawText(item.label, x + 14f, y + 9f, Paints.body)
                        val state = item.state.uppercase()
                        Paints.label.color = colour
                        canvas.drawText(
                            state,
                            x + width - Paints.label.measureText(state),
                            y + 9f,
                            Paints.label,
                        )
                        Paints.label.color = MUTED
                        y += ROW_H
                        if (!item.detail.isNullOrBlank()) {
                            y += paragraph(item.detail, Paints.mono, x + 14f, width - 14f)
                            y += 2f
                        }
                    }

                    is CaptureItem.Line -> {
                        space(ROW_H)
                        val colour = item.tone?.print ?: item.emphasis.print
                        var textX = x
                        if (item.tone != null) {
                            drawGlyph(canvas, item.tone.glyph, x, y + 5f, 7f, colour)
                            textX += 14f
                        }
                        canvas.drawText(item.label, textX, y + 9f, Paints.body)
                        Paints.rowValue.color = colour
                        canvas.drawText(
                            item.value,
                            x + width - Paints.rowValue.measureText(item.value),
                            y + 9f,
                            Paints.rowValue,
                        )
                        Paints.rowValue.color = INK
                        y += ROW_H
                    }

                    is CaptureItem.Note -> {
                        space(MONO_LEADING)
                        y += paragraph(item.text, Paints.mono, x, width)
                        y += 3f
                    }

                    is CaptureItem.Disclosure -> {
                        y += 4f
                        space(ROW_H)
                        canvas.drawText(item.label.uppercase(), x + INDENT, y + 8f, Paints.label)
                        y += 14f
                        val top = y
                        drawCaptureItems(item.items, x + INDENT, width - INDENT)
                        canvas.drawLine(x + 4f, top - 12f, x + 4f, y, Paints.hairline)
                        y += 4f
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // Primitives
        // -------------------------------------------------------------------

        private fun rule() {
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paints.hairline)
        }

        private fun sectionLabel(text: String) {
            space(20f)
            canvas.drawText(text.uppercase(), MARGIN, y + 8f, Paints.label)
            y += 16f
        }

        private fun caption(text: String) {
            space(MONO_LEADING)
            y += 2f
            y += paragraph(text, Paints.mono, MARGIN, CONTENT_W)
        }

        private fun dataRow(label: String, value: String, colour: Int, x: Float, width: Float) {
            space(ROW_H)
            canvas.drawText(label, x, y + 9f, Paints.body)
            Paints.rowValue.color = colour
            canvas.drawText(value, x + width - Paints.rowValue.measureText(value), y + 9f, Paints.rowValue)
            Paints.rowValue.color = INK
            y += ROW_H
        }

        /**
         * Draws wrapped text from the current [y] and returns the height it consumed. Breaks the
         * page itself if the whole block will not fit, so a caveat is never half-printed.
         */
        private fun paragraph(text: String, paint: Paint, x: Float, width: Float): Float {
            val leading = if (paint === Paints.mono || paint === Paints.footer) {
                MONO_LEADING
            } else {
                BODY_LEADING
            }
            val lines = wrap(text, paint, width)
            space(lines.size * leading)
            var ty = y + leading - 3f
            for (line in lines) {
                canvas.drawText(line, x, ty, paint)
                ty += leading
            }
            return lines.size * leading
        }
    }
}

// ---------------------------------------------------------------------------
// Geometry. Points, because a PDF point is 1/72 inch and the page is US Letter.
// ---------------------------------------------------------------------------

private const val PAGE_W = 612f
private const val PAGE_H = 792f
private const val MARGIN = 54f
private const val CONTENT_W = PAGE_W - 2 * MARGIN
private const val SECTION = 16f
private const val PANEL_PAD = 10f
private const val ACCENT_BAR = 3f
private const val INDENT = 14f
private const val TILE_H = 46f
private const val ROW_H = 15f
private const val BODY_LEADING = 12f
private const val MONO_LEADING = 11f

/** Room kept clear at the foot of every page for the rule and the generated-on line. */
private const val FOOTER_RESERVE = 24f

// ---------------------------------------------------------------------------
// The print palette.
//
// Not the app's palette darkened by eye. Every value below clears 4.5:1 against white, which is
// the same floor the dark palette clears against Ink, and the category assignments are unchanged
// from `ui/theme/Skin.kt`: MOTION achromatic, MIXTURE teal, THERMAL blue. What changes is only
// which end of the luminance range each hue sits at, because the ground moved.
// ---------------------------------------------------------------------------

/** Near-black rather than #000000, for the same reason Ink is not #000000: pure contrast is loud. */
private const val INK = 0xFF14181C.toInt()
private const val BODY = 0xFF3A4148.toInt()
private const val MUTED = 0xFF5C646F.toInt()
private const val HAIRLINE = 0xFFD8DDE2.toInt()
private const val PANEL = 0xFFF6F7F9.toInt()

private const val PRINT_MIXTURE = 0xFF0F7A72.toInt()
private const val PRINT_THERMAL = 0xFF1E5FA8.toInt()
private const val PRINT_LIVE = 0xFF1F7A3D.toInt()
private const val PRINT_CAUTION = 0xFF9A6400.toInt()
private const val PRINT_FAULT = 0xFFB3261E.toInt()

private val ReportCategory.print: Int
    get() = when (this) {
        // Achromatic in every skin and on every medium; on white that means near-black, not grey.
        ReportCategory.MOTION -> INK
        ReportCategory.MIXTURE -> PRINT_MIXTURE
        ReportCategory.THERMAL -> PRINT_THERMAL
    }

private val ReportTone.print: Int
    get() = when (this) {
        ReportTone.NEUTRAL -> INK
        ReportTone.UNKNOWN -> MUTED
        ReportTone.LIVE -> PRINT_LIVE
        ReportTone.CAUTION -> PRINT_CAUTION
        ReportTone.FAULT -> PRINT_FAULT
    }

/** The band wash. Far lighter than the screen's 12% tint: ink on paper costs something. */
private val ReportTone.printFill: Int
    get() = when (this) {
        ReportTone.NEUTRAL, ReportTone.UNKNOWN -> PANEL
        ReportTone.LIVE -> 0xFFF0F7F2.toInt()
        ReportTone.CAUTION -> 0xFFFBF5EA.toInt()
        ReportTone.FAULT -> 0xFFFCF1F0.toInt()
    }

private val ReportEmphasis.print: Int
    get() = when (this) {
        ReportEmphasis.PRIMARY -> INK
        ReportEmphasis.SECONDARY -> BODY
        ReportEmphasis.ECONOMY -> PRINT_MIXTURE
    }

/** The tone's mark. Colour is never the sole carrier, and a photocopier eats colour first. */
private val ReportTone.glyph: PrintGlyph
    get() = when (this) {
        ReportTone.NEUTRAL -> PrintGlyph.DASH
        ReportTone.UNKNOWN -> PrintGlyph.DOTS
        ReportTone.LIVE -> PrintGlyph.TICK
        ReportTone.CAUTION -> PrintGlyph.BANG
        ReportTone.FAULT -> PrintGlyph.CROSS
    }

/**
 * The same five marks `ui/components/Instrument.kt` draws, drawn the same way and for the same
 * reason: five shapes are not worth a vector asset dependency, and stroking them onto the canvas
 * keeps them crisp at any zoom in a PDF viewer rather than rasterising an icon.
 */
private enum class PrintGlyph { TICK, BANG, CROSS, DASH, DOTS }

private fun drawGlyph(canvas: Canvas, glyph: PrintGlyph, x: Float, cy: Float, size: Float, colour: Int) {
    val p = Paints.glyph
    p.color = colour
    val r = size / 2f
    val cx = x + r
    when (glyph) {
        PrintGlyph.TICK -> {
            canvas.drawLine(cx - r, cy, cx - r * 0.2f, cy + r * 0.7f, p)
            canvas.drawLine(cx - r * 0.2f, cy + r * 0.7f, cx + r, cy - r * 0.8f, p)
        }
        PrintGlyph.BANG -> {
            canvas.drawLine(cx, cy - r, cx, cy + r * 0.25f, p)
            canvas.drawPoint(cx, cy + r * 0.85f, p)
        }
        PrintGlyph.CROSS -> {
            canvas.drawLine(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, p)
            canvas.drawLine(cx + r * 0.8f, cy - r * 0.8f, cx - r * 0.8f, cy + r * 0.8f, p)
        }
        PrintGlyph.DASH -> canvas.drawLine(cx - r, cy, cx + r, cy, p)
        PrintGlyph.DOTS -> {
            canvas.drawPoint(cx - r * 0.8f, cy, p)
            canvas.drawPoint(cx, cy, p)
            canvas.drawPoint(cx + r * 0.8f, cy, p)
        }
    }
}

// ---------------------------------------------------------------------------
// Paints. One set, reused across every page; the few that change colour per call set it back.
// ---------------------------------------------------------------------------

private object Paints {
    private const val TABULAR = "tnum"

    private fun paint(
        size: Float,
        colour: Int,
        typeface: Typeface,
        tracking: Float = 0f,
        tabular: Boolean = false,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        this.typeface = typeface
        letterSpacing = tracking
        if (tabular) fontFeatureSettings = TABULAR
    }

    private val sans: Typeface = Typeface.SANS_SERIF
    private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val monoFace: Typeface = Typeface.MONOSPACE

    /** The DRIVETRACE wordmark: caps, heavily tracked, exactly as in the app's header bar. */
    val wordmark = paint(10f, INK, sansBold, tracking = 0.28f)

    /** The bezel legend. Identifiable without being readable, so it can shrink under body size. */
    val label = paint(7.5f, MUTED, sansBold, tracking = 0.12f)

    val title = paint(15f, INK, sansMedium, tabular = true)

    /** 46pt rather than the screen's 64sp: a page is read at arm's length, not glanced at. */
    val hero = paint(46f, INK, sansLight, tracking = -0.02f, tabular = true)
    val heroUnit = paint(11f, MUTED, sansMedium)

    val tileValue = paint(17f, INK, sansMedium, tabular = true)
    val unit = paint(8.5f, MUTED, sansMedium)

    val body = paint(9.5f, BODY, sans, tabular = true)
    val rowValue = paint(10f, INK, sansMedium, tabular = true)
    val mono = paint(8f, MUTED, monoFace, tabular = true)
    val footer = paint(7.5f, MUTED, monoFace)

    val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
        color = HAIRLINE
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        strokeCap = Paint.Cap.ROUND
    }
}

/**
 * Greedy word wrap against the paint's own measurements.
 *
 * `StaticLayout` would do this properly, including hyphenation and bidi, and is the right answer
 * for arbitrary user text. Every string on this page is either app-authored prose or a PID tag,
 * and the one piece of user text (the drive note) is capped at 120 characters by `NoteField`, so
 * the extra API surface buys nothing here. A single word longer than the column is left to
 * overhang rather than being chopped mid-token: a truncated PID tag is worse than a wide one.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val out = mutableListOf<String>()
    for (hardLine in text.split('\n')) {
        var current = StringBuilder()
        for (word in hardLine.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                out.add(current.toString())
                current = StringBuilder(word)
            }
        }
        out.add(current.toString())
    }
    return out
}
