package com.ericbarone.drivetrace.ui

/**
 * What the upload pipeline is allowed to say on a screen.
 *
 * The trip report used to render `SessionEntity.backfillMessage` verbatim, which on the failure
 * path is `Throwable.message` straight out of OkHttp. That string names the server: on a real
 * phone it read `failed to connect to ericb.duckdns.org/47.200.186.46 (port 8090) from
 * /172.18.11.218 (port 43348) after 10000ms`, i.e. the user's home network's hostname, public IP,
 * open port and the phone's LAN address, painted at full width in fault red on a screen anyone
 * holding the phone can read and that gets screenshotted into forum posts and bug reports.
 *
 * The rule this file exists to enforce is a **whitelist, not a filter.** Nothing here tries to
 * redact hostnames or IPs out of an error string; that kind of scrubbing works until the day the
 * exception format changes and then it leaks silently. Instead: a message the app composed itself
 * out of known parts may be shown, and anything originating in the transport layer is replaced
 * wholesale by a fixed sentence. There is no code path that passes a caught exception's text
 * through to a composable.
 *
 * **The diagnostic detail is not destroyed, only unpublished.** The raw string is still written to
 * `SessionEntity.backfillMessage` by `BackfillCoordinator` (Room, on-device, and deliberately not
 * one of the fields `CsvExporter` writes into `metadata.json`, so it never reaches an export
 * bundle either) and still logged by `StreamingClient` as `Backfill failed: …`. A developer with
 * adb or a database dump can still see exactly which host timed out; a passer-by with the phone
 * cannot.
 */

/**
 * The only thing a failed upload says out loud. Deliberately about consequences rather than
 * causes: "will retry" is the part that changes what the user does, and the retry claim is true
 * rather than reassuring noise, because `BackfillRetryWorker` really does sweep this session
 * again on network-regained and on next launch.
 */
const val UPLOAD_FAILED_MESSAGE =
    "Couldn't reach the server. This drive is saved on the phone and will upload on its own."

/**
 * Detail line for the upload stage. [uploaded] is null while the attempt is still in flight.
 *
 * The success message survives because the app wrote it: `BackfillCoordinator` composes it from
 * three integers it counted itself ("412 measurements, 88 GPS, 19 events"), so there is no
 * transport text in it and it is genuinely the useful confirmation that the whole drive landed
 * rather than a truncated prefix of it.
 */
fun uploadDetail(uploaded: Boolean?, rawMessage: String?): String? = when (uploaded) {
    true -> rawMessage?.takeIf { it.isNotBlank() }
    false -> UPLOAD_FAILED_MESSAGE
    null -> null
}
