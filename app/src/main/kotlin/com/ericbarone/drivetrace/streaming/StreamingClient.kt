package com.ericbarone.drivetrace.streaming

import android.util.Log
import com.ericbarone.drivetrace.obd.MeasurementSample
import com.ericbarone.drivetrace.location.LocationSample
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "StreamingClient"
private const val MAX_CONSECUTIVE_FAILURES = 5
private const val COOLDOWN_MS = 30_000L
private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Best-effort live stream to the home ingest server (see server/). This is
 * NEVER the authoritative data path, local Room + CSV stays authoritative
 * per the blueprint's reliability rules. Every call here is fire-and-forget:
 * it never suspends the caller, never retries indefinitely, and a circuit
 * breaker stops even attempting once the network looks dead, so a long
 * dead zone can't pile up an unbounded backlog of doomed requests.
 */
class StreamingClient(private val baseUrl: String, private val token: String) {

    private val enabled = baseUrl.isNotBlank() && token.isNotBlank()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntilMs = AtomicLong(0)

    private fun shouldAttempt(): Boolean =
        enabled && System.currentTimeMillis() >= circuitOpenUntilMs.get()

    private fun recordResult(success: Boolean) {
        if (success) {
            consecutiveFailures.set(0)
        } else if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
            circuitOpenUntilMs.set(System.currentTimeMillis() + COOLDOWN_MS)
            Log.i(TAG, "Too many consecutive failures, pausing streaming for ${COOLDOWN_MS}ms")
        }
    }

    private fun postFireAndForget(path: String, body: JSONObject) {
        if (!shouldAttempt()) return
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    recordResult(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { recordResult(it.isSuccessful) }
                }
            },
        )
    }

    fun startSession(
        sessionId: Long,
        startWallTimeUtc: Long,
        vehicleProfile: String,
        adapterName: String?,
        adapterAddress: String?,
        appVersion: String,
        phoneModel: String,
    ) {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("start_wall_time_utc_ms", startWallTimeUtc)
            put("vehicle_profile", vehicleProfile)
            put("adapter_name", adapterName)
            put("adapter_address", adapterAddress)
            put("app_version", appVersion)
            put("phone_model", phoneModel)
        }
        postFireAndForget("/sessions/$sessionId/start", body)
    }

    fun endSession(sessionId: Long, endWallTimeUtc: Long, completionStatus: String) {
        val body = JSONObject().apply {
            put("end_wall_time_utc_ms", endWallTimeUtc)
            put("completion_status", completionStatus)
        }
        postFireAndForget("/sessions/$sessionId/end", body)
    }

    fun postMeasurement(sessionId: Long, sample: MeasurementSample) {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("sequence", sample.sequence)
            put("wall_time_utc_ms", sample.wallTimeUtc)
            put("elapsed_ns", sample.elapsedNs)
            put("pid", sample.pidTag)
            put("canonical_name", sample.canonicalName)
            if (sample.valueNumeric != null) put("value_numeric", sample.valueNumeric) else put("value_numeric", JSONObject.NULL)
            put("value_text", sample.valueText)
            put("unit", sample.unit)
            put("latency_ms", sample.latencyMs)
            put("quality_flag", sample.qualityFlag)
        }
        postFireAndForget("/measurements", body)
    }

    fun postLocation(sessionId: Long, sample: LocationSample) {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("elapsed_ns", sample.elapsedNs)
            put("wall_time_utc_ms", sample.wallTimeUtc)
            put("latitude", sample.latitude)
            put("longitude", sample.longitude)
            put("altitude_m", sample.altitudeM)
            put("speed_mps", sample.speedMps)
            put("bearing_deg", sample.bearingDeg)
            put("horizontal_accuracy_m", sample.horizontalAccuracyM)
            put("provider", sample.provider)
        }
        postFireAndForget("/locations", body)
    }

    fun postEvent(sessionId: Long, elapsedNs: Long, wallTimeUtc: Long, eventType: String, severity: String, message: String) {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("elapsed_ns", elapsedNs)
            put("wall_time_utc_ms", wallTimeUtc)
            put("event_type", eventType)
            put("severity", severity)
            put("message", message)
        }
        postFireAndForget("/events", body)
    }
}
