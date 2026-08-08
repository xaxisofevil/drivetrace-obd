package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericbarone.drivetrace.ui.components.Glyph
import com.ericbarone.drivetrace.ui.components.GlyphMark
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.DriveTraceShapes
import com.ericbarone.drivetrace.ui.theme.Hairline
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.Panel
import com.ericbarone.drivetrace.ui.theme.PanelActive
import com.ericbarone.drivetrace.ui.theme.Slate
import com.ericbarone.drivetrace.ui.theme.Space

/**
 * The two row shapes a config screen is made of, and the two marks that go in them. They live
 * here rather than in whichever screen happened to need them first because both screens that ask
 * the user to choose something now use them: Setup picks a vehicle and an adapter, Settings picks
 * a skin and flips the daylight boost. They are deliberately *not* in
 * `ui/components/Instrument.kt`: they are a composition of `InstrumentPanel` and `Text` specific
 * to configuration screens, not a new container type, and section 6 of the design doc is a list
 * of container types.
 */

/**
 * One choice in a config section. Replaces the stock RadioButton row: the whole panel is the
 * target (not a 20dp circle), selection is carried by three signals at once (accent bar, border,
 * fill) so it survives a glance, and `role = Role.RadioButton` keeps the single-choice semantics
 * TalkBack needs now that the RadioButton widget itself is gone.
 */
@Composable
internal fun SelectableRow(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    detailIsMachine: Boolean = false,
    flagged: Boolean = false,
) {
    val type = LocalReadoutType.current
    InstrumentPanel(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        accent = if (selected) AccentMixture else null,
        fill = if (selected) PanelActive else Panel,
        border = if (selected) AccentMixture.copy(alpha = 0.45f) else Hairline,
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionMark(selected)
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = type.small,
                    color = if (selected) Chalk else Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = if (detailIsMachine) type.mono else type.unit,
                    color = Slate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (flagged && !selected) {
                Text("LIKELY", style = type.label, color = Ash)
            }
        }
    }
}

/**
 * A binary setting. Same panel-is-the-target treatment as [SelectableRow] and the same three
 * redundant selection signals, with `Role.Switch` instead of `Role.RadioButton` so TalkBack
 * announces it as a toggle. Deliberately not a Material `Switch`: an M3 switch is a 52x32dp pill
 * with a sliding thumb, which is the one shape this design system has ruled out everywhere else.
 */
@Composable
internal fun ToggleRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val type = LocalReadoutType.current
    InstrumentPanel(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
        accent = if (checked) AccentMixture else null,
        fill = if (checked) PanelActive else Panel,
        border = if (checked) AccentMixture.copy(alpha = 0.45f) else Hairline,
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckMark(checked)
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = type.small,
                    color = if (checked) Chalk else Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail, style = type.unit, color = Slate, maxLines = 2)
            }
        }
    }
}

/** On/off indicator. Square with the chip radius, so it can never be confused with the round
 *  single-choice [SelectionMark] a section above it. */
@Composable
private fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(DriveTraceShapes.chip)
            .background(if (checked) AccentMixture else Color.Transparent)
            .border(1.5.dp, if (checked) AccentMixture else Hairline, DriveTraceShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) GlyphMark(Glyph.TICK, Ink, sizeDp = 12)
    }
}

/** Selection indicator, drawn rather than borrowed from Material, so its ring weight matches the
 *  1dp hairline language the panels use. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val ring: Color = if (selected) AccentMixture else Hairline
    val core: Color = if (selected) AccentMixture else Color.Transparent
    Box(modifier = modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ring, radius = size.minDimension / 2f - 1f, center = c, style = Stroke(width = 1.5.dp.toPx()))
            if (core != Color.Transparent) {
                drawCircle(core, radius = size.minDimension * 0.22f, center = c)
            }
        }
    }
}
