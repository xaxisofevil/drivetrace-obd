package com.ericbarone.drivetrace.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    // Deliberately NOT autoGenerate: an autoincrement counter restarts from 1 after any local
    // Room wipe (a schema-version bump's destructive migration, an app data clear, a device
    // change), and this ID is also the server's session_id, so a restarted counter collides with
    // whatever history already used that number there. The server's backfill is a delete-then-
    // insert full replace keyed on this ID (see server/README.md), so a collision doesn't just
    // create confusing duplicate data, it silently and permanently destroys the older session.
    // Confirmed happening for real: a Room wipe here overwrote an original session 1 with a new
    // drive that reused the same ID. Caller must supply a value derived from wall-clock time
    // (e.g. System.currentTimeMillis()), which two sessions can only collide on if they started
    // in the exact same millisecond, not realistic for a manually-started single-device app.
    @PrimaryKey val sessionId: Long,
    val startWallTimeUtc: Long,
    val startElapsedNs: Long,
    var endWallTimeUtc: Long? = null,
    val vehicleProfile: String,
    var adapterName: String? = null,
    var adapterAddress: String? = null,
    var protocol: String? = null,
    val appVersion: String,
    val phoneModel: String,
    var notes: String? = null,
    var completionStatus: String = "IN_PROGRESS",
    /** Persisted (not just held in the ephemeral in-memory LoggingUiState) so a failed upload
     * survives the app being closed: "PENDING" | "SUCCESS" | "FAILED". Confirmed real need: a
     * driveway test's data got stranded with no durable record that it still needed uploading,
     * the only way to notice was checking manually. See BackfillRetryWorker. */
    var backfillStatus: String = "PENDING",
    var backfillMessage: String? = null,
    /** "PENDING" | "DONE" | "FAILED"; DONE only once the server's analysis actually completed. */
    var analysisStatus: String = "PENDING",
    /** The AnalysisSummary result, serialized as JSON so trip history can show real numbers
     * without needing a live round-trip to the server every time the list is viewed. */
    var analysisSummaryJson: String? = null,
)

@Entity(
    tableName = "measurements",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sequence: Long,
    val wallTimeUtc: Long,
    val elapsedNs: Long,
    val pidTag: String,
    val canonicalName: String,
    val valueNumeric: Double?,
    val valueText: String?,
    val unit: String,
    val latencyMs: Long,
    val qualityFlag: String,
    /** Verbatim ELM response text for this exact read, before the library's own cleanup.
     * Null for older rows written before this field existed. */
    val rawResponse: String? = null,
)

@Entity(
    tableName = "locations",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedNs: Long,
    val wallTimeUtc: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val horizontalAccuracyM: Float?,
    val provider: String?,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedNs: Long,
    val wallTimeUtc: Long,
    val eventType: String,
    val severity: String,
    val message: String,
    val detailsJson: String? = null,
)
