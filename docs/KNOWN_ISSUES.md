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

## VIN doesn't work on the test vehicle (check removed)

`VINCommand` (Mode 09 PID 02) threw `NonNumericResponseException` every
time on the 2020 Mazda 6 test vehicle, even with the engine running and
real data flowing on every other PID. Never root-caused, whether it's
vehicle-specific (some manufacturers require an extended diagnostic
session for Mode 09 that a generic ELM327 clone in default mode won't
establish), adapter-specific, or a library parsing issue.

Deprioritized deliberately early on (the user's call), and later removed
entirely: an always-failing check every single session carries no
information, it was just a wasted round-trip at session start and a
permanently-"no" row cluttering the UI (its associated warning banner
fired every session regardless of whether the vehicle was actually
awake). Vehicle-awake detection now relies solely on RPM plausibility.
Sessions logged before this change still have `ONE_TIME_READ_FAILED`
events for VIN in their data if anything ever needs to look back at that.

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

## MPG estimate runs well above the vehicle's own trip computer

Two real drives (city/backroad, ~3.5-3.8 km each): DriveTrace's
`overall_mpg` came out 20.5-24.7, the vehicle's own dash trip computer
showed 17 and 19 for the same two drives (user-reported, matches the
~19 MPG tank-average baseline from the blueprint). GPS-vs-OBD distance
agreement is excellent on both (0-0.6%), so this is not a distance/
odometer issue, it's specifically the fuel side of the MPG calculation.

Checked and ruled out as the explanation:
- **Stoich-gating dropping non-stoichiometric samples to zero fuel**:
  only 0.5-3.7% of samples per drive fell outside the 0.9-1.1 equivalence
  ratio gate. Not enough volume to produce a 15-25% gap.
- **Missing equivalence-ratio multiplier** (found and fixed regardless,
  see below): moved MPG from 20.5→20.8 and 24.7→25.1, the wrong direction
  to close the gap and too small to matter either way.

**Still open, and can't currently rule out**: DriveTrace's fuel-burned
estimate is entirely MAF-derived (airflow × assumed stoichiometric ratio);
the vehicle's own trip computer almost certainly integrates direct
injector pulse width, a fundamentally more direct fuel measurement. Some
gap between the two is expected on principle. A gap this large and this
consistent is also exactly what a MAF sensor under-reporting actual
airflow would produce (lower reported airflow → lower estimated fuel →
higher estimated MPG), which is one of the blueprint's original
diagnostic hypotheses. The normal way to check that (does LONG_TERM trim
sit persistently positive, i.e. has the ECU learned to inject more than
a biased-low MAF calls for) isn't available, LTFT is dead on this
vehicle (see above). Until either LTFT starts working or another way to
cross-check MAF turns up, treat the vehicle's own trip computer as the
more trustworthy absolute MPG number, and DriveTrace's estimate as useful
for relative comparisons across drives (same methodology every time) more
than as a replacement for the dash reading.

**Fixed while investigating**: `add_derived_columns`'s fuel-rate formula
computed `fuel_g_s = maf_gs / STOICH_AFR_GASOLINE` for every sample inside
the near-stoich gate, never actually multiplying by the real commanded
equivalence ratio value. Per SAE J1979, actual AFR = stoich AFR /
ce_ratio, so the correct formula is `fuel_g_s = maf_gs * ce_ratio /
STOICH_AFR_GASOLINE`. Confirmed the fix's actual effect on two real
drives before concluding it wasn't the answer to the bigger question
above, rather than assuming.

## Octane-driven timing retard (new lead, not yet checked)

This vehicle is a SKYACTIV-G 2.5 Turbo, factory-rated 250hp on 93 octane
vs. ~225hp on 87, via the ECU's knock sensor dynamically retarding timing
(and reducing boost) on lower-octane fuel, not a fixed detune. Retarded
timing to avoid knock measurably hurts fuel economy on its own, no sensor
fault required, this is a real, mundane, non-mechanical candidate
explanation for the original MPG-drop question, worth checking before
chasing anything else.

**Timeline detail that matters**: the user noticed the original MPG drop
while still buying 93, and kept buying 93 for a long time afterward, only
switching to 87 in the last few months to save money. This rules out
"switched fuel grade" as the explanation for the *original* drop, that
happened entirely on premium fuel. It reframes the useful question:
could the engine have been retarding timing *unnecessarily even on 93*,
a real fault (bad knock sensor, carbon buildup on the direct-injection
valves/chamber increasing knock tendency independent of fuel, a cooling
issue raising intake/chamber temps) rather than the engine just doing
what it's designed to do on cheaper gas. The recent 87 switch is a
separate, later layer on top of whatever the original issue was.

**The test this suggests**: log a drive on 87 (current), then run a tank
of 93 and log a comparable drive. If Timing Advance normalizes on 93,
the engine's behaving as designed and the 87 switch alone explains the
recent numbers. If it's still retarded on 93 similar to what shows up on
87, that's real evidence of a persistent, fuel-independent problem worth
chasing further (knock sensor, carbon buildup, cooling), not just a fuel
grade choice.

Timing Advance (PID 0E) wasn't logged before tonight; kotlin-obd-api
doesn't have a command for it. Added a custom `TimingAdvanceCommand` (see
`SafeCommands.kt`) to Tier B, alongside a corrected `SafeAbsoluteLoadCommand`
(PID 43, the library's `AbsoluteLoadCommand` had the same unbounded-byte
bug as everything else tonight) for boost-aware load context, a turbo
engine's actual cylinder filling under boost isn't well represented by
the plain Engine Load PID. Neither checked against a real drive under
load yet, if timing is more retarded than expected for the RPM/load at
hand, that's direct ECU-side evidence of knock mitigation in progress,
independent of what octane anyone remembers buying.

