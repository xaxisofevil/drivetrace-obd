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
 *
 * It also runs a narrower job on request: with [KEY_ANALYSIS_ONLY] set it skips the upload and
 * only asks the server to analyze a session it already holds. Also a confirmed real case, not a
 * theoretical one: a session ended up at `backfillStatus = SUCCESS, analysisStatus = FAILED`
 * because the ingest server was running and the analysis server was not, and the only retry that
 * existed re-sent every measurement of a drive the server already had in full just to reach the
 * one call that had actually failed.
 */
class BackfillRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).sessionDao()
        val streamingClient = StreamingClient(BuildConfig.INGEST_BASE_URL, BuildConfig.INGEST_TOKEN)

        val targetId = inputData.getLong(KEY_SESSION_ID, -1L).takeIf { it != -1L }

        // Analysis-only: the upload already landed and only the server-side analysis needs asking
        // for again. Always scoped to one session, since the only thing that queues it is a
        // per-session control on the logbook.
        if (targetId != null && inputData.getBoolean(KEY_ANALYSIS_ONLY, false)) {
            val session = dao.getSession(targetId) ?: return Result.success()
            if (session.backfillStatus == "SUCCESS") {
                val outcome = runAnalysisOnly(dao, streamingClient, targetId)
                return if (outcome.status != "DONE" && outcome.worthRetrying) Result.retry() else Result.success()
            }
            // The server does not have the data to analyze after all (the row changed between the
            // button rendering and this running). Fall through and do the full job.
        }

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

        /**
         * Skip [runBackfillAndAnalysis] and run only the analysis half. A flag on this worker
         * rather than a lighter foreground-only path, because everything that makes this worker
         * worth having applies just as much to the narrow case: the analysis poll loop runs for
         * up to a minute, so a coroutine tied to the logbook screen would be cancelled by the
         * user simply navigating away, and the network constraint, the expedited request and the
         * exponential backoff all come for free here and would all have to be rebuilt there.
         * Reusing the worker also means one place decides what a retry is, rather than two that
         * can drift.
         */
        const val KEY_ANALYSIS_ONLY = "analysis_only"

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

        /** Same idea, for the analysis-only retry. A separate name rather than the same one, so
         * an analysis retry can never REPLACE an upload retry that is still in flight for the
         * same session, and so each button watches only its own work. */
        fun analysisRetryWorkName(sessionId: Long): String = "analysis_retry_$sessionId"

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

        /**
         * User-triggered "analyze it again" for a session whose upload already succeeded. Skips
         * [runBackfillAndAnalysis] entirely, so a drive whose measurements are already sitting on
         * the server does not get re-uploaded in full to reach the one call that failed.
         */
        fun enqueueAnalysisRetryNow(context: Context, sessionId: Long) {
            val data = Data.Builder()
                .putLong(KEY_SESSION_ID, sessionId)
                .putBoolean(KEY_ANALYSIS_ONLY, true)
                .build()
            val request = OneTimeWorkRequestBuilder<BackfillRetryWorker>()
                .setInputData(data)
                .setConstraints(networkConstraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(analysisRetryWorkName(sessionId), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
