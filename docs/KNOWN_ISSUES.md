# Known issues and gotchas

Things found through actual testing tonight, not theoretical. Read this
before assuming a weird result is a new bug.

## kotlin-obd-api byte-overflow bug (root-caused, mostly fixed)

Several of the library's command handlers call
`bytesToInt(response.bufferedValue)` without bounding `bytesToProcess`,
which defaults to `-1` and skips the length cap entirely, folding every
byte in the whole cleaned response into one number instead of just the
PID's real 2 data bytes. Confirmed directly: RPM read back as 3.8 trillion,
module voltage as tens of billions, distance-since-codes-cleared as
hundreds of trillions, on a real drive.

- `RPMCommand` is fixed on the library's **unreleased master branch**
  (commit `30014eb6e8cd35334ba8f7ea627500f6b1942ff5`). The dependency in
  `app/build.gradle.kts` is pinned to that exact commit via JitPack instead
  of the latest tag (`v1.4.1`, which predates the fix).
- `ModuleVoltageCommand`, `MassAirFlowCommand`, `FuelConsumptionRateCommand`,
  `FuelRailPressureCommand`, and `DistanceSinceCodesClearedCommand` all have
  the identical bug, **still unfixed even at that commit**. Local
  replacements live in `app/src/main/kotlin/.../obd/SafeCommands.kt`, same
  tag/name/mode/pid/unit as the originals, correct bounded math.
- **Every PID's value still gets clamped to a plausible range regardless**
  (`PLAUSIBLE_RANGES` in `PidScheduler.kt`), kept as defense-in-depth in
  case another command has the same bug that hasn't surfaced yet. An
  `IMPLAUSIBLE` quality_flag means the clamp caught something, not that
  everything's fine, check `pid_coverage_report` for how often it's firing.
- **This dependency is fragile.** Pinning to an arbitrary commit via
  JitPack means the dependency isn't a stable, citable release; if the
  upstream repo's history gets rewritten (rebase, force-push) that commit
  could become unresolvable. Before any production/commercial use, either
  wait for (or push for) a real tagged release containing the fix, or fork
  and vendor the specific fixed files. See
  [COMMERCIAL_READINESS.md](COMMERCIAL_READINESS.md).

## PID cooldown, not permanent drop (fixed)

Before: a PID that failed twice in a row was permanently dropped for the
rest of the session. Confirmed directly: long-term fuel trim failed twice
17 seconds into a real drive (adapter likely still settling right after
connect) and was never retried for the remaining ~8.7 minutes, despite the
vehicle genuinely supporting it (confirmed via other OBD apps).

Now: 2 consecutive failures triggers a 30-second cooldown, then automatic
retry (`PID_COOLDOWN` event, not `PID_UNSUPPORTED`). A genuinely
unsupported PID just cycles through cooldown at near-zero cost.

Same fix applied to `pc_logger/scheduler.py`. While fixing that file, also
found and fixed a real standalone bug: `tier_a`/`tier_b`/`tier_c`
initialization had been stranded as unreachable dead code inside the
`sequence` property (from an earlier edit), meaning `PidScheduler` would
have crashed immediately on first real use. Never caught before because
pc_logger hadn't been run against real hardware.

**Update, session 7 (post-fix)**: long-term fuel trim still failed **every
single attempt**, 6 cooldown cycles across the whole drive, always
`NoDataException`, never once succeeded. This is a different picture than
the drive that motivated the fix, there, the same PID eventually succeeded
after a couple of cooldown retries. The cooldown mechanism is confirmed
working as designed (it did keep retrying instead of giving up, exactly
every ~31s, matching `COOLDOWN_NS`), but *why* LTFT specifically won't
return data at all in some sessions while STFT on the same command family
works fine was still open as of that session.

**Investigated further**: pulled `FuelTrimCommand`'s source at the pinned
commit. Short-term and long-term trim are the exact same class, same
single-byte parsing formula, differing only by PID (`06` vs. `08`), so
this isn't a parsing bug distinguishing the two, ruling that out. The
first LTFT failure in session 7 came at ~5 seconds into the drive and it
never once succeeded across the full ~183-second session, so it's not the
"adapter needed a few seconds to settle after connect" explanation either,
that would predict eventual success, not zero-for-six. The leading
explanation now is a genuine vehicle/ECU condition (this specific short,
mostly-idle, already-warm-from-a-prior-drive trip may never have reached
whatever closed-loop/adaptation state this ECU requires before it'll
answer PID 08), not a library or app bug, but this is not confirmed.

**What would confirm it**: the raw-capture fix below now records the exact
ELM response text for every failed attempt (event type `PID_NO_DATA`), not
just the exception class name. If it happens again, check whether the
adapter is returning literal `NO DATA` (genuine ECU non-response, consistent
with the vehicle-condition theory) versus something else being
misclassified as `NoDataException` (would point back to a library/parsing
issue after all). Session 7 predates this fix, so its own raw text was
never captured, this can only be resolved on the next drive where LTFT
fails again.

