package com.ericbarone.drivetrace.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.AccentMotion
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.DriveTraceShapes
import com.ericbarone.drivetrace.ui.theme.Hairline
import com.ericbarone.drivetrace.ui.theme.HairlineBright
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutPalette
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.Panel
import com.ericbarone.drivetrace.ui.theme.PanelActive
import com.ericbarone.drivetrace.ui.theme.PanelRaised
import com.ericbarone.drivetrace.ui.theme.Slate
import com.ericbarone.drivetrace.ui.theme.Space
import com.ericbarone.drivetrace.ui.theme.StatusCaution
import com.ericbarone.drivetrace.ui.theme.StatusCautionFill
import com.ericbarone.drivetrace.ui.theme.StatusFault
import com.ericbarone.drivetrace.ui.theme.StatusFaultFill
import com.ericbarone.drivetrace.ui.theme.StatusLive
import com.ericbarone.drivetrace.ui.theme.StatusLiveFill

/**
 * The DriveTrace component vocabulary. Every screen is assembled from these; nothing below is
 * screen-specific. Adding a new screen means composing these, not inventing new containers.
 * See docs/DESIGN_SYSTEM.md for what each one is for and why it looks the way it does.
 */

// ---------------------------------------------------------------------------
// Status tone
// ---------------------------------------------------------------------------

/**
 * The status channel, kept strictly separate from the category accents (see Color.kt). Every
 * tone carries a glyph as well as a colour, so the state survives colour blindness, a sun-washed
 * screen, and a greyscale screenshot. Colour alone is never the carrier.
 */
enum class Tone(val color: Color, val fill: Color, val glyph: Glyph) {
    /** Nothing to report. Achromatic on purpose: normal state should not compete for attention. */
    NEUTRAL(Chalk, Color.Transparent, Glyph.DASH),

    /** Still resolving. */
    UNKNOWN(Slate, Color.Transparent, Glyph.DOTS),

    /** Confirmed good. Used sparingly, never as a general "fine" wash. */
    LIVE(StatusLive, StatusLiveFill, Glyph.TICK),

    /** Degraded but working. */
    CAUTION(StatusCaution, StatusCautionFill, Glyph.BANG),

    /** Broken. */
    FAULT(StatusFault, StatusFaultFill, Glyph.CROSS),
}

enum class Glyph { TICK, BANG, CROSS, DASH, DOTS, CHEVRON_LEFT }

/**
 * Glyphs are drawn rather than pulled from a font. The set needed is five shapes; adding the
 * Material icon artifact for that would cost an extra dependency and a few hundred KB of vector
 * assets to use six of them.
 */
@Composable
fun GlyphMark(glyph: Glyph, color: Color, modifier: Modifier = Modifier, sizeDp: Int = 14) {
    Canvas(modifier = modifier.size(sizeDp.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.14f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.TICK -> {
                drawLine(color, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.42f, h * 0.76f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.26f), stroke.width, StrokeCap.Round)
            }
            Glyph.BANG -> {
                drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.60f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.83f), Offset(w * 0.5f, h * 0.85f), stroke.width, StrokeCap.Round)
            }
            Glyph.CROSS -> {
                drawLine(color, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), stroke.width, StrokeCap.Round)
            }
            Glyph.DASH -> {
                drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.DOTS -> {
                val r = w * 0.075f
                drawCircle(color, r, Offset(w * 0.24f, h * 0.5f))
                drawCircle(color, r, Offset(w * 0.5f, h * 0.5f))
                drawCircle(color, r, Offset(w * 0.76f, h * 0.5f))
            }
            Glyph.CHEVRON_LEFT -> {
                drawLine(color, Offset(w * 0.62f, h * 0.18f), Offset(w * 0.34f, h * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.5f), Offset(w * 0.62f, h * 0.82f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/**
 * The heartbeat. A slow alpha pulse, roughly one cycle a second, is the one animation in the app:
 * it says "data is still arriving" without occupying any words, and motion is the strongest
 * pre-attentive cue there is, so it needs to be the only thing moving to stay meaningful.
 */
@Composable
fun StatusDot(tone: Tone, pulsing: Boolean = false, sizeDp: Int = 8, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val v by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        v
    } else {
        1f
    }
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(tone.color.copy(alpha = alpha)),
    )
}

// ---------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------

/**
 * A panel. Bordered with a 1dp hairline instead of a drop shadow: on a near-black ground a
 * Material elevation shadow is invisible, whereas a bright edge is exactly how a bezel or a
 * switch-panel cutout reads in the physical objects this borrows from.
 *
 * [accent] paints a bar down the left edge, which is how a panel declares its category or its
 * status without spending a whole row on a label.
 */
@Composable
fun InstrumentPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    fill: Color = Panel,
    border: Color = Hairline,
    contentPadding: PaddingValues = PaddingValues(Space.panelPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(DriveTraceShapes.panel)
            .background(fill)
            .border(Space.hairline, border, DriveTraceShapes.panel)
            .height(IntrinsicSize.Min),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(Space.accentBar)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A section legend. The rule running off to the right is borrowed from engineering drawings and
 * from the printed legends on a switch panel: it binds the label to the full width of what it
 * names, so a scan down the page finds section boundaries without needing whitespace to do it.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Ash) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(text.uppercase(), style = LocalReadoutType.current.label, color = color)
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = Space.hairline, color = Hairline)
    }
}

