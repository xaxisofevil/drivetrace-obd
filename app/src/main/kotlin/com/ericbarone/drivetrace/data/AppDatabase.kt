package com.ericbarone.drivetrace.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, MeasurementEntity::class, LocationEntity::class, EventEntity::class],
    // v2: MeasurementEntity gained rawResponse (see KNOWN_ISSUES.md, raw-capture).
    // v3: SessionEntity.sessionId dropped autoGenerate (see KNOWN_ISSUES.md, ID collision fix).
    // v4: SessionEntity gained backfillStatus/backfillMessage/analysisStatus/analysisSummaryJson
    // (trip history + retry-after-exit, see BackfillRetryWorker/HistoryScreen).
    // Schema changes alter Room's computed identity hash even with exportSchema=false,
    // exportSchema only controls whether the schema JSON gets written to disk for migration
    // tooling, it does NOT skip the runtime identity check against room_master_table. Forgetting
    // to bump the version here crashed the app with "Room cannot verify the data integrity" the
    // moment any DB write happened (pressing Start Logging), confirmed via a real crash log.
    //
    // IMPORTANT before ever bumping this again: fallbackToDestructiveMigration wipes local Room
    // on any version change. Confirmed this bit for real once already (a Room wipe collided a
    // restarted session-ID counter with old server history, destroying it, see KNOWN_ISSUES.md's
    // "Session ID collision" entry) and there is, as of this v3->v4 bump, a real driveway-test
    // session sitting locally that never finished backfilling to the server (network was down at
    // Stop time). That session MUST be pulled and manually backfilled before this version ships
    // to the phone, or its data is gone for good the moment the app updates.
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drivetrace.db",
                )
                    // No real migration story yet, this is a dev-stage app with a handful of
                    // sessions logged so far, all already backfilled to the server (the actual
                    // source of truth once a drive completes). Destructively rebuilding local
                    // Room on a schema bump is an acceptable shortcut here; it is NOT before any
                    // real user's data depends on this device being the only copy, see
                    // COMMERCIAL_READINESS.md.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
