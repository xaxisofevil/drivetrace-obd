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

    if (!streamingClient.requestAnalysis(sessionId)) {
        val msg = "Could not reach the server to request analysis."
        dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
        return BackfillOutcome(true, backfillMessage, "FAILED", null, msg)
    }

    var pollsLeft = ANALYSIS_MAX_POLLS
    while (pollsLeft > 0) {
        delay(ANALYSIS_POLL_INTERVAL_MS)
        when (val poll = streamingClient.pollAnalysis(sessionId)) {
            is AnalysisPollResult.Done -> {
                dao.getSession(sessionId)?.let { s ->
                    dao.updateSession(s.copy(analysisStatus = "DONE", analysisSummaryJson = poll.summary.toJson()))
                }
                return BackfillOutcome(true, backfillMessage, "DONE", poll.summary, null)
            }
            is AnalysisPollResult.Failed -> {
                dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
                return BackfillOutcome(true, backfillMessage, "FAILED", null, poll.error)
            }
            is AnalysisPollResult.Running -> Unit // keep polling
        }
        pollsLeft--
    }
    val timeoutMsg = "Timed out waiting for analysis; check the PC directly."
    dao.getSession(sessionId)?.let { s -> dao.updateSession(s.copy(analysisStatus = "FAILED")) }
    return BackfillOutcome(true, backfillMessage, "FAILED", null, timeoutMsg)
}
