package com.ericbarone.drivetrace.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.ui.PREFS_NAME
import com.ericbarone.drivetrace.ui.PREF_AUTOMATION_TOKEN
import com.ericbarone.drivetrace.ui.PREF_LAST_DEVICE
import com.ericbarone.drivetrace.ui.PREF_VEHICLE_PROFILE
import java.security.MessageDigest
import java.security.SecureRandom

/** Every line this class writes uses this tag, so `adb logcat -s DriveTraceAutomation` is the
 *  whole diagnostic story for a recipe that fires but appears to do nothing. */
private const val TAG = "DriveTraceAutomation"

/** 10 bytes of SecureRandom as lowercase hex. Long enough that no app on the phone guesses it,
 *  short enough to read off the Settings screen and check character by character against what got
 *  pasted into MacroDroid. Not a credential for anything outside this device. */
private const val TOKEN_BYTES = 10

/**
 * The third way a drive session starts or stops, alongside the Setup screen's button and the
 * notification's Stop action: an exported broadcast receiver an automation app fires at.
 *
 * The shape is deliberately AdGuard's (`com.adguard.android.receiver.AutomationReceiver`, see
 * their Tasker KB page): one action, a `command` extra, a `token` extra. Not because it is the
 * only workable design, but because the person configuring MacroDroid against this already has
 * AdGuard's recipe working on the same phone and can build this one by direct analogy.
 *
 * **The token is not decoration.** An exported receiver is reachable by every app on the device;
 * without a check, any of them could start a two-hour logging session with GPS and a wake lock, or
 * kill one mid-drive. There is no way to restrict this to "MacroDroid only" through the manifest
 * (`android:permission` with a signature-level permission would exclude MacroDroid too, which is
 * the app that needs in), so a shared secret is what is left.
 *
 * **What it deliberately does not carry.** No MAC address, no vehicle profile in the intent. The
 * recipe names a command and proves who it is; everything else comes from the same
 * SharedPreferences the Setup screen writes, so an automation-started session is byte-for-byte the
 * session the button would have started, and changing the adapter never means editing a macro.
 */
class AutomationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AUTOMATION = "com.ericbarone.drivetrace.action.AUTOMATION"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TOKEN = "token"
        const val COMMAND_START = "start"
        const val COMMAND_STOP = "stop"
        const val COMMAND_PERFORMANCE = "performance"
        const val EXTRA_MODE = "mode"
        const val COMMAND_VEHICLE = "vehicle"
        const val COMMAND_IDENTITY = "identity"
        const val EXTRA_CAR_MAKE = "car_make"
        const val EXTRA_CAR_MODEL = "car_model"
        const val EXTRA_CAR_YEAR = "car_year"
        const val EXTRA_CAR_VEHICLE_ID = "car_vehicle_id"
        private const val KNOWN_COMMANDS = "start, stop, performance, vehicle, identity"
        private const val MAX_EXTRA_CHARS = 120

        /**
         * The device's automation token, generated on first read and stable afterwards. Lives in
         * the same `drivetrace_prefs` store as the adapter and vehicle choices rather than a
         * private file of its own.
         *
         * Synchronized because the Settings screen (main thread, to display it) and this receiver (a
         * broadcast thread, to check it) can both be the first caller; two threads generating two
         * tokens would leave the one on screen unable to authenticate anything.
         */
        @Synchronized
        fun token(context: Context): String {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_AUTOMATION_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val fresh = ByteArray(TOKEN_BYTES)
                .also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            // commit(), not apply(): the next thing that happens is usually the user copying this
            // token into a macro, and a token that loses a race with process death would leave a
            // recipe authenticating against a value the app has since regenerated.
            prefs.edit().putString(PREF_AUTOMATION_TOKEN, fresh).commit()
            Log.i(TAG, "Generated a new automation token (see the Settings screen to copy it)")
            return fresh
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTOMATION) {
            Log.w(TAG, "Ignored: action was ${intent.action}, expected $ACTION_AUTOMATION")
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase()
        val supplied = intent.getStringExtra(EXTRA_TOKEN)?.trim()

        // Neither token is ever logged, including the wrong one: a mistyped token is usually a
        // correct token with one character off, and logcat is readable by adb from a desk.
        if (supplied.isNullOrEmpty()) {
            Log.w(TAG, "Rejected \"$command\": no '$EXTRA_TOKEN' extra. Add it to the macro; the value is on DriveTrace's Settings screen.")
            return
        }
        if (!tokensMatch(supplied, token(context))) {
            Log.w(TAG, "Rejected \"$command\": '$EXTRA_TOKEN' does not match this device's token (Settings screen has the current one; clearing app data regenerates it).")
            return
        }

        when (command) {
            COMMAND_START -> startLogging(context, intent)
            COMMAND_STOP -> stopLogging(context, intent)
            COMMAND_PERFORMANCE -> setPerformanceMode(context, intent.getStringExtra(EXTRA_MODE))
            COMMAND_VEHICLE -> forwardEvent(
                context, command, "VEHICLE_STATE",
                describe(intent, "gear", "parking_brake", "driving_status", "ts"),
            )
            COMMAND_IDENTITY -> lateIdentity(context, intent)
            null -> Log.w(TAG, "Authenticated but ignored: no '$EXTRA_COMMAND' extra. Expected one of $KNOWN_COMMANDS.")
            else -> Log.w(TAG, "Authenticated but ignored: unknown command \"$command\". Expected one of $KNOWN_COMMANDS.")
        }
    }

    /** "key=value" pairs for the extras that are present and non-blank, in the order given. Values
     * are capped so a misbehaving caller cannot fill the events table with one message. */
    private fun describe(intent: Intent, vararg keys: String): String =
        keys.mapNotNull { key ->
            intent.getStringExtra(key)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_EXTRA_CHARS)?.let { "$key=$it" }
        }.joinToString(" ")

    private fun identityOf(intent: Intent) = CarIdentity(
        make = intent.getStringExtra(EXTRA_CAR_MAKE)?.trim()?.take(MAX_EXTRA_CHARS).orEmpty(),
        model = intent.getStringExtra(EXTRA_CAR_MODEL)?.trim()?.take(MAX_EXTRA_CHARS).orEmpty(),
        year = intent.getStringExtra(EXTRA_CAR_YEAR)?.trim()?.take(MAX_EXTRA_CHARS).orEmpty(),
        vehicleId = intent.getStringExtra(EXTRA_CAR_VEHICLE_ID)?.trim()?.take(MAX_EXTRA_CHARS).orEmpty(),
    )

    /** Vehicle state is only recorded against a live session, for the same reason as stop. */
    private fun forwardEvent(context: Context, command: String, eventType: String, message: String) {
        if (!sessionIsLive()) {
            Log.i(TAG, "$command ignored: no session is running.")
            return
        }
        dispatch(context, DriveLoggingService.recordEventIntent(context, eventType, message), command)
    }

    /**
     * Identity that arrives after the session started (the head unit had not announced it yet when
     * the gear trigger fired). Updates the registry whether or not a session is running, so the
     * next start knows the car; records an IDENTITY event on a live session, flagged if the car
     * resolves to a different profile than the one already polling.
     */
    private fun lateIdentity(context: Context, intent: Intent) {
        val identity = identityOf(intent)
        val resolution = CarRegistry.resolve(context, identity)
        if (resolution == null) {
            Log.i(TAG, "identity ignored: no car_* fields.")
            return
        }
        if (!sessionIsLive()) {
            Log.i(TAG, "identity for ${identity.label()} registered as ${resolution.profile.name} (${resolution.source}); no session is running.")
            return
        }
        val message = describe(
            intent, EXTRA_CAR_MAKE, EXTRA_CAR_MODEL, EXTRA_CAR_YEAR, EXTRA_CAR_VEHICLE_ID,
            "head_unit_make", "head_unit_model", "head_unit_sw", "ts",
        ) + " profile=${resolution.profile.name} source=${resolution.source}"
        dispatch(
            context,
            DriveLoggingService.recordEventIntent(
                context, "IDENTITY", message, expectedProfile = resolution.profile,
            ),
            "identity",
        )
    }

    /**
     * Live performance-logging toggle, `mode` = on | off | toggle. Guarded on a live session for the
     * same foreground-service reason as [stopLogging]. A mode sent before the session is live is
     * dropped; a start that wants performance sets it with its own `mode` extra instead.
     */
    private fun setPerformanceMode(context: Context, mode: String?) {
        val requested = mode?.trim()?.lowercase()
        if (requested !in setOf("on", "off", "toggle")) {
            Log.w(TAG, "performance ignored: '$EXTRA_MODE' must be on, off or toggle (got \"$mode\").")
            return
        }
        if (!sessionIsLive()) {
            Log.i(TAG, "performance \"$requested\" ignored: no session is running.")
            return
        }
        dispatch(context, DriveLoggingService.performanceModeIntent(context, requested!!), "performance $requested")
    }

    /**
     * Reads back exactly what a manual "Start logging" tap persists and hands it to the same
     * [DriveLoggingService.startIntent]. The one thing it cannot do is invent a device: the Setup
     * screen writes [PREF_LAST_DEVICE] when Start is tapped, so a phone that has never started a
     * session in-app has nothing to start with. That fails loudly in logcat rather than passing a
     * null address down and letting `onStartCommand` drop it silently.
     */
    private fun startLogging(context: Context, intent: Intent) {
        if (sessionIsLive()) {
            Log.i(TAG, "start ignored: a session is already running (${LoggingStatus.state.value.connectionState}).")
            return
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(PREF_LAST_DEVICE, null)?.takeIf { it.isNotBlank() }
        if (address == null) {
            Log.w(
                TAG,
                "start ignored: no adapter has ever been selected on this device. Open DriveTrace, " +
                    "pick the ELM327 adapter on the Setup screen and press Start logging once; " +
                    "automation reuses that choice from then on.",
            )
            return
        }
        // The car the head unit names wins over the Setup screen's selection, and resolve()
        // writes its answer back to that selection, so the read below sees it.
        val identity = identityOf(intent)
        val resolution = CarRegistry.resolve(context, identity)
        val vehicleProfile = prefs.getString(PREF_VEHICLE_PROFILE, null)
            ?.let { name -> VehicleProfile.entries.find { it.name == name } }
            ?: VehicleProfile.entries.first()
        val startDetails = buildString {
            append(describe(intent, "trigger", "gear"))
            if (isEmpty()) append("trigger=automation")
            if (resolution != null) {
                append(" car=\"${identity.label()}\"")
                if (identity.vehicleId.isNotBlank()) append(" vehicle_id=${identity.vehicleId}")
                append(" car_source=${resolution.source}")
            } else {
                append(" car_source=selected-profile")
            }
        }

        // Predicts a whole drive logged with an empty locations table, which is otherwise only
        // discoverable after the fact. A foreground service started while the app is in the
        // background gets while-in-use permissions withheld (Android 11 behaviour change), and
        // location is one of them unless the user has set this app to "Allow all the time".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                TAG,
                "Starting without ACCESS_BACKGROUND_LOCATION. OBD data will log normally, but GPS " +
                    "may record nothing for a session started while the app is in the background. " +
                    "Set Location to \"Allow all the time\" for DriveTrace; see docs/AUTOMATION.md.",
            )
        }

        val performance = intent.getStringExtra(EXTRA_MODE)?.trim()?.lowercase() == "performance"
        dispatch(
            context,
            DriveLoggingService.startIntent(context, address, vehicleProfile, performance, startDetails),
            "start",
        )
    }

    /**
     * The same intent the notification's Stop action sends, sent the same way. No note: there is
     * nowhere to type one, and [DriveLoggingService.stopSession] leaves an existing note alone
     * when none arrives (see docs/DATA_SCHEMA.md).
     *
     * Guarded on there actually being a live session, which is not defensive tidiness: the service
     * is started with `startForegroundService`, and a service that starts, finds nothing to stop
     * and never calls `startForeground()` is exactly the shape the platform kills the process for.
     * A "car disconnected" macro firing on a day nobody pressed Start is the ordinary case, not
     * the rare one.
     */
    private fun stopLogging(context: Context, intent: Intent) {
        if (!sessionIsLive()) {
            Log.i(TAG, "stop ignored: no session is running.")
            return
        }
        val details = describe(intent, "trigger", "gear").ifEmpty { "trigger=automation" }
        dispatch(context, DriveLoggingService.stopIntent(context, stopDetails = details), "stop")
    }

    /**
     * `startForegroundService`, not `startService`, for both commands, and for the same reason the
     * notification's Stop action uses `PendingIntent.getForegroundService` (see KNOWN_ISSUES.md):
     * this call is made from a backgrounded app, and a plain background `startService` is not
     * allowed at all since Android 8.
     *
     * It can still be refused. From Android 12 an app in the background may not start a foreground
     * service unless it is exempt, and the exemption that applies here is the user turning off
     * battery optimisation for DriveTrace, which is a setting, not something code can arrange. So
     * the failure is caught and named rather than crashing a broadcast receiver, and the fix is
     * step 1 of docs/AUTOMATION.md's troubleshooting.
     */
    private fun dispatch(context: Context, intent: Intent, label: String) {
        try {
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "$label accepted, dispatched to DriveLoggingService.")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "$label failed: the system refused to start the logging service from the " +
                    "background. Set DriveTrace's battery usage to Unrestricted (Settings > Apps > " +
                    "DriveTrace > Battery). See docs/AUTOMATION.md.",
                e,
            )
        }
    }

    /**
     * Whether a drive is actually in progress right now, as opposed to a finished one whose report
     * is still on screen: [LoggingStatus] keeps `sessionId` set after Stop so the Session Complete
     * screen has something to render, and only the connection state distinguishes the two.
     *
     * Reading the in-process status bus is sound here because the receiver and the service share
     * one process, and a process that has died has taken any running session with it.
     */
    private fun sessionIsLive(): Boolean {
        val state = LoggingStatus.state.value
        return state.sessionId != null && state.connectionState != ConnectionState.DISCONNECTED
    }

    /** Length-safe, content-constant-time comparison. A timing attack is not a credible threat
     *  from a local app that can retry instantly, but this costs one import. */
    private fun tokensMatch(supplied: String, actual: String): Boolean =
        MessageDigest.isEqual(supplied.toByteArray(), actual.toByteArray())
}
