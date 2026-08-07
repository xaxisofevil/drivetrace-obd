package com.ericbarone.drivetrace.service

import com.ericbarone.drivetrace.data.SessionDao
import com.ericbarone.drivetrace.streaming.AnalysisPollResult
import com.ericbarone.drivetrace.streaming.AnalysisSummary
import com.ericbarone.drivetrace.streaming.StreamingClient
import com.ericbarone.drivetrace.streaming.toJson
import kotlinx.coroutines.delay

private const val ANALYSIS_POLL_INTERVAL_MS = 3_000L
private const val ANALYSIS_MAX_POLLS = 20 // ~60s total before giving up

data class BackfillOutcome(
    val backfillSucceeded: Boolean,
    val backfillMessage: String,
    /** "DONE" | "FAILED" | "PENDING" (analysis was never attempted because backfill itself failed) */
    val analysisStatus: String,
    val analysisSummary: AnalysisSummary?,
    val analysisMessage: String?,
)

data class AnalysisOutcome(
    /** "DONE" | "FAILED" */
    val status: String,
    val summary: AnalysisSummary?,
    val message: String?,
    /**
     * True when the failure was this phone not reaching the analysis server, rather than the
     * server reporting that the analysis itself failed. Only the first kind is worth queueing
     * again; re-running an analysis the server has already rejected fails identically, and a
     * worker that returned [androidx.work.ListenableWorker.Result.retry] for it would back off
     * and try forever for no reason.
     */
    val worthRetrying: Boolean,
)

/**
 * Takes a session from "logged locally" to "confirmed uploaded and analyzed", persisting the
 * outcome onto SessionEntity at every step. Shared by the live Stop-button flow
 * (DriveLoggingService) and the background retry sweep (BackfillRetryWorker) so there's exactly
 * one place that knows how to do this, not two copies that could silently drift apart. Confirmed
 * real need for the retry half of this: a driveway test's backfill failed when the home server
 * was unreachable, and there was no durable record anywhere that it still needed uploading once
 * the app closed, see KNOWN_ISSUES.md and BackfillRetryWorker.
 */
suspend fun runBackfillAndAnalysis(
    dao: SessionDao,
    streamingClient: StreamingClient,
    sessionId: Long,
): BackfillOutcome {
    val result = streamingClient.backfillSession(
        sessionId = sessionId,
        measurements = dao.getMeasurements(sessionId),
        locations = dao.getLocations(sessionId),
        events = dao.getEvents(sessionId),
    )

    val backfillMessage = if (result.success) {
        "${result.measurementCount} measurements, ${result.locationCount} GPS, ${result.eventCount} events"
    } else {
        result.error ?: "Backfill failed"
    }
    dao.getSession(sessionId)?.let { s ->
        dao.updateSession(
            s.copy(
                backfillStatus = if (result.success) "SUCCESS" else "FAILED",
                backfillMessage = backfillMessage,
            ),
        )
    }

    if (!result.success) {
        return BackfillOutcome(false, backfillMessage, "PENDING", null, null)
    }

    val analysis = runAnalysisOnly(dao, streamingClient, sessionId)
    return BackfillOutcome(true, backfillMessage, analysis.status, analysis.summary, analysis.message)
}

/**
 * The second half of the above on its own: ask the server to analyze a session it already holds,
 * then poll until it answers. No [StreamingClient.backfillSession] call at all.
 *
 * Split out because backfill succeeding and analysis failing is a real, observed combination, not
 * a theoretical one: the ingest server was up and the analysis server was not, leaving a session
 * at `backfillStatus = SUCCESS, analysisStatus = FAILED`. The only retry path that existed re-ran
 * the whole thing from the top, re-uploading every measurement of a drive the server already had
 * a complete copy of, to get back to the one call that had actually failed. This is that one call.
 *
 * Writes the same fields onto the same row as the full path, so a session analyzed this way is
 * indistinguishable afterwards from one analyzed at Stop.
 */
suspend fun runAnalysisOnly(
    dao: SessionDao,
    streamingClient: StreamingClient,
    sessionId: Long,
): AnalysisOutcome {
    if (!streamingClient.requestAnalysis(sessionId)) {
        val msg = "Could not reach the server to request analysis."
        dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
        return AnalysisOutcome("FAILED", null, msg, worthRetrying = true)
    }

    var pollsLeft = ANALYSIS_MAX_POLLS
    while (pollsLeft > 0) {
        delay(ANALYSIS_POLL_INTERVAL_MS)
        when (val poll = streamingClient.pollAnalysis(sessionId)) {
            is AnalysisPollResult.Done -> {
                dao.getSession(sessionId)?.let { s ->
                    dao.updateSession(s.copy(analysisStatus = "DONE", analysisSummaryJson = poll.summary.toJson()))
                }
                return AnalysisOutcome("DONE", poll.summary, null, worthRetrying = false)
            }
            is AnalysisPollResult.Failed -> {
                dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
                return AnalysisOutcome("FAILED", null, poll.error, worthRetrying = !poll.fromServer)
            }
            is AnalysisPollResult.Running -> Unit // keep polling
        }
        pollsLeft--
    }
    // Worth retrying: the server may simply still be chewing on a long drive, and requesting the
    // analysis again is idempotent from its side.
    val timeoutMsg = "Timed out waiting for analysis; check the PC directly."
    dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
    return AnalysisOutcome("FAILED", null, timeoutMsg, worthRetrying = true)
}