/**
 * The screen header. Deliberately not a Material TopAppBar: the M3 bar's 64dp of chrome, its
 * centred-or-left title debate and its overflow-menu affordance all exist to serve navigation
 * this app does not have (three screens, one of them modal). What is left is a wordmark, an
 * optional back affordance, and one optional action, over a hairline rule.
 */
@Composable
fun HeaderBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Space.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(Space.compactTarget)
                        .clip(DriveTraceShapes.control)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphMark(Glyph.CHEVRON_LEFT, Mist, sizeDp = 18)
                }
                Spacer(Modifier.width(Space.sm))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = LocalReadoutType.current.wordmark,
                    color = Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = LocalReadoutType.current.unit, color = Ash, maxLines = 1)
                }
            }
            trailing?.invoke(this)
        }
        HorizontalDivider(thickness = Space.hairline, color = Hairline)
    }
}

// ---------------------------------------------------------------------------
// Readouts
// ---------------------------------------------------------------------------

/**
 * The number that owns the screen. One per screen state, no exceptions: a hierarchy with two
 * heroes has no hierarchy. The unit is a separate Text at a fraction of the size, so the numeral
 * keeps a stable left edge and a stable baseline no matter what the unit string is, and the eye
 * lands on the digits rather than on "MPG".
 */
@Composable
fun HeroReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = AccentMotion,
    caption: String? = null,
) {
    val type = LocalReadoutType.current
    // The one place the daylight boost applies. Routing the accent through the palette rather
    // than branching on a flag here is what keeps "high contrast" from becoming a second copy of
    // every screen: the theme swaps one CompositionLocal and this composable is the only reader.
    val palette = LocalReadoutPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = type.label, color = palette.heroLabel)
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = type.hero, color = palette.hero(accent), maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(Space.sm))
                Text(
                    unit,
                    style = type.unit,
                    color = palette.heroLabel,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(Space.xs))
            Text(caption, style = type.mono, color = palette.heroCaption)
        }
    }
}

/**
 * A tile in the secondary band. Roughly a third the visual weight of the hero, which is what
 * makes the hero a hero. [tone] tints only the value, never the whole tile, so a tile going
 * amber is a legible change rather than a redecoration.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = AccentMotion,
    tone: Tone? = null,
) {
    val type = LocalReadoutType.current
    val valueColor = tone?.color ?: accent
    Column(
        modifier = modifier
            .clip(DriveTraceShapes.tile)
            .background(PanelRaised)
            .border(Space.hairline, Hairline, DriveTraceShapes.tile)
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (tone != null && tone != Tone.NEUTRAL) {
                GlyphMark(tone.glyph, tone.color, sizeDp = 10)
            }
            Text(
                label.uppercase(),
                style = type.label,
                color = Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = type.medium, color = valueColor, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = type.unit, color = Ash, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/**
 * A dense label/value line for data that is worth showing but not worth a tile. This is where
 * Tier C housekeeping belongs: present, findable, and visually subordinate to everything above it.
 */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Chalk,
    leadingGlyph: Glyph? = null,
    glyphColor: Color = Ash,
) {
    val type = LocalReadoutType.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (leadingGlyph != null) {
            GlyphMark(leadingGlyph, glyphColor, sizeDp = 12)
        }
        Text(
            label,
            style = type.unit,
            color = Ash,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = type.small, color = valueColor, maxLines = 1)
    }
}

/**
 * A named pipeline stage with a state: upload, analysis. Dot plus word plus optional detail,
 * so the state is readable at a glance and diagnosable if it went wrong.
 */
@Composable
fun StatusRow(
    label: String,
    state: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    detail: String? = null,
    pulsing: Boolean = false,
) {
    val type = LocalReadoutType.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            StatusDot(tone, pulsing = pulsing)
            Text(label, style = type.unit, color = Mist, modifier = Modifier.weight(1f))
            Text(state.uppercase(), style = type.label, color = tone.color)
        }
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = type.mono,
                color = if (tone == Tone.FAULT) tone.color else Slate,
                modifier = Modifier.padding(start = 20.dp, top = Space.xs),
            )
        }
    }
}

/**
 * A full-width state band. Reserved for conditions the driver must act on, which is why it gets
 * a tinted fill, an accent bar and a glyph all at once: three redundant carriers for one message.
 * Overuse would destroy it, so exactly one thing on the live screen is allowed to be a band.
 */
