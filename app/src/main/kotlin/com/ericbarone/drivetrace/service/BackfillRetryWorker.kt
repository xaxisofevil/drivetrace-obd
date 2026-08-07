package com.ericbarone.drivetrace.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.ericbarone.drivetrace.BuildConfig
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.streaming.StreamingClient
import java.util.concurrent.TimeUnit

/**
 * Retries backfill (and the analysis it unlocks) for any session that didn't finish uploading,
 * even after the app that logged it has been closed or the phone rebooted. WorkManager persists
 * this work request in its own system-level store, independent of this app's process, and Android
 * will start the app just to run it once the network constraint below is satisfied.
 *
 * Confirmed real need: a driveway test's 876 measurements got stranded locally when the home
 * server was unreachable at Stop time (see KNOWN_ISSUES.md), with no way to retry short of
 * pulling the Room DB by hand over adb. This is the fix.
 */
class BackfillRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).sessionDao()
        val streamingClient = StreamingClient(BuildConfig.INGEST_BASE_URL, BuildConfig.INGEST_TOKEN)

        val targetId = inputData.getLong(KEY_SESSION_ID, -1L).takeIf { it != -1L }
        val sessions = if (targetId != null) {
            listOfNotNull(dao.getSession(targetId))
        } else {
            dao.getSessionsNeedingBackfill()
        }
        if (sessions.isEmpty()) return Result.success()

        var anyStillFailing = false
        for (session in sessions) {
            val outcome = runBackfillAndAnalysis(dao, streamingClient, session.sessionId)
            if (!outcome.backfillSucceeded) anyStillFailing = true
        }
        // Let WorkManager's own exponential backoff handle re-scheduling rather than looping or
        // sleeping in here: a genuinely offline phone should back off, not hammer retries.
        return if (anyStillFailing) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        private const val SWEEP_WORK_NAME = "backfill_retry_sweep"

        private fun networkConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Opportunistic sweep of every not-yet-uploaded session. Safe to call often (app
         * startup, a failed live backfill) since ExistingWorkPolicy.KEEP means a sweep already
         * queued or running just absorbs the request instead of stacking duplicates. */
        fun enqueueSweep(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackfillRetryWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(SWEEP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The unique work name a user-triggered retry for [sessionId] is queued under. Public
         * because the logbook observes this exact name through
         * `WorkManager.getWorkInfosForUniqueWorkFlow` to know whether a retry is still in flight,
         * rather than keeping a boolean of its own: WorkManager already tracks that, durably and
         * across process death, and a hand-rolled flag would drift from it the first time the app
         * got killed mid-retry (it would come back looking idle while the work was still queued).
         */
        fun retryWorkName(sessionId: Long): String = "backfill_retry_$sessionId"

        /** User-triggered "retry now" for one specific session (Trip History screen). REPLACE +
         * expedited so it runs promptly rather than waiting behind an opportunistic sweep. */
        fun enqueueRetryNow(context: Context, sessionId: Long) {
            val data = Data.Builder().putLong(KEY_SESSION_ID, sessionId).build()
            val request = OneTimeWorkRequestBuilder<BackfillRetryWorker>()
                .setInputData(data)
                .setConstraints(networkConstraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(retryWorkName(sessionId), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
