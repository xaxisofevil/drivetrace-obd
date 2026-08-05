package com.ericbarone.drivetrace.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
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
