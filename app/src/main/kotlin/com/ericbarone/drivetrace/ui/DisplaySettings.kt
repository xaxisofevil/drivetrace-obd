package com.ericbarone.drivetrace.ui

import android.content.Context
import com.ericbarone.drivetrace.ui.theme.SkinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's SharedPreferences keys, in one place rather than duplicated privately in whichever
 * screen happens to read them. Same store the adapter and vehicle choices have always used.
 */
const val PREFS_NAME = "drivetrace_prefs"
const val PREF_LAST_DEVICE = "last_device_address"
const val PREF_VEHICLE_PROFILE = "vehicle_profile"

/** The shared secret an automation app (MacroDroid, Tasker) has to present to
 *  [com.ericbarone.drivetrace.service.AutomationReceiver]. Generated on first read, never
 *  shipped in the APK; see docs/AUTOMATION.md. */
const val PREF_AUTOMATION_TOKEN = "automation_token"

/** See [DisplaySettings.highContrast]. Off by default: the standard palette is the right one in
 *  the conditions this app was designed around (see docs/DESIGN_SYSTEM.md section 2). */
const val PREF_HIGH_CONTRAST = "high_contrast_daylight"

/** See [DisplaySettings.skin]. Stored as the [SkinId] name, and an unrecognised or missing value
 *  falls back to the default skin rather than throwing, so a downgrade after a future skin ships
 *  degrades to Instrument instead of crashing on launch. */
const val PREF_SKIN = "instrument_skin"

/**
 * Display preferences, held as process-wide StateFlows for the same reason [
 * com.ericbarone.drivetrace.service.LoggingStatus] is one: the controls live on SetupScreen but
 * the values are consumed at the theme root above every screen, and threading callback pairs down
 * through three composables to move two settings back up is more machinery than this deserves.
 *
 * SharedPreferences is still the durable copy; the flows only exist so a change recomposes the
 * running UI instead of taking effect on next launch.
 */
object DisplaySettings {
    private val _highContrast = MutableStateFlow(false)
    private val _skin = MutableStateFlow(SkinId.INSTRUMENT)

    /**
     * Daylight readout boost. Not a light theme: the background stays the active skin's ground
     * and only the hero readout's ink gets brighter, see ReadoutPalette in ui/theme/Color.kt.
     *
     * Orthogonal to [skin]: it boosts the hero relative to whichever skin is running, so the two
     * settings never need to know about each other.
     */
    val highContrast: StateFlow<Boolean> = _highContrast

    /** The active instrument skin. See docs/DESIGN_SYSTEM.md section 3.5. */
    val skin: StateFlow<SkinId> = _skin

    /** Call once at process start, before the first composition, so the first frame is already
     *  in the right mode rather than flashing the standard palette and correcting itself. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _highContrast.value = prefs.getBoolean(PREF_HIGH_CONTRAST, false)
        _skin.value = prefs.getString(PREF_SKIN, null)
            ?.let { name -> SkinId.entries.find { it.name == name } }
            ?: SkinId.INSTRUMENT
    }

    fun setHighContrast(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_HIGH_CONTRAST, enabled).apply()
        _highContrast.value = enabled
    }

    fun setSkin(context: Context, skin: SkinId) {
        prefs(context).edit().putString(PREF_SKIN, skin.name).apply()
        _skin.value = skin
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
