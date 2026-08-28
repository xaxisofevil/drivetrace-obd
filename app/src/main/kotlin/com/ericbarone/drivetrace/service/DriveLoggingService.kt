package com.ericbarone.drivetrace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ericbarone.drivetrace.BuildConfig
import com.ericbarone.drivetrace.MainActivity
import com.ericbarone.drivetrace.R
import com.ericbarone.drivetrace.streaming.StreamingClient
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.EventEntity
import com.ericbarone.drivetrace.data.LocationEntity
import com.ericbarone.drivetrace.data.MeasurementEntity
import com.ericbarone.drivetrace.data.SessionEntity
import com.ericbarone.drivetrace.location.LocationCollector
import com.ericbarone.drivetrace.obd.BluetoothTransport
import com.ericbarone.drivetrace.obd.ElmSession
import com.ericbarone.drivetrace.ui.LocationSettings
import com.ericbarone.drivetrace.obd.PidScheduler
import com.ericbarone.drivetrace.obd.VehicleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val CHANNEL_ID = "drive_logging"
private const val NOTIFICATION_ID = 1
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 15_000L
private const val SETUP_TIMEOUT_MS = 30_000L
// How often the foreground notification (shade + lock screen) is refreshed with live sample/GPS
// counts. Matches Tier B's own "every 2-5s" cadence reasoning elsewhere in this project: fast
// enough that a glance at the lock screen mid-drive looks alive, far below the rate that would
// make repeated NotificationManager.notify() calls wasteful or get throttled by the system.
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 3_000L

// Some cheap ELM327 clones fabricate plausible-looking zero data instead of a clean error when
// the ECU is asleep, so "a response arrived" isn't proof of a live vehicle. RPM > this is a cheap
// plausibility floor (real idle is normally 600-1000); this many samples without one is enough
// to conclude the engine genuinely isn't running rather than just not sampled yet.
private const val PLAUSIBLE_RPM_FLOOR = 100.0
private const val RPM_SAMPLES_BEFORE_CONCLUDING_ENGINE_OFF = 5

class DriveLoggingService : Service() {

