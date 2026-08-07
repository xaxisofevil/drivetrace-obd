package com.ericbarone.drivetrace.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
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
 *
 * **Dark-only is not the same as single-skin.** Both shipped skins are negative-polarity; what a
 * skin picks is which near-black the ground is and which hues the categories get. See [SkinId].
 */
private fun colorScheme(skin: Skin): ColorScheme = darkColorScheme(
    // Primary is the mixture accent: fuel trim is the signal this app was built to chase, so
    // the brand colour and the flagship data category are the same colour on purpose.
    primary = skin.accentMixture,
    onPrimary = skin.ink,
    primaryContainer = skin.panelActive,
    onPrimaryContainer = skin.accentMixture,

    secondary = skin.accentThermal,
    onSecondary = skin.ink,
    secondaryContainer = skin.panelActive,
    onSecondaryContainer = skin.accentThermal,

    tertiary = skin.accentAirpath,
    onTertiary = skin.ink,
    tertiaryContainer = skin.panelActive,
    onTertiaryContainer = skin.accentAirpath,

    background = skin.ink,
    onBackground = skin.chalk,

    surface = skin.panel,
    onSurface = skin.chalk,
    surfaceVariant = skin.panelRaised,
    onSurfaceVariant = skin.mist,
    surfaceContainer = skin.panel,
    surfaceContainerHigh = skin.panelRaised,
    surfaceContainerHighest = skin.panelActive,
    surfaceContainerLow = skin.panel,
    surfaceContainerLowest = skin.ink,

    error = skin.statusFault,
    onError = skin.ink,
    errorContainer = skin.statusFaultFill,
    onErrorContainer = skin.statusFault,

    outline = skin.hairline,
    outlineVariant = skin.hairline,

    inverseSurface = skin.chalk,
    inverseOnSurface = skin.ink,
    scrim = skin.ink,
)

/**
 * [skinId] selects the instrument skin (see [SkinId]); [highContrast] selects the daylight
 * readout boost within it. Both are parameters rather than preferences this function reads for
 * itself, so the theme stays a pure function of its inputs and ui.theme keeps no dependency back
 * on the screens above it; the caller (MainActivity) collects both from `ui.DisplaySettings`.
 *
 * The two are orthogonal on purpose. The skin decides what the palette is; the daylight toggle
 * decides how much luminance the hero spends out of whatever palette is active. Nothing about the
 * boost is written per skin: [Skin.readoutPalette] derives it, so a third skin gets a working
 * direct-sun mode by declaring five daylight twins and no code at all.
 */
@Composable
fun DriveTraceTheme(
    skinId: SkinId = SkinId.INSTRUMENT,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val skin = skinId.skin
    val colors = remember(skin) { colorScheme(skin) }
    val readoutPalette = remember(skin, highContrast) { skin.readoutPalette(highContrast) }

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
            // res/values/themes.xml pins the pre-Compose window background to @color/ink, which
            // is the Instrument skin's ground and cannot follow a runtime setting. On the Amber
            // skin that leaves a cool near-black behind the warm one, visible under the ripple
            // of a dialog and for the frame before Compose draws on a cold start. One assignment
            // fixes it, and it belongs here rather than in MainActivity because this function is
            // already the only thing that knows which skin won.
            window.setBackgroundDrawable(ColorDrawable(skin.ink.toArgb()))
        }
    }

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalReadoutType provides ReadoutTypography(),
        LocalReadoutPalette provides readoutPalette,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = DriveTraceTypography,
            shapes = DriveTraceMaterialShapes,
            content = content,
        )
    }
}
