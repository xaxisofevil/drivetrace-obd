package com.ericbarone.drivetrace.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** See [LocationSettings.enabled]. Off by default: GPS at the fix rate and accuracy this app
 *  needs is one of the single biggest battery draws on Android, and OBD's own Vehicle Speed PID
 *  already tracks GPS-derived speed almost exactly (correlation 0.996 across 9,000+ matched
 *  samples in real drive data, mean absolute difference 1.57 km/h) - so leaving it off costs
 *  route/position data, not speed. Anyone who wants the map trace back can turn it on. */
const val PREF_GPS_ENABLED = "gps_logging_enabled"

/**
 * Whether [com.ericbarone.drivetrace.service.DriveLoggingService] requests location updates at
 * all during a session. Same StateFlow-over-SharedPreferences shape as [DisplaySettings] and for
 * the same reason: the toggle lives on SettingsScreen, the value is read where a session actually
 * starts, and that's a service, not a composable three screens down.
 */
object LocationSettings {
    private val _enabled = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled

    /** Call once at process start, same as [DisplaySettings.load]. */
    fun load(context: Context) {
        _enabled.value = prefs(context).getBoolean(PREF_GPS_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_GPS_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