**Confirmed, two drives later**: both post-fix drives show the adapter
returning literal `response [NO DATA]` for every single LONG_TERM_BANK_1
attempt, no exceptions. One was a ~13-minute drive with a large real-data
gap (see the battery-optimization issue below) so partly inconclusive; the
other was a full, clean ~16.4-minute drive with continuous Tier A coverage
throughout (STFT succeeded ~800 times in that same window) and LTFT still
failed literally every attempt (93 for 93). This rules out a parsing
misclassification: the adapter is genuinely telling this app that the
ECU's response to Mode 01 PID 08 is `NO DATA`, consistently, regardless of
drive length or conditions, on these two drives specifically.

**Not settled, though**: the user has directly seen LTFT populated in
other apps on this same car before. That's real, credible counter-evidence
and outweighs two drives' worth of "NO DATA" from this app alone, it does
NOT mean the ECU doesn't have the data, only that Mode 01 PID 08 hasn't
produced it here yet. Possible explanations, none confirmed: those apps
read it under conditions these two drives didn't hit (e.g. sustained
highway cruise long enough for full closed-loop trim adaptation, whereas
both drives here were city/backroad-heavy); those apps use a different,
possibly manufacturer-specific PID or mode instead of generic Mode 01 PID
08; or something specific to this adapter/firmware mishandles PID 08 while
a genuine ELM327 or different clone (whatever the other apps were paired
with) doesn't. Added `AvailablePIDsCommand` (all five PID-support ranges,
Mode 01 PID 00/20/40/60/80) as a one-time read to get the ECU's own
declared list of supported PIDs, this settles definitively whether PID 08
is even claimed as supported by this ECU, independent of whether this
adapter can successfully retrieve it. Check that result on the next drive
before drawing any further conclusion.

## Missing GPS/OBD data for most of a drive: OnePlus battery optimization

A ~13-minute drive logged with the screen off showed GPS and OBD data only
for the last ~50 seconds, despite the scheduler loop clearly running the
whole time (continuous `PID_NO_DATA`/`PID_COOLDOWN` events for LTFT/fuel
rail/fuel consumption throughout). Root cause: OnePlus's OxygenOS layers
its own aggressive background-process/network throttling on top of stock
Android's, which can suppress GPS and Bluetooth I/O even from a correctly
declared foreground service (`connectedDevice|location` foreground service
type was already correctly set in the manifest, this wasn't a missing-
permission issue). Fixed by disabling battery optimization for the app in
OnePlus's settings (Settings → Battery → DriveTrace → "Don't optimize",
plus OnePlus's separate "Advanced/deep optimization" toggle if present).
Confirmed fixed: the next drive, same phone, same settings otherwise,
covered the full session with no gap. Worth adding to setup instructions
for any OnePlus (or likely other aggressive-OEM) device before a real
diagnostic drive.

## Backfill chunking silently destroyed all but the last chunk (found and fixed)

**Severe, confirmed via direct reproduction.** `/sessions/{id}/measurements
(/locations/events)/bulk` deleted all of a session's existing rows, then
inserted, on *every* call, not once per full reconciliation. The Android
client (and PC-side migration tooling) splits a backfill into chunks of
`BACKFILL_CHUNK_SIZE = 500`, sending one bulk call per chunk. With more
than one chunk, each chunk's own DELETE wiped out every earlier chunk's
INSERT, so only the final chunk survived. Verified directly: posted two
one-row chunks for a throwaway session id, only the second row remained
after both calls completed successfully (no error, no indication anything
was wrong). Then confirmed it was the actual cause of a real, badly wrong
result: a genuine ~16-minute, 5881-measurement drive backfilled down to
just its last 381 rows (the final ~63 seconds, mostly stopped), producing
an analysis of 100% idle fraction and 259 MPG. After the fix, the same
drive's full 5881/987/231 rows landed correctly and analyzed to a normal,
plausible 25.9 MPG with GPS and OBD distance agreeing to within 20m over
7.7km.

Any historical session over 500 measurements should, in principle, have
been vulnerable to this same truncation on its own original backfill.
Checked all of them directly (`SELECT session_id, COUNT(*), MIN/MAX(elapsed_ns)
FROM measurements GROUP BY session_id`): sessions 2, 4, 5, and 7 all show a
measurement count and elapsed-time span consistent with a complete,
untruncated drive (not just a short tail), so they appear unaffected in
practice, this bug's precise trigger conditions (how much of a role the
live per-item stream played in already having the data present before a
truncating backfill overwrote-then-reinserted the same rows) weren't fully
pinned down, only decisively reproduced and fixed. Don't assume a session
that predates this fix is trustworthy purely by size; spot-check
`MIN(elapsed_ns)` vs. the session's real duration before relying on one
that's suspiciously small for its `end_wall_time_utc_ms - start_wall_time_utc_ms`.

**Fix**: `/bulk` endpoints now take `{"items": [...], "is_first_chunk":
bool}` instead of a bare array; the DELETE only runs when `is_first_chunk`
is true. Android's `StreamingClient.backfillSession()` sets this per chunk
index. Any other client hitting these endpoints directly (ad hoc scripts,
future PC-logger server integration) must do the same or it will silently
reproduce this exact bug again.

## Session ID collision after a local Room wipe (found and fixed)

