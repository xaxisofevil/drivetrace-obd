package com.ericbarone.drivetrace.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing tokens. See docs/DESIGN_SYSTEM.md.
 *
 * Everything is a multiple of 4dp. The only values worth arguing about are the touch targets:
 * [Space.touchTarget] is 56dp rather than Material's 48dp minimum, because this app gets used in
 * a moving vehicle where the hand is unbraced and the target is moving relative to the finger.
 * Fitts's Law says acquisition time falls with target size; a vibrating cabin effectively shrinks
 * the target, so it has to start bigger. NHTSA's visual-manual distraction guidelines cap a
 * single glance at roughly two seconds, and a missed tap costs a whole extra glance.
 */
object Space {
    /** Base grid unit. */
    val unit = 4.dp

    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 40.dp

    /** Left/right screen gutter. */
    val gutter = 20.dp

    /** Padding inside a panel. */
    val panelPadding = 16.dp

    /** Gap between metric tiles in a row. */
    val tileGap = 10.dp

    /** Gap between major sections of a screen. */
    val section = 20.dp

    /** Minimum height of anything tappable. */
    val touchTarget = 56.dp

    /** Height of a compact secondary action (back, retry). */
    val compactTarget = 44.dp

    /** Border width for panels and bezels. */
    val hairline = 1.dp

    /** Width of the status accent bar on the left edge of a panel. */
    val accentBar = 3.dp
}