    companion object {
        const val ACTION_START = "com.ericbarone.drivetrace.action.START"
        const val ACTION_STOP = "com.ericbarone.drivetrace.action.STOP"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_VEHICLE_PROFILE = "vehicle_profile"
        const val EXTRA_NOTES = "notes"

        fun startIntent(context: Context, deviceAddress: String, vehicleProfile: VehicleProfile): Intent =
            Intent(context, DriveLoggingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
                putExtra(EXTRA_VEHICLE_PROFILE, vehicleProfile.name)
            }

        /** [notes] is the optional free-text note typed into the Stop dialog. Null from the
         * notification's own Stop action, which has no way to collect one; a null note leaves
         * whatever is already on the session row alone rather than blanking it. */
        fun stopIntent(context: Context, notes: String? = null): Intent =
            Intent(context, DriveLoggingService::class.java).apply {
                action = ACTION_STOP
                if (!notes.isNullOrBlank()) putExtra(EXTRA_NOTES, notes)
            }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var sessionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSessionId: Long? = null
    // Best-effort live stream (see server/), never the authoritative path; Room/CSV are.
    private val streamingClient = StreamingClient(BuildConfig.INGEST_BASE_URL, BuildConfig.INGEST_TOKEN)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                // Falls back to the first enum entry rather than crashing if the extra is ever
                // missing/stale (e.g. a queued intent from before an app update changed the enum).
                val vehicleProfile = intent.getStringExtra(EXTRA_VEHICLE_PROFILE)
                    ?.let { name -> VehicleProfile.entries.find { it.name == name } }
                    ?: VehicleProfile.entries.first()
                if (deviceAddress != null && sessionJob == null) {
                    // CONFIRMED REAL CRASH, fixed here: this manifest declares
                    // foregroundServiceType="connectedDevice|location", and Android 14+
                    // enforces an eligibility check on the "location" half specifically, on top
                    // of (and stricter than) the generic background-FGS-start check
                    // AutomationReceiver.dispatch() already anticipates: a foreground service
                    // with a location type, started while this app is not itself in an eligible
                    // foreground/while-in-use state, throws SecurityException from
                    // startForeground() outright, killing the whole process, not just skipping
                    // GPS the way this file's own comments (see AutomationReceiver.kt's
                    // ACCESS_BACKGROUND_LOCATION warning) predicted. Confirmed live via
                    // MacroDroid and reproduced directly with `adb shell am broadcast`: a
                    // background-triggered start crashed with exactly this exception every
                    // time the device lacked "Allow all the time" location, and stopped
                    // crashing the moment that permission was granted. A manual Start Logging
                    // tap never hit this, the app is already in an eligible foreground state
                    // at that exact moment.
                    //
                    // Granting ACCESS_BACKGROUND_LOCATION is the real fix and was applied to
                    // the test device directly; this catch is the defense-in-depth this
                    // project's reliability rule (section 9: detect the impossible, don't
                    // crash) already asks for everywhere else, for the next phone that reaches
                    // this without that permission granted yet.
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
                    } catch (e: SecurityException) {
                        android.util.Log.e(
                            "DriveLoggingService",
                            "Could not start the foreground service: ${e.message}. This almost " +
                                "always means Location is not set to \"Allow all the time\" for " +
                                "DriveTrace, which Android requires for a GPS-capable foreground " +
                                "service started while the app is in the background (an " +
                                "automation trigger, not the in-app Start button). Settings > " +
                                "Apps > DriveTrace > Permissions > Location > Allow all the time.",
                        )
                        return START_NOT_STICKY
                    }
                    sessionJob = serviceScope.launch { runSession(deviceAddress, vehicleProfile) }
                }
            }
            ACTION_STOP -> stopSession(intent.getStringExtra(EXTRA_NOTES))
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession(deviceAddress: String, vehicleProfile: VehicleProfile) {
        val dao = AppDatabase.getInstance(applicationContext).sessionDao()
        acquireWakeLock()

        val startWallTimeUtc = System.currentTimeMillis()
        val session = SessionEntity(
            // See Entities.kt: must be wall-clock-derived, not a local autoincrement, so a Room
            // wipe can never make a new session collide with (and overwrite) old server history.
            sessionId = startWallTimeUtc,
            startWallTimeUtc = startWallTimeUtc,
            startElapsedNs = System.nanoTime(),
            vehicleProfile = vehicleProfile.displayName,
            adapterAddress = deviceAddress,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        dao.insertSession(session)
        val sessionId = session.sessionId
        currentSessionId = sessionId
        val startElapsedNs = session.startElapsedNs
        val sharedSequence = AtomicLong(0)
        // Persist across reconnects within the same session, not reset per attempt.
        var rpmSamplesSeen = 0
        var plausibleRpmSeen = false

        suspend fun recordEvent(elapsedNs: Long, eventType: String, severity: String, message: String) {
            val wallTimeUtc = System.currentTimeMillis()
            dao.insertEvent(
                EventEntity(
                    sessionId = sessionId,
                    elapsedNs = elapsedNs,
                    wallTimeUtc = wallTimeUtc,
                    eventType = eventType,
                    severity = severity,
                    message = message,
                ),
            )
            streamingClient.postEvent(sessionId, elapsedNs, wallTimeUtc, eventType, severity, message)
        }

        streamingClient.startSession(
            sessionId = sessionId,
            startWallTimeUtc = session.startWallTimeUtc,
            vehicleProfile = session.vehicleProfile,
            adapterName = session.adapterName,
            adapterAddress = session.adapterAddress,
            appVersion = session.appVersion,
            phoneModel = session.phoneModel,
        )

        LoggingStatus.state.value = LoggingUiState(
            connectionState = ConnectionState.CONNECTING,
            sessionId = sessionId,
            startedAtMs = System.currentTimeMillis(),
        )

        // GPS runs for the whole session regardless of Bluetooth reconnects - but only if the
        // Settings toggle has it on (see LocationSettings; off by default). When off, no
        // FusedLocationProviderClient request is ever made, not just an ignored result: the
        // point is avoiding the GPS chip's own battery cost, not merely discarding fixes after
        // the fact. OBD's own Vehicle Speed PID keeps working either way (see LocationSettings'
        // doc comment for why that's an acceptable trade, not just a cheaper one).
        val locationJob = if (LocationSettings.enabled.value) {
            serviceScope.launch {
                LocationCollector(applicationContext).samples(startElapsedNs).collect { sample ->
                    dao.insertLocation(
                        LocationEntity(
                            sessionId = sessionId,
                            elapsedNs = sample.elapsedNs,
                            wallTimeUtc = sample.wallTimeUtc,
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            altitudeM = sample.altitudeM,
                            speedMps = sample.speedMps,
                            bearingDeg = sample.bearingDeg,
                            horizontalAccuracyM = sample.horizontalAccuracyM,
                            provider = sample.provider,
                        ),
                    )
                    streamingClient.postLocation(sessionId, sample)
                    val current = LoggingStatus.state.value
                    LoggingStatus.state.value = current.copy(locationCount = current.locationCount + 1)
                }
            }
        } else {
            null
        }

        // CONFIRMED REAL BUG, fixed here: updateNotification() used to only ever be called once,
        // right after the initial connect finished, before the polling loop had produced any real
        // samples yet. LoggingStatus.state itself updates live on every measurement (the in-app UI
        // reads it directly and looks fine), but nothing was pushing those updates back into the
        // notification shown in the shade and on the lock screen, so it froze at whatever it said
        // in that first split second (often still "Initializing...") for the rest of the drive,
        // reported live: "the lock screen notification never updates, it stays at Initializing
        // always". A ticker on the session's own timescale, not tied to any one PID or reconnect
        // attempt, is what a display meant to be glanced at without unlocking the phone needs.
        val notificationTickerJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                updateNotification()
            }
        }

        var backoffMs = INITIAL_BACKOFF_MS
        val transport = BluetoothTransport(applicationContext)

        try {
            while (currentCoroutineIsActive()) {
                try {
                    // Connect through the one-time reads, guarded by a watchdog rather than
                    // withTimeout. First attempt used withTimeout(SETUP_TIMEOUT_MS) alone and a
                    // review caught that it doesn't actually work here: every step in this chain
                    // (BluetoothSocket.connect(), then raw InputStream/OutputStream reads for the
                    // AT handshake and one-time PID reads, inside the third-party
                    // ObdDeviceConnection this session's ElmSession wraps) is a plain blocking
                    // call with no suspension point of its own. Coroutine cancellation is
                    // cooperative: it can only throw at a suspension point, and a thread parked
                    // inside a blocking read has none to be caught at, so withTimeout's deadline
                    // would not actually fire until the blocked call already returned on its own,
                    // i.e. never, for the exact hang it was meant to catch. Confirmed for real
                    // before either fix: a session sat at connectionState=INITIALIZING,
                    // measurementCount=0 for 34 minutes with no recovery, see KNOWN_ISSUES.md.
                    //
                    // A watchdog on the side fixes this for real: closing a Java socket from a
                    // different thread makes any read already blocked on it throw IOException
                    // immediately, a genuine interrupt rather than a cancellation request the
                    // blocked thread has no chance to notice. That exception unwinds normally
                    // into the catch block below, the same recovery path an ordinary connection
                    // failure already takes: log a RECONNECT event, back off, try again.
                    val setupWatchdog = serviceScope.launch {
                        delay(SETUP_TIMEOUT_MS)
                        transport.close()
                    }
                    val scheduler = try {
                        LoggingStatus.state.value = LoggingStatus.state.value.copy(
                            connectionState = ConnectionState.CONNECTING,
                            statusMessage = "Connecting to adapter...",
                        )
                        transport.connect(deviceAddress)

                        LoggingStatus.state.value = LoggingStatus.state.value.copy(
                            connectionState = ConnectionState.INITIALIZING,
                            statusMessage = "Initializing ELM327...",
                        )
                        val input = transport.inputStream ?: error("No input stream")
                        val output = transport.outputStream ?: error("No output stream")
                        val elmSession = ElmSession(input, output)
                        val initResult = elmSession.initialize()
                        dao.updateSession(session.copy(sessionId = sessionId, protocol = initResult.protocol))

                        val scheduler = PidScheduler(
                            elmSession = elmSession,
                            startElapsedNs = startElapsedNs,
                            catalog = vehicleProfile.catalog,
                            onMeasurement = { sample ->
                                dao.insertMeasurement(
                                    MeasurementEntity(
                                        sessionId = sessionId,
                                        sequence = sample.sequence,
                                        wallTimeUtc = sample.wallTimeUtc,
                                        elapsedNs = sample.elapsedNs,
                                        pidTag = sample.pidTag,
                                        canonicalName = sample.canonicalName,
                                        valueNumeric = sample.valueNumeric,
                                        valueText = sample.valueText,
                                        unit = sample.unit,
                                        latencyMs = sample.latencyMs,
                                        qualityFlag = sample.qualityFlag,
                                        rawResponse = sample.rawResponse,
                                    ),
                                )
                                streamingClient.postMeasurement(sessionId, sample)

                                var engineDetected = LoggingStatus.state.value.engineDetected
                                if (sample.canonicalName == "Engine RPM") {
                                    rpmSamplesSeen++
                                    if ((sample.valueNumeric ?: 0.0) > PLAUSIBLE_RPM_FLOOR) {
                                        plausibleRpmSeen = true
                                    }
                                    engineDetected = when {
                                        plausibleRpmSeen -> TriState.YES
                                        rpmSamplesSeen >= RPM_SAMPLES_BEFORE_CONCLUDING_ENGINE_OFF -> TriState.NO
                                        else -> TriState.PENDING
                                    }
                                }

                                val current = LoggingStatus.state.value
                                LoggingStatus.state.value = current.copy(
                                    connectionState = ConnectionState.LOGGING,
                                    measurementCount = current.measurementCount + 1,
                                    lastSampleAtMs = System.currentTimeMillis(),
                                    engineDetected = engineDetected,
                                    // The live gauge cluster's whole data source, updated at
                                    // exactly the point this same sample is committed to Room, so
                                    // the screen can never show a value that was never recorded.
                                    // Last write wins per canonicalName; the map holds one entry
                                    // per PID in the catalog, roughly thirty, so copying it per
                                    // poll costs far less than the Room insert immediately above
                                    // it, and a StateFlow needs a fresh instance to emit at all.
                                    latestValues = current.latestValues +
                                        (sample.canonicalName to sample),
                                )
                            },
                            onEvent = { event ->
                                recordEvent(event.elapsedNs, event.eventType, event.severity, event.message)
                            },
                            sequence = sharedSequence,
                        )

                        val oneTimeResults = scheduler.runOneTimeReads()
                        for ((key, result) in oneTimeResults) {
                            recordEvent(
                                System.nanoTime() - startElapsedNs, "ONE_TIME_READ", "INFO",
                                "$key=${result.value} | raw=${result.rawResponse}",
                            )
                        }
                        scheduler
                    } finally {
                        // Disarm on any exit, success or failure: a watchdog left armed after
                        // setup genuinely finished would close the transport out from under a
                        // healthy, actively-polling connection SETUP_TIMEOUT_MS later.
                        setupWatchdog.cancel()
                    }

                    backoffMs = INITIAL_BACKOFF_MS // reset after a successful (re)connect
                    updateNotification()
                    scheduler.run { currentCoroutineIsActive() }
                } catch (e: Exception) {
                    if (!currentCoroutineIsActive()) break
                    transport.close()
                    val current = LoggingStatus.state.value
                    LoggingStatus.state.value = current.copy(
                        connectionState = ConnectionState.RECONNECTING,
                        reconnectCount = current.reconnectCount + 1,
                        statusMessage = "Reconnecting: ${e.message ?: e::class.simpleName}",
                    )
                    recordEvent(
                        System.nanoTime() - startElapsedNs, "RECONNECT", "WARNING",
                        e.message ?: e::class.simpleName ?: "unknown error",
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        } finally {
            locationJob?.cancel()
            notificationTickerJob.cancel()
            transport.close()
        }
    }

    private fun currentCoroutineIsActive(): Boolean = sessionJob?.isActive == true

    private fun stopSession(notes: String? = null) {
        val sessionId = currentSessionId
        serviceScope.launch {
            sessionJob?.cancel()
            sessionJob = null
            if (sessionId != null) {
                val dao = AppDatabase.getInstance(applicationContext).sessionDao()
                val endWallTimeUtc = System.currentTimeMillis()
                dao.getSession(sessionId)?.let { s ->
                    dao.updateSession(
                        s.copy(
                            endWallTimeUtc = endWallTimeUtc,
                            completionStatus = "COMPLETED",
                            // Written here, before backfill runs, so the note is already on the
                            // row when CsvExporter's metadata.json is written. A blank/absent
                            // note keeps whatever was there rather than clearing it.
                            notes = notes?.takeIf { it.isNotBlank() } ?: s.notes,
                        ),
                    )
                }
                streamingClient.endSession(sessionId, endWallTimeUtc, "COMPLETED")
                // The note typed into the Stop dialog. /start fired before it existed and /end
                // carries no note field, so without this the server's copy stays null for a note
                // the driver typed thirty seconds ago. Fire-and-forget, after the local write:
                // Room already has it, and a note failing to reach the server is not a reason to
                // interrupt anyone. Skipped entirely when the stop carried no note (the
                // notification's Stop action), so nothing blanks a note already on the server.
                notes?.takeIf { it.isNotBlank() }?.let { streamingClient.updateSessionNotes(sessionId, it) }

                LoggingStatus.state.value = LoggingStatus.state.value.copy(
                    statusMessage = "Verifying complete upload...",
                )
                // Shared with BackfillRetryWorker (see BackfillCoordinator.kt) so there's exactly
                // one place that knows how to take a session from "logged locally" to "confirmed
                // uploaded and analyzed", and so the persisted SessionEntity fields it writes stay
                // in sync with what the live UI below shows.
                val outcome = runBackfillAndAnalysis(dao, streamingClient, sessionId)
                LoggingStatus.state.value = LoggingStatus.state.value.copy(
                    backfillStatus = if (outcome.backfillSucceeded) TriState.YES else TriState.NO,
                    backfillMessage = outcome.backfillMessage,
                )

                if (!outcome.backfillSucceeded) {
                    // Room already has this session marked FAILED (persisted inside
                    // runBackfillAndAnalysis); queue a background retry so it isn't stranded if
                    // the user closes the app right now instead of waiting on this screen.
                    // Confirmed real need: exactly this happened on a real driveway test.
                    BackfillRetryWorker.enqueueSweep(applicationContext)
                } else {
                    LoggingStatus.state.value = LoggingStatus.state.value.copy(statusMessage = "Analyzing drive...")
                    LoggingStatus.state.value = LoggingStatus.state.value.copy(
                        analysisStatus = when (outcome.analysisStatus) {
                            "DONE" -> TriState.YES
                            "FAILED" -> TriState.NO
                            else -> TriState.PENDING
                        },
                        analysisSummary = outcome.analysisSummary,
                        analysisMessage = outcome.analysisMessage ?: "",
                    )
                }
            }
            releaseWakeLock()
            LoggingStatus.state.value = LoggingStatus.state.value.copy(
                connectionState = ConnectionState.DISCONNECTED,
                statusMessage = "Session complete",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveTrace::LoggingWakeLock").apply {
            acquire(2 * 60 * 60 * 1000L /* 2h safety cap */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Drive logging",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing OBD + GPS drive logging session" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // getForegroundService(), not getService(): confirmed for real, twice, with logcat
        // open both times, that a plain getService() PendingIntent tapped from this
        // notification's action never reached onStartCommand at all (ACTION_START logs a
        // clear ActivityManager line every time; ACTION_STOP left zero trace anywhere,
        // system-level or app-level, on this OEM's ColorOS build). getForegroundService()
        // is the platform's own recommended replacement for exactly this case, a notification
        // action targeting an already-foreground service, and is the pattern media-session
        // pause/stop actions use for the same reason. Safe to call again on an
        // already-foreground service: the "must call startForeground() promptly" requirement
        // is tracked per-service, not per start call, so this doesn't need a matching
        // startForeground() in the ACTION_STOP branch. See KNOWN_ISSUES.md.
        val stopPendingIntent = PendingIntent.getForegroundService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DriveTrace logging")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_notification, "Stop", stopPendingIntent)
            .setOngoing(true)
            // Unset defaults to VISIBILITY_PRIVATE, which redacts this notification's content
            // (and, on a locked device, can drop its action buttons entirely, the Stop action
            // included) down to a generic "content hidden" line on a secured lock screen. Nothing
            // here is sensitive, sample counts and connection state, so there is no reason to ask
            // for that redaction; PUBLIC is what makes Stop reachable without unlocking the phone.
            // Still subject to the device's own "hide sensitive notifications" lock screen
            // setting if the user has that on, which this can't and shouldn't override.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        val state = LoggingStatus.state.value
        val warning = if (state.engineDetected == TriState.NO) {
            " | No real vehicle data - check ignition"
        } else {
            ""
        }
        val text = "Samples: ${state.measurementCount} | GPS: ${state.locationCount} | ${state.connectionState}$warning"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
