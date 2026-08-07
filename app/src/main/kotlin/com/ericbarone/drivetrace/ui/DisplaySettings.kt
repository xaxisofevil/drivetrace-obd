package com.ericbarone.drivetrace.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's SharedPreferences keys, in one place rather than duplicated privately in whichever
 * screen happens to read them. Same store the adapter and vehicle choices have always used.
 */
const val PREFS_NAME = "drivetrace_prefs"
const val PREF_LAST_DEVICE = "last_device_address"
const val PREF_VEHICLE_PROFILE = "vehicle_profile"

/** See [DisplaySettings.highContrast]. Off by default: the standard palette is the right one in
 *  the conditions this app was designed around (see docs/DESIGN_SYSTEM.md section 2). */
const val PREF_HIGH_CONTRAST = "high_contrast_daylight"

/**
 * Display preferences, held as a process-wide StateFlow for the same reason [
 * com.ericbarone.drivetrace.service.LoggingStatus] is one: the toggle lives on SetupScreen but
 * the value is consumed at the theme root above every screen, and threading a callback pair down
 * through three composables to move one boolean back up is more machinery than this deserves.
 *
 * SharedPreferences is still the durable copy; the flow only exists so a toggle recomposes the
 * running UI instead of taking effect on next launch.
 */
object DisplaySettings {
    private val _highContrast = MutableStateFlow(false)

    /**
     * Daylight readout boost. Not a light theme: the background stays [
     * com.ericbarone.drivetrace.ui.theme.Ink] and only the hero readout's ink gets brighter, see
     * ReadoutPalette in ui/theme/Color.kt.
     */
    val highContrast: StateFlow<Boolean> = _highContrast

    /** Call once at process start, before the first composition, so the first frame is already
     *  in the right mode rather than flashing the standard palette and correcting itself. */
    fun load(context: Context) {
        _highContrast.value = prefs(context).getBoolean(PREF_HIGH_CONTRAST, false)
    }

    fun setHighContrast(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_HIGH_CONTRAST, enabled).apply()
        _highContrast.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
