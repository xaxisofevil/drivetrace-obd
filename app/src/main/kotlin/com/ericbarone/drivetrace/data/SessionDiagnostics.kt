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

// ---------------------------------------------------------------------------
// Diagnostic trouble codes
// ---------------------------------------------------------------------------

private const val EVENT_ONE_TIME_READ = "ONE_TIME_READ"

// kotlin-obd-api's own command tags, read off the library's compiled TroubleCodes classes rather
// than assumed. Note these are NOT the CURRENT_DTCS / PENDING_DTCS / PERMANENT_DTCS names
// docs/DATA_SCHEMA.md used to imply; the tags on the wire have always been these.
private const val TAG_CURRENT_DTCS = "TROUBLE_CODES"
private const val TAG_PENDING_DTCS = "PENDING_TROUBLE_CODES"
private const val TAG_PERMANENT_DTCS = "PERMANENT_TROUBLE_CODES"

/**
 * The three DTC sets read once at session start, recovered from the `ONE_TIME_READ` events they
 * were logged as. Nothing new is read from the vehicle here; this data has been captured every
 * session since the app was written and has never been shown to anyone.
 *
 * [read] separates "the ECU reported no stored codes" from "the DTC read never succeeded", which
 * matter very differently to a user and would otherwise both look like an empty list.
 */
data class DtcReport(
    /** Confirmed faults; the ones that turn the check-engine light on. */
    val current: List<String>,
    /** Seen once, not yet confirmed across enough drive cycles to light the lamp. */
    val pending: List<String>,
    /** Cleared from memory but retained until the ECU's own monitors pass. Not erasable with a
     *  scan tool, which is the whole point of the category. */
    val permanent: List<String>,
    /** Whether any of the three reads actually came back. */
    val read: Boolean,
) {
    val isEmpty: Boolean get() = current.isEmpty() && pending.isEmpty() && permanent.isEmpty()
    val total: Int get() = current.size + pending.size + permanent.size
}

suspend fun readSessionDtcs(context: Context, sessionId: Long): DtcReport =
    withContext(Dispatchers.IO) {
        val events = AppDatabase.getInstance(context).sessionDao()
            .getEventsOfTypes(sessionId, listOf(EVENT_ONE_TIME_READ))
        DtcReport(
            current = codesFor(events, TAG_CURRENT_DTCS),
            pending = codesFor(events, TAG_PENDING_DTCS),
            permanent = codesFor(events, TAG_PERMANENT_DTCS),
            read = listOf(TAG_CURRENT_DTCS, TAG_PENDING_DTCS, TAG_PERMANENT_DTCS)
                .any { tag -> events.any { it.message.startsWith("$tag=") } },
        )
    }

/**
 * ONE_TIME_READ's message is `"$tag=$value | raw=$verbatimElmText"` (DriveLoggingService), and
 * the library joins a trouble-code list with commas, so the value is `"P0171,P0300"` or empty.
 *
 * Takes the *last* matching event on purpose: a Bluetooth reconnect re-runs the whole one-time
 * read block, so a session with a dropped link has several of these and the newest is the one
 * that reflects the vehicle now.
 */
private fun codesFor(events: List<EventEntity>, tag: String): List<String> {
    val prefix = "$tag="
    val event = events.lastOrNull { it.message.startsWith(prefix) } ?: return emptyList()
    return event.message
        .removePrefix(prefix)
        .substringBefore(" | raw=")
        .split(',')
        .map { it.trim().uppercase() }
        // P0000 is the standard's "no code here" padding, and the library already truncates at
        // the first one; filtered again here because the raw value is not guaranteed to be clean.
        .filter { it.isNotBlank() && it != "P0000" }
        .distinct()
}

/**
 * PID_NO_DATA's message is `"${command.tag}: $exception"` (PidScheduler.pollOne), so the tag is
 * everything before the first colon. Parsed rather than stored in its own column because the
 * events table is a shared schema across Room, the CSV bundle and DuckDB (docs/DATA_SCHEMA.md);
 * adding a column for one screen's convenience would mean changing all three.
 */
private fun pidTagOf(message: String): String =
    message.substringBefore(':').trim().ifBlank { "unknown" }
