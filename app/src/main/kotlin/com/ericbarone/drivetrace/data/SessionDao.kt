package com.ericbarone.drivetrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startWallTimeUtc DESC")
    suspend fun getAllSessions(): List<SessionEntity>

    /** Anything not yet confirmed uploaded, oldest first, so a long-stranded session doesn't
     * get starved behind newer ones during an automatic retry sweep. */
    @Query("SELECT * FROM sessions WHERE backfillStatus != 'SUCCESS' ORDER BY startWallTimeUtc ASC")
    suspend fun getSessionsNeedingBackfill(): List<SessionEntity>

    @Insert
    suspend fun insertMeasurement(measurement: MeasurementEntity)

    @Insert
    suspend fun insertLocation(location: LocationEntity)

    @Insert
    suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM measurements WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun getMeasurements(sessionId: Long): List<MeasurementEntity>

    @Query("SELECT * FROM locations WHERE sessionId = :sessionId ORDER BY elapsedNs ASC")
    suspend fun getLocations(sessionId: Long): List<LocationEntity>

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY elapsedNs ASC")
    suspend fun getEvents(sessionId: Long): List<EventEntity>

    @Query("SELECT COUNT(*) FROM measurements WHERE sessionId = :sessionId")
    suspend fun getMeasurementCount(sessionId: Long): Int
}
