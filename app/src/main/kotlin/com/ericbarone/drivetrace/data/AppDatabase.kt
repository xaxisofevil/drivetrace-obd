package com.ericbarone.drivetrace.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, MeasurementEntity::class, LocationEntity::class, EventEntity::class],
    version = 2, // v2: MeasurementEntity gained rawResponse (see KNOWN_ISSUES.md, raw-capture)
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
