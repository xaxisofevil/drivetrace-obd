package com.ericbarone.drivetrace.streaming

import android.util.Log
import com.ericbarone.drivetrace.data.EventEntity
import com.ericbarone.drivetrace.data.LocationEntity
import com.ericbarone.drivetrace.data.MeasurementEntity
import com.ericbarone.drivetrace.obd.MeasurementSample
import com.ericbarone.drivetrace.location.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "StreamingClient"
private const val MAX_CONSECUTIVE_FAILURES = 5
private const val COOLDOWN_MS = 30_000L
private const val BACKFILL_CHUNK_SIZE = 500
private val JSON = "application/json; charset=utf-8".toMediaType()

data class BackfillResult(val success: Boolean, val measurementCount: Int, val locationCount: Int, val eventCount: Int, val error: String? = null)

data class AnalysisSummary(
    val idleFractionPct: Double?,
    val warmupMinutes: Double?,
    val distanceGpsKm: Double?,
    val overallMpg: Double?,
    val flags: List<String>,
)

sealed class AnalysisPollResult {
    data object Running : AnalysisPollResult()
    data class Done(val summary: AnalysisSummary) : AnalysisPollResult()
    data class Failed(val error: String) : AnalysisPollResult()
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

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

    // Backfill wants patience, not fast-fail: it runs once at Stop, not per-measurement in a
    // polling loop, so a slower cellular connection finishing late is fine, wrongly giving up
    // when it would've succeeded a few seconds later is not.
    private val backfillClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
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

    /**
     * Run once at Stop, not fire-and-forget: local Room is authoritative, this makes the
     * server's copy match it exactly regardless of what the live per-item stream missed
     * during the drive (dead zones, the circuit breaker pausing, etc). Unlike every other
     * method here this suspends and can fail loudly, callers should surface [BackfillResult]
     * to the user rather than swallow it, that's the whole point: know for certain, not hope.
     */
    suspend fun backfillSession(
        sessionId: Long,
        measurements: List<MeasurementEntity>,
        locations: List<LocationEntity>,
        events: List<EventEntity>,
    ): BackfillResult =
        withContext(Dispatchers.IO) {
            if (!enabled) {
                return@withContext BackfillResult(false, 0, 0, 0, "Streaming not configured")
            }
            try {
                var measurementCount = 0
                for (chunk in measurements.chunked(BACKFILL_CHUNK_SIZE)) {
                    val arr = JSONArray()
                    for (m in chunk) {
                        arr.put(
                            JSONObject().apply {
                                put("session_id", sessionId)
                                put("sequence", m.sequence)
                                put("wall_time_utc_ms", m.wallTimeUtc)
                                put("elapsed_ns", m.elapsedNs)
                                put("pid", m.pidTag)
                                put("canonical_name", m.canonicalName)
                                put("value_numeric", m.valueNumeric ?: JSONObject.NULL)
                                put("value_text", m.valueText)
                                put("unit", m.unit)
                                put("latency_ms", m.latencyMs)
                                put("quality_flag", m.qualityFlag)
                            },
                        )
                    }
                    executeBulk("/sessions/$sessionId/measurements/bulk", arr)
                    measurementCount += chunk.size
                }

                var locationCount = 0
                for (chunk in locations.chunked(BACKFILL_CHUNK_SIZE)) {
                    val arr = JSONArray()
                    for (l in chunk) {
                        arr.put(
                            JSONObject().apply {
                                put("session_id", sessionId)
                                put("elapsed_ns", l.elapsedNs)
                                put("wall_time_utc_ms", l.wallTimeUtc)
                                put("latitude", l.latitude)
                                put("longitude", l.longitude)
                                put("altitude_m", l.altitudeM ?: JSONObject.NULL)
                                put("speed_mps", l.speedMps ?: JSONObject.NULL)
                                put("bearing_deg", l.bearingDeg ?: JSONObject.NULL)
                                put("horizontal_accuracy_m", l.horizontalAccuracyM ?: JSONObject.NULL)
                                put("provider", l.provider)
                            },
                        )
                    }
                    executeBulk("/sessions/$sessionId/locations/bulk", arr)
                    locationCount += chunk.size
                }

                var eventCount = 0
                for (chunk in events.chunked(BACKFILL_CHUNK_SIZE)) {
                    val arr = JSONArray()
                    for (e in chunk) {
                        arr.put(
                            JSONObject().apply {
                                put("session_id", sessionId)
                                put("elapsed_ns", e.elapsedNs)
                                put("wall_time_utc_ms", e.wallTimeUtc)
                                put("event_type", e.eventType)
                                put("severity", e.severity)
                                put("message", e.message)
                            },
                        )
                    }
                    executeBulk("/sessions/$sessionId/events/bulk", arr)
                    eventCount += chunk.size
                }

                BackfillResult(true, measurementCount, locationCount, eventCount)
            } catch (e: Exception) {
                Log.w(TAG, "Backfill failed: ${e.message}")
                BackfillResult(false, 0, 0, 0, e.message ?: e::class.simpleName)
            }
        }

    /** Synchronous (unlike postFireAndForget), throws on any failure so the caller sees it. */
    private fun executeBulk(path: String, body: JSONArray) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        backfillClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} for $path")
            }
        }
    }

    /** Tells the server backfilled data is complete and safe to analyze. Fire-once, not
     * fire-and-forget: caller should know if this failed to send, since without it the
     * analysis never starts and polling would wait forever. */
    suspend fun requestAnalysis(sessionId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext false
            try {
                val request = Request.Builder()
                    .url("$baseUrl/sessions/$sessionId/analyze")
                    .addHeader("Authorization", "Bearer $token")
                    .post("".toRequestBody(null))
                    .build()
                backfillClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "requestAnalysis failed: ${e.message}")
                false
            }
        }

    suspend fun pollAnalysis(sessionId: Long): AnalysisPollResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/sessions/$sessionId/analysis")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                backfillClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext AnalysisPollResult.Failed("HTTP ${response.code}")
                    val json = JSONObject(response.body?.string() ?: "{}")
                    when (json.optString("status")) {
                        "done" -> {
                            val result = json.getJSONObject("result")
                            val flags = mutableListOf<String>()
                            result.optJSONArray("flags")?.let { arr ->
                                for (i in 0 until arr.length()) flags.add(arr.getString(i))
                            }
                            AnalysisPollResult.Done(
                                AnalysisSummary(
                                    idleFractionPct = result.optDoubleOrNull("idle_fraction_pct"),
                                    warmupMinutes = result.optDoubleOrNull("warmup_minutes"),
                                    distanceGpsKm = result.optDoubleOrNull("distance_gps_km"),
                                    overallMpg = result.optDoubleOrNull("overall_mpg"),
                                    flags = flags,
                                ),
                            )
                        }
                        "failed" -> AnalysisPollResult.Failed(json.optString("error", "unknown error"))
                        else -> AnalysisPollResult.Running // "running" or "not_requested" both just mean keep waiting
                    }
                }
            } catch (e: Exception) {
                AnalysisPollResult.Failed(e.message ?: e::class.simpleName ?: "unknown")
            }
        }
}
