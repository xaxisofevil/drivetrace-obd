package com.ericbarone.drivetrace.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * DriveTrace's theme. See docs/DESIGN_SYSTEM.md for the full rationale.
 *
 * Dark only, on purpose, and not following the system setting. This is a display that gets
 * glanced at from a phone mount while driving, frequently at night. A light surface at
 * cabin-forward brightness is a lamp pointed at the driver and wrecks dark adaptation; every
 * OEM cluster, every glass cockpit (G1000, 787), and every serious OBD tool defaults to a
 * negative-polarity display for exactly that reason. Offering a light mode here would mean
 * shipping a mode that is worse at the job in the conditions the app is used in.
 */
private val DriveTraceColorScheme = darkColorScheme(
    // Primary is the mixture accent: fuel trim is the signal this app was built to chase, so
    // the brand colour and the flagship data category are the same colour on purpose.
    primary = AccentMixture,
    onPrimary = Ink,
    primaryContainer = PanelActive,
    onPrimaryContainer = AccentMixture,

    secondary = AccentThermal,
    onSecondary = Ink,
    secondaryContainer = PanelActive,
    onSecondaryContainer = AccentThermal,

    tertiary = AccentAirpath,
    onTertiary = Ink,
    tertiaryContainer = PanelActive,
    onTertiaryContainer = AccentAirpath,

    background = Ink,
    onBackground = Chalk,

    surface = Panel,
    onSurface = Chalk,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Mist,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelRaised,
    surfaceContainerHighest = PanelActive,
    surfaceContainerLow = Panel,
    surfaceContainerLowest = Ink,

    error = StatusFault,
    onError = Ink,
    errorContainer = StatusFaultFill,
    onErrorContainer = StatusFault,

    outline = Hairline,
    outlineVariant = Hairline,

    inverseSurface = Chalk,
    inverseOnSurface = Ink,
    scrim = Ink,
)

/**
 * [highContrast] selects the daylight readout boost (see [DaylightReadoutPalette]). It is a
 * parameter rather than a preference this function reads for itself, so the theme stays a pure
 * function of its inputs and ui.theme keeps no dependency back on the screens above it; the
 * caller (MainActivity) collects the value from `ui.DisplaySettings`.
 */
@Composable
fun DriveTraceTheme(highContrast: Boolean = false, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Light icons in both bars: the app is always dark, so the system bars always sit
            // on a dark ground. Without this the status bar icons inherit whatever the launcher
            // theme left behind and can render dark-on-dark.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalReadoutType provides ReadoutTypography(),
        LocalReadoutPalette provides
            if (highContrast) DaylightReadoutPalette else StandardReadoutPalette,
    ) {
        MaterialTheme(
            colorScheme = DriveTraceColorScheme,
            typography = DriveTraceTypography,
            shapes = DriveTraceMaterialShapes,
            content = content,
        )
    }
}
