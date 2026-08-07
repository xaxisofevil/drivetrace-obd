package com.ericbarone.drivetrace.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-models derived from the events table. Nothing here collects new data: every number below
 * already gets written on every session (see docs/DATA_SCHEMA.md's events table), it just never
 * had anywhere to be read from.
 */

private const val EVENT_PID_NO_DATA = "PID_NO_DATA"
private const val EVENT_PID_COOLDOWN = "PID_COOLDOWN"

/** Enough to name the problem PIDs without turning the trip report into a log viewer. */
private const val MAX_OFFENDERS_SHOWN = 3

/**
 * How well the adapter actually held up over one drive, from the failure events the scheduler
 * already logs. A flaky ELM327 clone is otherwise completely invisible to the user: the samples
 * counter only counts what succeeded, so a drive that dropped half its reads looks identical to
 * a clean one apart from being thinner in the CSV nobody opens.
 *
 * Deliberately reports *distinct PIDs* alongside raw failure count: one PID failing 200 times is
 * an unsupported PID cycling through cooldown (cheap, expected, see KNOWN_ISSUES.md), while eight
 * different PIDs failing a handful of times each is an adapter or a link problem.
 */
data class AdapterHealth(
    /** How many different PIDs failed at least once. */
    val distinctPidsDropped: Int,
    /** Every failed read attempt, logged individually by PidScheduler. */
    val failedReads: Int,
    /** How many times a PID hit two consecutive failures and paused for 30s. */
    val cooldowns: Int,
    /** Highest-failure PID tags first, at most [MAX_OFFENDERS_SHOWN] of them. */
    val worstOffenders: List<Pair<String, Int>>,
) {
    val isClean: Boolean get() = failedReads == 0
}

suspend fun computeAdapterHealth(context: Context, sessionId: Long): AdapterHealth =
    withContext(Dispatchers.IO) {
        val events = AppDatabase.getInstance(context).sessionDao()
            .getEventsOfTypes(sessionId, listOf(EVENT_PID_NO_DATA, EVENT_PID_COOLDOWN))

        val perTag = events
            .filter { it.eventType == EVENT_PID_NO_DATA }
            .groupingBy { pidTagOf(it.message) }
            .eachCount()

        AdapterHealth(
            distinctPidsDropped = perTag.size,
            failedReads = perTag.values.sum(),
            cooldowns = events.count { it.eventType == EVENT_PID_COOLDOWN },
            worstOffenders = perTag.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_OFFENDERS_SHOWN)
                .map { it.key to it.value },
        )
    }

/**
 * PID_NO_DATA's message is `"${command.tag}: $exception"` (PidScheduler.pollOne), so the tag is
 * everything before the first colon. Parsed rather than stored in its own column because the
 * events table is a shared schema across Room, the CSV bundle and DuckDB (docs/DATA_SCHEMA.md);
 * adding a column for one screen's convenience would mean changing all three.
 */
private fun pidTagOf(message: String): String =
    message.substringBefore(':').trim().ifBlank { "unknown" }
