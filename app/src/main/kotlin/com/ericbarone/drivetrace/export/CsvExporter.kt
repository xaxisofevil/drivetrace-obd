package com.ericbarone.drivetrace.export

import android.content.Context
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.data.EventEntity
import com.ericbarone.drivetrace.data.LocationEntity
import com.ericbarone.drivetrace.data.MeasurementEntity
import com.ericbarone.drivetrace.data.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dumps a session's raw data to the export bundle described in the blueprint (section 8):
 * samples_long.csv is the source of truth, everything else is convenience/context. Raw values
 * are exported as-is; derived metrics (MPG, combined trim, etc.) are computed later by the
 * offline Python analysis script, not in the app.
 */
class CsvExporter(private val context: Context) {

    suspend fun export(sessionId: Long): File =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val dao = db.sessionDao()
            val session = dao.getSession(sessionId) ?: error("Session $sessionId not found")
            val measurements = dao.getMeasurements(sessionId)
            val locations = dao.getLocations(sessionId)
            val events = dao.getEvents(sessionId)

            val exportDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(session.startWallTimeUtc))
            val zipFile = File(exportDir, "drivetrace_${stamp}_session-$sessionId.zip")

            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.writeEntry("metadata.json", buildMetadataJson(session, measurements.size, locations.size))
                zip.writeEntry("samples_long.csv", buildSamplesCsv(measurements))
                zip.writeEntry("locations.csv", buildLocationsCsv(locations))
                zip.writeEntry("events.csv", buildEventsCsv(events))
            }
            zipFile
        }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun buildMetadataJson(session: SessionEntity, measurementCount: Int, locationCount: Int): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return """
            {
              "sessionId": ${session.sessionId},
              "startWallTimeUtc": "${iso.format(Date(session.startWallTimeUtc))}",
              "endWallTimeUtc": ${session.endWallTimeUtc?.let { "\"${iso.format(Date(it))}\"" } ?: "null"},
              "vehicleProfile": "${session.vehicleProfile}",
              "adapterName": ${session.adapterName?.let { "\"$it\"" } ?: "null"},
              "adapterAddress": ${session.adapterAddress?.let { "\"$it\"" } ?: "null"},
              "protocol": ${session.protocol?.let { "\"$it\"" } ?: "null"},
              "appVersion": "${session.appVersion}",
              "phoneModel": "${session.phoneModel}",
              "notes": ${session.notes?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"},
              "completionStatus": "${session.completionStatus}",
              "measurementCount": $measurementCount,
              "locationCount": $locationCount
            }
        """.trimIndent()
    }

    private fun buildSamplesCsv(measurements: List<MeasurementEntity>): String =
        buildString {
            appendLine(
                "sequence,wall_time_utc_ms,elapsed_ns,pid,canonical_name,value_numeric,value_text,unit,latency_ms,quality_flag,raw_response",
            )
            for (m in measurements) {
                appendLine(
                    listOf(
                        m.sequence,
                        m.wallTimeUtc,
                        m.elapsedNs,
                        m.pidTag,
                        csvSafe(m.canonicalName),
                        m.valueNumeric ?: "",
                        m.valueText?.let { csvSafe(it) } ?: "",
                        csvSafe(m.unit),
                        m.latencyMs,
                        m.qualityFlag,
                        m.rawResponse?.let { csvSafe(it) } ?: "",
                    ).joinToString(","),
                )
            }
        }

    private fun buildLocationsCsv(locations: List<LocationEntity>): String =
        buildString {
            appendLine(
                "elapsed_ns,wall_time_utc_ms,latitude,longitude,altitude_m,speed_mps,bearing_deg,horizontal_accuracy_m,provider",
            )
            for (l in locations) {
                appendLine(
                    listOf(
                        l.elapsedNs,
                        l.wallTimeUtc,
                        l.latitude,
                        l.longitude,
                        l.altitudeM ?: "",
                        l.speedMps ?: "",
                        l.bearingDeg ?: "",
                        l.horizontalAccuracyM ?: "",
                        l.provider ?: "",
                    ).joinToString(","),
                )
            }
        }

    private fun buildEventsCsv(events: List<EventEntity>): String =
        buildString {
            appendLine("elapsed_ns,wall_time_utc_ms,event_type,severity,message")
            for (e in events) {
                appendLine(
                    listOf(
                        e.elapsedNs,
                        e.wallTimeUtc,
                        csvSafe(e.eventType),
                        e.severity,
                        csvSafe(e.message),
                    ).joinToString(","),
                )
            }
        }

    private fun csvSafe(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