@Composable
fun StatusBand(
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    val type = LocalReadoutType.current
    InstrumentPanel(
        modifier = modifier.fillMaxWidth(),
        accent = tone.color,
        fill = if (tone.fill == Color.Transparent) Panel else tone.fill,
        border = if (tone.fill == Color.Transparent) Hairline else tone.color.copy(alpha = 0.35f),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            GlyphMark(tone.glyph, tone.color, sizeDp = 16)
            Text(title.uppercase(), style = type.label, color = tone.color)
        }
        if (body != null) {
            Spacer(Modifier.height(Space.sm))
            Text(body, style = type.mono, color = Mist)
        }
    }
}

/** A compact state badge for list rows, where a full StatusRow would be too heavy. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(DriveTraceShapes.chip)
            .background(if (tone.fill == Color.Transparent) PanelRaised else tone.fill)
            .padding(horizontal = Space.sm, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        GlyphMark(tone.glyph, tone.color, sizeDp = 9)
        Text(text.uppercase(), style = LocalReadoutType.current.label, color = tone.color)
    }
}

/**
 * Machine output: the raw status string from the service, adapter addresses, session IDs.
 * Monospaced and dim, so it reads as a log the app is keeping rather than as a message the app
 * is giving you, and so it can sit at the bottom of a screen without pulling the eye.
 */
@Composable
fun ConsoleLine(text: String, modifier: Modifier = Modifier, color: Color = Slate) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(">", style = LocalReadoutType.current.mono, color = Hairline)
        Text(text, style = LocalReadoutType.current.mono, color = color)
    }
}

/**
 * The one text input in the app: a short free-text note about a drive, typed at Stop time.
 *
 * Material's own `OutlinedTextField` underneath rather than a hand-rolled `BasicTextField`,
 * because a text field is the one place where the platform behaviour (IME, selection handles,
 * autofill, TalkBack's editable semantics) is worth far more than pixel control over the frame.
 * Only the frame is restyled: control radius rather than M3's default, hairline border, raised
 * panel fill, and the same `unit` type as every other body value, so it reads as a slot on an
 * instrument panel rather than as a chat box.
 *
 * [maxLength] is a hard cap, not a hint. This exists to hold "cold start, highway, 93 octane",
 * not a paragraph; a long note would be unreadable on a logbook card anyway.
 */
@Composable
fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    maxLength: Int = 120,
) {
    val type = LocalReadoutType.current
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = DriveTraceShapes.control,
        textStyle = type.unit,
        placeholder = placeholder?.let { { Text(it, style = type.unit, color = Slate, maxLines = 1) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Chalk,
            unfocusedTextColor = Chalk,
            focusedContainerColor = PanelRaised,
            unfocusedContainerColor = PanelRaised,
            cursorColor = AccentMixture,
            focusedBorderColor = AccentMixture.copy(alpha = 0.65f),
            unfocusedBorderColor = Hairline,
        ),
    )
}

/** Small print: caveats, methodology notes, the "this is an estimate" disclaimers. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Slate) {
    Text(text, style = LocalReadoutType.current.mono, color = color, modifier = modifier)
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

/**
 * The one primary action on a screen. Full width and 56dp tall rather than Material's 40dp
 * default pill, because the hand aiming at it is unbraced in a moving vehicle and the target is
 * effectively smaller than its drawn size (Fitts's Law, with the cabin's vibration folded into
 * the effective target width).
 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = com.ericbarone.drivetrace.ui.theme.AccentMixture,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = DriveTraceShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Ink,
            disabledContainerColor = PanelActive,
            disabledContentColor = Slate,
        ),
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = Space.touchTarget),
    ) {
        Text(text.uppercase(), style = LocalReadoutType.current.label)
    }
}

/** The alternative action. Outlined, never coloured, so it can never be mistaken for primary. */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Mist,
    borderColor: Color = HairlineBright,
    minHeight: androidx.compose.ui.unit.Dp = Space.touchTarget,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = DriveTraceShapes.control,
        border = androidx.compose.foundation.BorderStroke(Space.hairline, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = Slate,
        ),
        modifier = modifier.defaultMinSize(minHeight = minHeight),
    ) {
        Text(text.uppercase(), style = LocalReadoutType.current.label)
    }
}

/**
 * A bar pinned to the bottom of a screen holding the primary action. The hairline along its top
 * edge is what makes it read as pinned chrome rather than as the last item in the scroll, which
 * matters because on both LoggingScreen and SetupScreen the content above it can be arbitrarily
 * long and the action must never scroll away.
 */
@Composable
fun ActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = Space.hairline, color = Hairline)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink)
                .padding(horizontal = Space.gutter, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
            content = content,
        )
    }
}

/** Nothing to show yet. Centred, quiet, and it says what to do next rather than only what is missing. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val type = LocalReadoutType.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(title.uppercase(), style = type.label, color = Ash)
        Text(body, style = type.mono, color = Slate)
    }
}
