package com.ericbarone.drivetrace.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape language. See docs/DESIGN_SYSTEM.md.
 *
 * Instruments are rectilinear. A gauge bezel, a switch panel, a DIN head unit: all shallow
 * radii on a rectangle. Material 3's defaults run the other way, toward 16-28dp cards and fully
 * pill-shaped buttons, which is what makes stock M3 read as a consumer phone app no matter what
 * colours it wears. The radii here are deliberately tighter than the M3 baseline, and buttons
 * are explicitly not pills.
 */
object DriveTraceShapes {
    /** Chips, status dots' containers, small badges. */
    val chip = RoundedCornerShape(6.dp)

    /** Buttons and inputs. Not a pill: equipment, not chat. */
    val control = RoundedCornerShape(10.dp)

    /** Metric tiles nested inside a panel. */
    val tile = RoundedCornerShape(10.dp)

    /** Top-level panels. */
    val panel = RoundedCornerShape(14.dp)

    /** Dialogs and the largest containers. */
    val dialog = RoundedCornerShape(18.dp)

    /** The status accent bar down the left edge of a panel. Rounded only on the outer side so
     *  it reads as painted onto the panel rather than floating in front of it. */
    val accentBar = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 0.dp, bottomEnd = 0.dp)
}

/** Material 3 slots, so stock components inherit the same language. */
val DriveTraceMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = DriveTraceShapes.chip,
    medium = DriveTraceShapes.control,
    large = DriveTraceShapes.panel,
    extraLarge = DriveTraceShapes.dialog,
)
