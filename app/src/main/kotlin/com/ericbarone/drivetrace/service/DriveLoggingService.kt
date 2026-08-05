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
private const val VEHICLE_PROFILE = "2020 Mazda 6 2.5T"
private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 15_000L

class DriveLoggingService : Service() {

    companion object {
        const val ACTION_START = "com.ericbarone.drivetrace.action.START"
        const val ACTION_STOP = "com.ericbarone.drivetrace.action.STOP"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        fun startIntent(context: Context, deviceAddress: String): Intent =
            Intent(context, DriveLoggingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, DriveLoggingService::class.java).apply { action = ACTION_STOP }
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
                if (deviceAddress != null && sessionJob == null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
                    sessionJob = serviceScope.launch { runSession(deviceAddress) }
                }
            }
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession(deviceAddress: String) {
        val dao = AppDatabase.getInstance(applicationContext).sessionDao()
        acquireWakeLock()

        val session = SessionEntity(
            startWallTimeUtc = System.currentTimeMillis(),
            startElapsedNs = System.nanoTime(),
            vehicleProfile = VEHICLE_PROFILE,
            adapterAddress = deviceAddress,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        val sessionId = dao.insertSession(session)
        currentSessionId = sessionId
        val startElapsedNs = session.startElapsedNs
        val sharedSequence = AtomicLong(0)

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
                                ),
                            )
                            streamingClient.postMeasurement(sessionId, sample)
                            val current = LoggingStatus.state.value
                            LoggingStatus.state.value = current.copy(
                                connectionState = ConnectionState.LOGGING,
                                measurementCount = current.measurementCount + 1,
                                lastSampleAtMs = System.currentTimeMillis(),
                            )
                        },
                        onEvent = { event ->
                            recordEvent(event.elapsedNs, event.eventType, event.severity, event.message)
                        },
                        sequence = sharedSequence,
                    )

                    val oneTimeResults = scheduler.runOneTimeReads()
                    for ((key, value) in oneTimeResults) {
                        recordEvent(System.nanoTime() - startElapsedNs, "ONE_TIME_READ", "INFO", "$key=$value")
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

    private fun stopSession() {
        val sessionId = currentSessionId
        serviceScope.launch {
            if (sessionId != null) {
                val dao = AppDatabase.getInstance(applicationContext).sessionDao()
                val endWallTimeUtc = System.currentTimeMillis()
                dao.getSession(sessionId)?.let { s ->
                    dao.updateSession(s.copy(endWallTimeUtc = endWallTimeUtc, completionStatus = "COMPLETED"))
                }
                streamingClient.endSession(sessionId, endWallTimeUtc, "COMPLETED")
            }
            sessionJob?.cancel()
            sessionJob = null
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
        val stopPendingIntent = PendingIntent.getService(
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
        val text = "Samples: ${state.measurementCount} | GPS: ${state.locationCount} | ${state.connectionState}"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