`SessionEntity.sessionId` used to be a Room `autoGenerate` autoincrement
column, which restarts from 1 after any local wipe (an app-schema-version
bump's destructive migration, a data clear, a device change). The server's
session_id is the same number, and its backfill is delete-then-insert
full-replace keyed on it, so a restarted local counter colliding with an
already-used server-side id doesn't create a duplicate, it **silently and
permanently destroys the older session's data**. Confirmed happening for
real: a Room wipe (done here to add the `rawResponse` column, see below)
reset the counter to 1, and the next drive's backfill overwrote an original
session 1 that had nothing to do with it. That data is gone.

**Fix**: `sessionId` is no longer autoGenerate; the app now assigns it
directly from `System.currentTimeMillis()` at session start (the same value
already stored in `startWallTimeUtc`). Two sessions can only collide if
they start in the exact same millisecond, not realistic for a manually-
started single-device app. A local wipe can no longer produce a colliding
ID regardless of what triggered the wipe.

## DTC decoding is unverified

Current/pending/permanent trouble codes are read and parsed
(`BaseTroubleCodesCommand.parseTroubleCodesList()` in the pinned library
commit), but nothing has independently confirmed the output is correct on
this vehicle. The core nibble-decode arithmetic has been manually traced
through with a worked example (`"0171"` → `"P0171"`) and is correct, but
the more complex `workingData` extraction branch (CAN one-frame vs.
CAN multi-frame vs. ISO9141/KWP response framing) hasn't been fully
audited. Given how many other real bugs this library had elsewhere tonight
(see above), treat any DTC this project reports as a lead to verify
against a second scan tool, not a confirmed finding. Session 7 read back
`C0300` (current), `C0700` (pending), `C0A00` (permanent), all chassis
codes; unverified against another tool as of this writing, and predates
the raw-capture fix below so there's no raw text to re-check it against
either.

## Raw ELM response capture (added, field-confirmed working)

The blueprint originally called for a raw ELM response log; this never
existed until now. Every measurement (`MeasurementSample.rawResponse` /
`MeasurementEntity.rawResponse` / CSV's `raw_response` column / DuckDB's
`raw_response` column) now carries the verbatim adapter text for that
exact read, on both success and failure, mirrored identically in
`pc_logger`. Failed attempts also get a dedicated `PID_NO_DATA` event per
attempt (not just at the cooldown-triggering transition) with the same raw
text, and one-time reads (VIN, DTCs) log it in their `ONE_TIME_READ`/
`ONE_TIME_READ_FAILED` event message. This closes the forensic gap the DTC
and LTFT sections above depend on, going forward, it does not retroactively
add raw text to any session logged before this change (including session
7). Confirmed working on two real drives since, it's what let the LTFT
question above finally get resolved (literal `NO DATA` from the adapter,
not a parsing issue). Room's schema bumped to version 2 with
`fallbackToDestructiveMigration(dropAllTables = true)` (acceptable for a
dev-stage app with no undelivered local-only data, see
COMMERCIAL_READINESS.md) to add the column; this is what triggered the
session-ID collision below.

## VIN doesn't work on the test vehicle

`VINCommand` (Mode 09 PID 02) throws `NonNumericResponseException` every
time on the 2020 Mazda 6 test vehicle, even with the engine running and
real data flowing on every other PID. Not yet root-caused, whether it's
vehicle-specific (some manufacturers require an extended diagnostic
session for Mode 09 that a generic ELM327 clone in default mode won't
establish), adapter-specific, or a library parsing issue.

Deprioritized deliberately (the user's call): don't spend more time on this
unless it starts blocking something else. The vehicle-awake detection
already accounts for it by treating RPM plausibility as an independent,
equally-weighted signal rather than depending on VIN alone.

## Vehicle-awake detection can show a brief false "no"

`engineDetected` needs 5 RPM samples with none above the plausibility floor
before concluding "no." Right after connecting, if the first few RPM reads
happen to be corrupted (see the byte-overflow bug above, now clamped to
null rather than a huge fake number) it can briefly show "no" before
flipping to "yes" once clean data arrives. This is the clamp doing its job,
not a false alarm to chase, if it flips to "yes" within the first ~30
seconds and stays there, the session is fine. If it never flips, that's
real.

## No TLS on the ingest server

Plain HTTP; the bearer token is the only protection once the port is
exposed to the internet (see `server/README.md`'s port-forwarding section).
Fine for a single personal user's own data over a low-value channel, not
acceptable if this ever handles other people's data. See
[COMMERCIAL_READINESS.md](COMMERCIAL_READINESS.md).

## Miscellaneous

- Fuel Rail Pressure and Fuel Consumption Rate frequently return
  `NoDataException` on the test vehicle (now correctly cycling through
  cooldown rather than being permanently dropped, but still often
  genuinely unsupported, not a bug).
- `Engine Runtime` has parsed to `None` in every session so far; not yet
  investigated.
- Screen timeout was set to 30 minutes on the test phone for development
  convenience, then reset back to 30 seconds (`adb shell settings put
  system screen_off_timeout 30000`) once dev work wrapped up for the
  session.