## Driving-phase classification found a real Tier B staleness bug

Built a phase classifier (idle/accelerating/cruising/coasting/decelerating/
braking) plus a per-braking-event kinetic-energy-waste estimate, to answer
"could coasting earlier here have saved fuel" with real numbers instead of
guesses (see `classify_phases`/`find_braking_waste_events` in
`analyze_drive.py`). First pass against three real drives flagged **zero**
coast phases across 22 braking events, every single one. That's not a
real finding about the user's driving, tracked it down: `Throttle
Position` (used to distinguish "foot off the gas, coasting" from "still
decelerating on throttle") is Tier B, nominally ~3-5s, but confirmed
directly against real snapshot data it was actually 7-10s stale exactly
during lift-off moments, `age_s_throttle_pct` hit 10.4s while the car
decelerated from 36 to 15 km/h in that same window. The classifier was
reading a mid-throttle value held over from several seconds earlier, not
what the driver's foot was actually doing.

Fixed two ways: promoted `ThrottlePositionCommand` to Tier A (see
DATA_SCHEMA.md), and added a staleness guard in `classify_phases` itself
(`MAX_THROTTLE_AGE_S = 3.0`, defense-in-depth for older sessions or any
future degraded rotation) that refuses to classify a phase as "coasting"
without fresh throttle evidence, falling back to the throttle-agnostic
"decelerating" instead. Verified the guard does the conservative thing on
the three historical sessions (still zero coasts detected, correctly, all
three predate the Tier A promotion) rather than silently producing the
same wrong answer with more confidence. Whether real coast phases show up
now needs a drive logged after this fix, not yet confirmed.

The braking-waste-to-fuel conversion itself (`ASSUMED_VEHICLE_MASS_KG`,
`ASSUMED_ENGINE_EFFICIENCY`) is a physics estimate, not a measurement,
representative values for this vehicle class, not measured for this
specific car. Trust the relative comparison between events within one
drive more than the absolute mL figures.

**Not yet built**: cross-session GPS clustering to recognize the same
real-world spot (e.g. a specific intersection on a repeated commute)
across multiple drives, so a coaching flag can say "this has happened N
times here" rather than being scoped to one drive. Deliberately deferred:
decided to use GPS-coordinate clustering with no external dependency
(no reverse-geocoding/street-name API) rather than pull in a mapping
service, real street names could be layered on later as a separate
decision if useful.

## est_instant_mpg is a misleading point-in-time ratio (found while building a phase-timeline chart)

Building a chart of MPG over time (prompted by wanting to visualize a
drive by phase) surfaced a real problem: `est_instant_mpg` rarely dipped
below 50 on a drive whose actual overall MPG was 26.3. Not a display
quirk, a real flaw in the metric itself. `est_instant_mpg = speed /
fuel_rate` at each single instant; during coasting or deceleration
fuel-cut, fuel rate drops toward zero while speed is still real, so the
ratio spikes toward infinity (248 MPG observed) even though that instant
contributes almost nothing to the trip's actual fuel total either way.
Confirmed directly: the drive's true moving-only MPG (sum distance / sum
fuel, ignoring idle entirely) was 31.2; `est_instant_mpg`'s median while
moving was 42.6, 75th percentile 99.7. You can't average a ratio and
expect it to match the ratio of the sums, classic mistake, and this
metric had been feeding the existing `estimated_mpg.png` plot the whole
project.

Added `compute_rolling_mpg`: sums distance and fuel separately over a
15-second rolling window and divides once, the same correct method
`compute_overall_mpg` already used for the whole trip, just windowed.
Confirmed much more representative on the same drive: median 33.7 (vs.
31.2 true), max 143 (vs. 248). `estimated_mpg.png` now plots both, the
old raw ratio faint in the background so the difference stays visible
rather than silently disappearing, and the new rolling line prominent.
Used the rolling metric (not the raw one) for the new phase-timeline
chart below.

## Phase-timeline chart, built with `xy` (new library, alpha)

User wanted to visualize a full drive as a timeline colored by driving
phase, with a continuous MPG line, and asked specifically to try `xy`
(a brand-new Rust-backed Python charting library, alpha as of this
writing, not something used elsewhere in this project) rather than
matplotlib. Real API, found the right primitives (`x_band` for
full-height phase-colored background bands, `line` with a named
secondary `y_axis` for the dual speed/MPG scales, `callout` for
pointer-line text), composition works as documented.

Hit one real alpha-library bug: `x_band(x0, x1)` rejects a zero-width
band, and a single-row (1-second) phase run has `x0 == x1` on a 1Hz grid.
Fixed by extending `x1` by one grid interval for every band, which is
also just the more correct thing to draw (that row represents a full
second of that phase, not an instant).

Not yet wired into the automatic per-drive pipeline (`analysis_worker.py`
still only produces the matplotlib plots), this is a prototype under
review before deciding whether/how to make it a standard output.

**Update**: user feedback on the first version caught two more real
issues, both fixed. (1) The phase classifier produced 246 bands on one
drive, traced to Vehicle Speed's 1 km/h integer quantization creating a
~+-2 km/h/s noise floor sitting on top of the original thresholds, fixed
with a two-signal approach (raw for braking, smoothed for the gentler
bands) plus a narrowly-scoped merge pass, see `classify_phases`'s
docstring/comments for the full story; bands dropped to 68 with braking
counts unchanged. (2) `hline()` doesn't support targeting a secondary
y-axis in this alpha library, worked around by drawing a flat dashed
`line()` on that axis instead. (3) Not a bug: the rolling MPG line
sitting above the trip's overall average most of the time is expected
when idle time is a large fraction of the trip (0 MPG, drags the blended
average down more than its time-share suggests), added an explicit
reference line rather than treating it as something to fix further.

## Miscellaneous

- Fuel Rail Pressure and Fuel Consumption Rate frequently return
  `NoDataException` on the test vehicle (now correctly cycling through
  cooldown rather than being permanently dropped, but still often
  genuinely unsupported, not a bug).
- `Engine Runtime` **root-caused**: the library's `RuntimeCommand` queries
  PID `0F`, which is actually Intake Air Temperature per SAE J1979, not
  Engine Runtime (that's PID `1F`), confirmed directly against the
  library's real source. It was silently formatting an intake-air-temp
  byte as a fake `HH:MM:SS` string, which never parses as a number, hence
  "always None." Fixed via `SafeEngineRuntimeCommand` (correct PID, plain
  numeric seconds).
- Screen timeout was set to 30 minutes on the test phone for development
  convenience, then reset back to 30 seconds (`adb shell settings put
  system screen_off_timeout 30000`) once dev work wrapped up for the
  session.
