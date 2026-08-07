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
                    startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
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

        // GPS runs for the whole session regardless of Bluetooth reconnects.
        val locationJob = serviceScope.launch {
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

        var backoffMs = INITIAL_BACKOFF_MS
        val transport = BluetoothTransport(applicationContext)

        try {
            while (currentCoroutineIsActive()) {
                try {
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
            locationJob.cancel()
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
