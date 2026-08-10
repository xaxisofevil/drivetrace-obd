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

**Resolved (for real, this time)**: the ECU's own declared supported-PID
list (`SafeAvailablePIDsCommand`, see below for a real parsing bug this
required fixing first) confirms PID 08 (LONG_TERM_BANK_1) is **absent**
from the Mode 01 01-20 range on both sessions checked, decoded three
different ways (each of two conflicting raw frames individually, and
their OR) and PID 08 is missing under every reading. This ECU genuinely
does not expose LTFT via generic Mode 01, full stop, this is a vehicle
characteristic, not a library, adapter, or timing/condition issue after
all. The user has seen LTFT populated in other apps on this same car
before, that's still real and unexplained by this finding, but the
explanation is now narrowed: those apps must be reading it through a
different (likely manufacturer-specific) PID or diagnostic mode, not
generic Mode 01 PID 08, since this vehicle's own ECU says that PID isn't
there.

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

**Update, while building the DTC display:** those three session-7 values
are almost certainly response framing leaking into the parse, not real
faults. `C0300` (current), `C0700` (pending), `C0A00` (permanent) are
exactly modes `03`, `07` and `0A`, one per request, and they came back as
*chassis* codes on a vehicle whose chassis ECU this app never queries.
Three requests, three codes, each encoding its own mode byte, is not a
coincidence.

`isSuspectedFramingArtifact` (`data/DtcCatalog.kt`) hardcodes those three
strings. The trip report demotes a matching code out of the fault channel
to `UNKNOWN` tone and says why, rather than filtering it: same posture as
`IMPLAUSIBLE` measurements, surface the suspect value with its caveat and
never silently drop it. A genuine `C0300` on some other vehicle would be
mislabelled by this, which is the honest cost of not having verified the
decode against a second scan tool yet.

**Still the right next step:** read codes with a second tool on a vehicle
that has a known stored code, and compare against the `raw=` text the
`ONE_TIME_READ` event now carries (which session 7 predated). That settles
both the artifact theory and whether the decode is correct for real codes,
and neither can be settled from the app's own output alone.

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

Checked and ruled out as the explanation, **for these two short
drives specifically** (see the update below, this stopped being true in
general):
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

**Fixed while investigating (first pass)**: `add_derived_columns`'s
fuel-rate formula computed `fuel_g_s = maf_gs / STOICH_AFR_GASOLINE` for
every sample inside the near-stoich gate, never actually multiplying by
the real commanded equivalence ratio value. Per SAE J1979, actual AFR =
stoich AFR / ce_ratio, so the correct formula is `fuel_g_s = maf_gs *
ce_ratio / STOICH_AFR_GASOLINE`. Confirmed the fix's actual effect on two
real drives before concluding it wasn't the answer to the bigger question
above, rather than assuming.

### Update: the "stoich-gating" theory above was wrong to rule out, it was just tested on the wrong drives

Reported live: a 66-minute Subaru highway/backroad drive came back at
**38.7 MPG**, driver-certain that's wrong (their own sense of the drive,
and the on-device rough estimate shown right at Stop, both said roughly
21).

The masking gate ruled out above (`.where(ce_ratio.between(0.9, 1.1))`,
dropping every sample outside it to **zero fuel burned** rather than
"unknown") was tested against two short, mild drives where only 0.5-3.7%
of samples fell outside the band, not enough volume to matter. This
Subaru drive had a 23.8-minute cold-start warmup and real highway
acceleration, putting **15.5%** of its samples outside the band. Directly
confirmed those masked samples were not idle or noise: their mean MAF
(38.6 g/s) was *higher* than the kept samples' mean (22.4 g/s), meaning
the mask was silently zeroing out the drive's highest-consumption
moments (WOT enrichment on acceleration, merges, passes) specifically
because those are exactly when combustion moves away from stoichiometric,
not despite it.

This inverts the original reasoning entirely: Commanded Equivalence
Ratio exists precisely to correct the MAF-derived fuel formula for
non-stoichiometric operation, that correction is the entire reason to
read `ce_ratio` at all rather than just assuming stoich everywhere. A
"near-stoich only" trust window masks the formula's output exactly where
the `ce_ratio` term does real work, and defaults to zero everywhere else.
Removed the mask entirely in `add_derived_columns`; the formula is valid
across `ce_ratio`'s full range, and the range itself is already sanity-
clamped upstream on the phone (`PidScheduler.kt`'s `PLAUSIBLE_RANGES`:
0.0-3.0), no second "trust window" was needed on top of that.

**Confirmed effect directly, before and after, same underlying data:**
- The disputed Subaru drive: 38.7 → 30.4 MPG. Large correction, this was
  the drive with the high out-of-band fraction.
- The two Subaru drives recovered earlier tonight (see "Retry Analysis
  failed forever", above), both short, low out-of-band fraction: 22.4 →
  22.2 and 24.4 → 24.1. Barely moved, as expected.

30.4 is still above the driver's ~21 expectation and above the vehicle's
own trip computer by the same general margin documented in the section
above, that broader, still-open gap (MAF under-reporting vs. a more
direct injector-pulse-width reference) is unaffected by this fix and
remains the likely next lead. This fix corrects a real, separate,
mechanical bug in how the existing MAF-based estimate summed its own
numbers; it is not a claim that the MAF-based method itself is now
accurate against the dash.

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

## AvailablePIDsCommand silently failed on the exact range that mattered (found and fixed)

The ECU-supported-PID check added earlier (see the LTFT section above)
had never actually worked: `PIDS_01_TO_20`, `PIDS_21_TO_40`, and
`PIDS_41_TO_60` all threw `NumberFormatException` on both real sessions
tried. `PIDS_01_TO_20` is the one that mattered most, it's the range
containing PID 08. Root cause: this adapter returns **two** frames for a
single Mode 01 supported-PIDs request, concatenated with no separator
surviving the library's cleanup pipeline (e.g.
`"4100981A80134100FE7FA813"`, 24 hex chars). The library's
`AvailablePIDsCommand` calls `rawValue.toLong(radix=16)` on the entire
string; 24 hex chars is 96 bits, which overflows a 64-bit `Long` and
throws.

Checked the two frames' actual content before assuming they were
harmless duplicates, worth doing since assuming wrong here would have
shipped a quietly-unreliable fix: they aren't duplicates. Session 1 saw
`981A8013` then `FE7FA813`; session 2 saw the exact same two values in
the **opposite** order. Reproducing the identical pair of values across
independent sessions rules out random corruption (which wouldn't
reproduce); the order flipping rules out "always take the first frame"
as a safe fix. Leading explanation: this project's ELM327 init sends
`ATH0` (headers off), so a broadcast Mode 01 request gets answered by
every ECU on the bus that supports it, and with headers off there's no
way to attribute which frame came from which module, they just
concatenate in whatever order bus arbitration produces that time.

Fixed `SafeAvailablePIDsCommand` (see `SafeCommands.kt`) to split the raw
response into complete 12-hex-char frames and **OR them together**
rather than picking one: a PID counts as supported if any responding
module reports it, and OR is order-independent by construction, verified
directly (both sessions' frame-orderings OR to the identical result).
Also verified the fix doesn't disturb the two ranges that were already
parsing correctly (single-frame responses, no change in behavior).

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

## Wrong PID for Long Term Fuel Trim Bank 1, the actual answer to the whole evening's LTFT mystery

The real root cause, finally found via a live cross-check against Car
Scanner: kotlin-obd-api's `FuelTrimCommand.FuelTrimBank` enum has
`SHORT_TERM_BANK_2` (PID `07`) and `LONG_TERM_BANK_1` (PID `08`) swapped
relative to the real, unambiguous SAE J1979 standard (`06`=STFT-B1,
`07`=LTFT-B1, `08`=STFT-B2, `09`=LTFT-B2, unchanged for decades). This
project had been requesting PID `08` under the label "Long Term Fuel Trim
Bank 1" all night, PID `08` is really Short Term Fuel Trim **Bank 2**,
which doesn't exist on this single-bank inline-4 engine, exactly why it
returned literal `NO DATA` on every single attempt, confirmed across
multiple real drives. The real PID `07` was never once requested by this
project. `pc_logger/pids.py` had the identical bug, hand-written
independently but inheriting the same wrong assumption.

Confirmed three independent ways before touching code: (1) the real SAE
standard, (2) Car Scanner reading a live 7.03% value from its own PID
ID=7, explicitly labeled "Long term fuel % trim - Bank 1", on this same
vehicle with this same adapter, and (3) this project's own
`SafeAvailablePIDsCommand` fix from earlier tonight had already decoded
PID `07` as present in the ECU's declared supported list and PID `08` as
absent, exactly consistent with a real Bank-1-only engine, the data was
sitting there the whole time, it just hadn't been cross-referenced
against the right standard yet.

Every theory chased earlier tonight (adapter flakiness, ELM327 headers
mode, timing/condition-gating, "Mazda hides this behind an enhanced PID")
was chasing a request that was simply asking the ECU a question it
correctly had no answer to. Fixed via `SafeLongTermFuelTrimBank1Command`
(app) and a corrected PID string (pc_logger), same parsing math as
before (only the PID was ever wrong), same tag/canonical_name so
`analyze_drive.py`'s existing keyword matching and all prior event-log
queries keep working unchanged, they'll just finally have real data
behind them going forward. Not yet confirmed end-to-end through
DriveTrace itself on a real drive as of this writing, built and installed,
next drive should show it.

## Trip history and forced backfill retry (new feature)

Two real gaps this closes: no way to see past sessions once you've left
the Session Complete screen (it's ephemeral in-memory `LoggingUiState`,
lost on app restart even though the underlying Room data is fine), and
no way to retry a failed upload without either reopening a screen that no
longer exists or asking someone to pull the Room DB by hand over adb.
Confirmed real need for the second one specifically: a driveway test's
876 measurements got stranded when the home server was unreachable at
Stop time, with no durable record anywhere that it still needed
uploading.

- `SessionEntity` gained `backfillStatus`/`backfillMessage`/
  `analysisStatus`/`analysisSummaryJson` (persisted, not just in the
  ephemeral state), Room bumped to v4. **This required care**: the
  destructive-migration policy already in place would have permanently
  destroyed the driveway test's still-stranded session the moment this
  version installed, so that session must be pulled and manually
  backfilled with the *old* app version still on the phone before this
  update ever ships to it. See AppDatabase.kt's version-history comment.
- `BackfillCoordinator.kt`: the actual backfill-then-analyze sequence,
  extracted into one shared function so the live Stop-button flow
  (`DriveLoggingService`) and the new background retry worker can't
  silently drift into two different implementations of the same thing.
- `BackfillRetryWorker.kt`: a WorkManager `CoroutineWorker`, not a plain
  coroutine, specifically because WorkManager persists the retry request
  in its own system-level store independent of this app's process.
  Android will start the app just to run it once its network constraint
  is satisfied, which is what actually makes "retry even after the app
  is closed" true rather than aspirational. Triggered three ways: an
  opportunistic sweep on every app launch (`MainActivity.onCreate`), a
  fallback sweep whenever a live backfill fails, and an expedited,
  session-specific request from the new History screen's "Retry upload"
  button.
- `HistoryScreen.kt`: lists every session from local Room (authoritative,
  works even if the server's never been reachable for a given session),
  color-coded upload/analysis status, trip MPG once analyzed, and a
  "Retry upload" button on anything not yet confirmed uploaded. Reachable
  from a new "Trip History" button on the Setup screen.

## Multi-vehicle support (new feature): PidCatalog is now per-vehicle

Prompted by adding a 2014 Subaru Outback 2.5i alongside the Mazda 6.
`PidCatalog` changed from a single hardcoded `object` to an interface,
with `MazdaPidCatalog` and `SubaruPidCatalog` as separate implementations,
selected via a new `VehicleProfile` enum picked at Setup (persisted like
the OBD device address already was). The server/Room data model needed no
changes for this, `SessionEntity.vehicleProfile` was already a per-session
field, not a global constant, this was a gap in the app's UI/scheduler,
not the data model.

**The one substantive, non-cosmetic difference for the Subaru**: it's a
genuine two-bank boxer engine (FB25), unlike the Mazda 6's single-bank
inline-4. Boxer engines are known for legitimate bank-to-bank fuel trim
asymmetry from unequal exhaust runner lengths, so Bank 2 trim (PIDs 08/09)
is a real diagnostic signal here, not the nonexistent-PID noise it would
be on the Mazda. Added `SafeShortTermFuelTrimBank2Command` (PID 08, the
library's own `SHORT_TERM_BANK_2` entry is wrong, same swap bug as
`LONG_TERM_BANK_1`, see the LTFT section above) and used the library's
`FuelTrimBank.LONG_TERM_BANK_2` as-is (PID 09 is correct there). Also
dropped the Mazda-specific "no VIN" decision for this new catalog: that
was a finding about this specific vehicle/adapter, not a project-wide
default, so `SubaruPidCatalog` includes `VINCommand` until there's
evidence it needs the same treatment.

**Completely untested as of writing.** This is a best-effort starting
catalog built from the Mazda's, not a validated one. Before trusting
anything it reports: run the ECU-supported-PID check (already included in
`oneTimeReadOnly()`) on a real connection and see what this ECU actually
declares, same caution as everywhere else in this project tonight, don't
assume PID behavior transfers between vehicles just because it's the same
manufacturer or even the same generic OBD-II PID.

**Scope decision**: `pc_logger` (the Python/laptop logger) was NOT
extended to multi-vehicle in this pass, it still polls a single hardcoded
Mazda-specific PID list (`pc_logger/pids.py`). Flagging explicitly so this
doesn't silently drift out of sync with the app: if pc_logger is ever
used for the Subaru, it needs the equivalent per-vehicle treatment first.

## Two more sensor gaps found: catalyst temp, oil temp; plus turbo-health signals

Prompted by "are we set up with all the sensors we need." Checked the
library source before answering rather than guessing: kotlin-obd-api has
no Catalyst Temperature or Engine Oil Temperature command at all, in
either its temperature or pressure package. Both are standard,
unambiguous SAE J1979 PIDs directly relevant to failure modes nothing
else in either catalog covers: a restricted/failing catalytic converter
(exhaust backpressure, pumping losses) and thermostat/warm-up behavior
that coolant temp alone can mask. Added `CatalystTemperatureBank1Sensor1Command`
(PID 3C) and `OilTemperatureCommand` (PID 5C) to `SafeCommands.kt` and
both catalogs' Tier C. Formulas verified against realistic values before
shipping (e.g. A=32,B=208 -> 800°C, a plausible converter operating
temp; A=130 -> 90°C, a plausible oil temp).

Also added boost pressure as a derived value (`boost_kpa` = MAP -
Barometric, both already collected, never combined before) and intake
air temp above ambient (`iat_above_ambient_c`), specifically to give the
turbo a first health check using data already being gathered, no new
PIDs needed for this part. The diagnostic signatures worth knowing:
- **Boost leak** (cracked intercooler pipe, loose clamp, failing diverter
  valve): trim goes lean specifically under boost/load, not idle, the
  mirror image of a PCV leak's idle-heavy pattern (see
  MPG_ASSESSMENT_PLAYBOOK.md). MAF (upstream of the turbo) reading normal
  while boost (downstream, after the intercooler) underperforms for the
  same RPM/throttle points at air escaping between the turbo and the
  cylinders, not a compressor problem.
- **Heat-soaked/failing intercooler, or oil in the intake tract from a
  failing turbo seal**: a bigger-than-expected `iat_above_ambient_c` gap
  during/after boosted driving, beyond normal compression heating.
- **Turbo-related timing retard vs. octane-related**: if retard tracks
  boost/load specifically rather than being roughly constant regardless
  of load, that points at the turbo side, not fuel octane. Distinguishing
  these needs a real drive with actual boosted segments, not yet
  collected.
- **Not checkable without manufacturer-specific (Mode 22) data**:
  commanded/target boost to compare against actual, wastegate duty cycle,
  turbo shaft speed. None of these exist as standard PIDs, same category
  of gap as LTFT's enhanced-PID confusion earlier tonight, except in this
  case there's no known bug to find, this data genuinely isn't exposed
  generically.

None of this has been checked against a real boosted drive yet, only
verified as computing correctly on synthetic values.

## Five manufacturer-specific enhanced PIDs added (Mazda only, substantially untested)

The user found real evidence that Mazda exposes enhanced (Mode 22)
parameters for exactly the signals generic OBD-II can't provide: Boost
Pressure Desired (target boost, something no standard PID covers at
all), Turbocharger A/B Compressor Inlet Pressure, and Knock Retard/Knock
Control System (a more direct knock signal than Timing Advance alone,
directly relevant to the octane investigation). Source: a community-
compiled CSV of extended Mazda Skyactiv PIDs (Torque custom-PID format)
shared on the Mazdas247 forum, not official Mazda documentation.

**Real technical differences from every other command in this project,
carried into `MazdaEnhancedCommands.kt`'s own documentation, not hidden:**
- The source CSV's header column was corrupted by Excel's own
  auto-formatting before reaching this project (`"7.00E+00"` etc.),
  almost certainly Excel misreading the literal text `"7E0"` (a
  completely standard CAN request ID) as scientific notation.
  Reconstructed as `7E0`, inferred from the corruption pattern, not
  confirmed against an original.
- This project has **no mechanism to override the CAN header per
  command at all** (confirmed against kotlin-obd-api's `ObdCommand` base
  class). Some rows in the source file explicitly said `"Auto"` while
  others specified a real header, a deliberate distinction its author
  made, real evidence these specific requests may need an explicit
  header this project never sends.
- These responses are **longer than anything successfully parsed
  before** (5+ data bytes plus a 3-byte echo prefix, vs. every other
  command's 1-2 data bytes), depending on the same kind of multi-frame
  CAN reassembly that VIN needs and has never once worked on this
  adapter, for a reason never fully resolved.
- Byte-offset math differs from every other command: a 2-byte Data
  Identifier echoes back 3 bytes (mode + 2-byte DID) before real data,
  not the 2-byte echo (mode + 1-byte PID) every other command in this
  project assumes. Verified this directly with a worked example before
  writing the formulas, and separately verified the "signed(A)" knock
  formulas byte-by-byte (byte A alone reinterpreted as signed 8-bit,
  byte B stays unsigned, not "reinterpret the combined value as signed",
  a different, wrong operation that was easy to reach for instead).
- **A real inconsistency found in the source file itself**: Knock
  Control System's row claims a -100..100 range, but its own formula
  (divisor 16384) can only ever produce roughly ±2.0, confirmed by
  computing the formula's actual extremes. Used the derived range for
  this project's plausibility clamp, not the CSV's stated one.
- Compressor inlet **pressure**, not temperature, despite resembling
  what was originally asked about, the source file has no compressor
  inlet temperature entry at all.

`boost_desired_kpa` added to `analyze_drive.py` alongside the existing
`boost_kpa` (same MAP-minus-Barometric logic, against the ECU's target
instead of its measurement): a persistent gap between the two, actual
running well below target, is itself a real turbo-health signal
(wastegate stuck open, a boost leak, or a lazy/failing turbo).

**Completely untested against a real vehicle.** If these come back
`NO DATA` on a real drive, check the raw response text (captured for
every attempt) before assuming the PID itself is wrong, the header or
multi-frame reception is the more likely culprit given everything above.

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

## Notification-shade "Stop" action does nothing (root-caused, mostly fixed)

Observed for real on the test phone: tapping the notification's
**Stop** action had no visible effect, notification unchanged. This is
the notification-shade action button
(`DriveLoggingService`'s `NotificationCompat.Builder.addAction(...,
"Stop", stopPendingIntent)`), a separate code path from the in-app red
"Stop logging" button on `LoggingScreen`, confirmed working throughout
(see the Live-state screenshot in `docs/DESIGN_SYSTEM.md`'s review).

**Root-caused with `adb logcat` open across two separate, deliberate
reproductions**, one with an imprecise tap, one with a careful tap
squarely on the word "Stop" while a session was genuinely stuck
mid-connect (adapter powered off). Both times, `grep` across the full
capture for `com.ericbarone.drivetrace.action.STOP` found **zero
matches, anywhere**, system-level or app-level, while
`com.ericbarone.drivetrace.action.START` reliably produced a clear
`ActivityManager: Background started FGS` line every single time. The
`PendingIntent` was never firing at all, not a slow cancellation, not a
missed `stopForeground` call. Code inspection of `stopSession()` and
the notification-building code found nothing wrong with either, ruling
out an app-level logic bug.

**Fix**: `PendingIntent.getService()` → `PendingIntent.getForegroundService()`.
The latter is the platform's own documented replacement for exactly
this case, a notification action targeting an already-running
foreground service (the same pattern media-session pause/stop actions
use), and is more consistently honored across OEM notification
implementations than the older API. Safe on an already-foreground
service without a matching `startForeground()` call in the
`ACTION_STOP` branch: the "must call `startForeground()` promptly"
requirement is tracked per-service state, not per individual start
call.

**Confirmed fixed for the case that matters**: tapping Stop while the
app is backgrounded (screen off, another app open, home screen), the
actual scenario the notification action exists for, now works.

**One residual case, confirmed via direct A/B test, not further
chased**: tapping Stop while `DriveTrace`'s own `MainActivity` is
*already* the current foreground activity (app open, shade pulled down
over it) still does nothing. Same device, same fix, tested twice more:
background → works, foreground → doesn't. This reads as an OEM
(ColorOS) touch-routing quirk specific to a notification's own posting
app being the currently-resumed foreground window, not something
further logcat digging can resolve without instrumenting closed-source
SystemUI. Not pursued further because the workaround is already on
screen in that exact scenario: the in-app "Stop logging" button, right
there, unquestionably working. If this resurfaces as a real complaint
(e.g. on a different OEM/device), the next lever to try is
`.setCategory(NotificationCompat.CATEGORY_SERVICE)` on the builder,
untested here, speculative.

## Four bugs found on a real drive, three fixed, one partially

Reported together after a real session: the PC this app's server runs
on rebooted mid-drive, killing the server, which exposed all four.

**1. "Retry upload" spun forever against an unresponsive server
(fixed).** `BackfillRetryWorker.doWork()` returned `Result.retry()` on
any failure, including for a single user-targeted retry
(`enqueueRetryNow`/`enqueueAnalysisRetryNow`), not just the
opportunistic sweep. `Result.retry()` re-queues the work with
exponential backoff, and a `WorkInfo` sitting in backoff reads as
`!isFinished` exactly like one still actively running, so the
logbook's busy-spinner (`HistoryScreen.kt`'s `workInFlight()`, which
watches `!it.state.isFinished` on that specific unique work name)
spun for as long as WorkManager's backoff window lasted, hours,
against a server that would never answer, rather than for the ~10s
one real attempt actually took. Fixed: a single targeted retry
(`targetId != null`) now returns `Result.failure()` on failure instead,
finishing that `WorkInfo` promptly. The opportunistic sweep
(`targetId == null`) is unchanged, still backs off and keeps trying
quietly, nothing is watching its `WorkInfo` for a spinner to turn off.

**2. Rotating the phone while on the Logbook warped back to Setup
(fixed).** `MainActivity`'s `showHistory` was a plain
`remember { mutableStateOf(false) }`. Android recreates the Activity
on a configuration change like rotation by default, and plain
`remember` state does not survive that, only `rememberSaveable` does,
so the whole screen silently reset to its `else` branch (Setup) on
every rotation. Fixed two ways: `android:screenOrientation="portrait"`
on `MainActivity` in the manifest (this is a phone-mount dashboard
app, landscape was never a real use case, and this stops the
Activity-recreation trigger entirely), plus `showHistory` itself
switched to `rememberSaveable` as defense-in-depth against the same
class of loss from plain process death under memory pressure while
the foreground logging service keeps running through a long drive,
independent of rotation.

**3. Logbook cards showed "--" MPG for drives that had a real number
(fixed).** `HistoryScreen.kt`'s `SessionCard` read only
`session.analysisSummaryJson` (the *server*-computed analysis result).
The Session Complete report screen has always had a "prefer the server
figure, fall back to the on-device estimate" chain for exactly the
case where analysis never completed; the logbook card never inherited
that fallback. Confirmed for real: two completed Subaru drives showed
real MPG on the report screen (on-device estimate, since the server
had died mid-session and analysis never ran) and a bare "--" on the
same drives' logbook cards. Fixed: `SessionCard` now computes the same
on-device `TripSummary` (`export/TripSummary.kt`, the identical
function the report screen already calls) as a fallback, only when
`analysisSummaryJson` doesn't already have a real figure, so a card
with a genuine server-side result never pays for the extra query.

**4. A session can hang at `INITIALIZING`, zero samples, forever
(fixed, took two attempts).** Confirmed for real: a session sat at
`connectionState = INITIALIZING`, `measurementCount = 0` for 34
minutes straight with no recovery, notification frozen the whole time.
Root cause: every step from `BluetoothSocket.connect()` through the
AT-command handshake (`ElmSession.initialize()`, which wraps a
third-party library's `ObdDeviceConnection`) to the one-time PID reads
is a plain blocking call on the raw input/output streams with no
timeout anywhere in that chain, same underlying class of bug as the
notification Stop-action investigation above.

**First attempt, wrong:** wrapped the whole setup sequence in
`withTimeout(30_000L)`. Caught by review before it shipped to a second
real hang: coroutine cancellation is cooperative, it only throws at a
suspension point, and a thread genuinely parked inside a blocking
`InputStream.read()` (or `BluetoothSocket.connect()`) has no
suspension point to be caught at. `withTimeout`'s deadline elapsing
does not preempt that thread, it only arranges to throw once the
blocking call already returns on its own, i.e. it would not have
actually fired for the exact hang it was written to catch. Worth
recording precisely because it's a genuinely easy mistake: the fix
compiled, read correctly, and would have looked identical to a real
fix in every session that happened not to hit a true stalled read
during testing.

**Second attempt, the real fix:** a watchdog coroutine launched
alongside the setup sequence (`serviceScope.launch { delay(30_000L);
transport.close() }`, disarmed via `.cancel()` in a `finally` the
moment setup finishes, success or failure). Closing a Java socket from
a different thread makes any read already blocked on it throw
`IOException` immediately, that's a real interrupt delivered by the
OS/runtime, not a cancellation request the blocked thread has no
chance to notice. The resulting exception unwinds into the same
`catch (e: Exception)` block that already handles an ordinary
connection failure: log a `RECONNECT` event, back off, try again.

**Still a real, deliberately unclosed gap:** the watchdog only guards
the setup phase, not `scheduler.run { ... }`, the ongoing polling loop
for the whole drive, since that call is supposed to block for a long
time by design. A hang inside one specific ongoing PID poll mid-drive
is not covered and would still freeze the session with no recovery.
Given "34 minutes, zero samples" means nothing ever got past setup in
the incident that motivated this fix, closing the setup-phase gap is
the right first cut, but the same watchdog-closes-the-socket pattern
applied per-command inside `PidScheduler`'s polling loop is the
correct shape for the deeper fix, not yet built.

## Trip report's hero readout looked blank (root-caused, not the bug it looked like)

**Observed:** a photograph of the Session Complete screen after a real
completed drive, taken to complain about the capture block, showed
nothing at all where the 64sp hero belongs. No label, no value, no
caption, just empty space under `SESSION COMPLETE / trip report` and
then straight into `CAPTURE AND DELIVERY`. The same drive's on-device
MPG (23.8) and GPS distance (14.60 km) rendered correctly further down
the same screenshot.

**It was never a blank hero, and the screenshot proves it against
itself.** `CompleteBody` derives `mpg = analysis?.overallMpg ?:
tripSummary?.overallMpg` and the capture block prints
`tripSummary.overallMpg` from the same composition, in the same frame.
For 23.8 to appear in the capture block, `tripSummary.overallMpg` must
be 23.8, so `mpg` is 23.8, so `heroFigure` returns its `HeroKind.MPG`
branch and the hero reads `23.8 MPG` in teal. There is no code path
that can produce one without the other; a screenshot is one frame, so
they cannot disagree. Same argument for the missing **Drive profile**
tiles: `distanceKm` was 14.60, so the Distance tile had to render too,
and it is not on screen either.

**Root cause: the report was scrolled, and nothing on screen said so.**
Measured off the original 1080x2376 JPEG rather than eyeballed. The
bright row at y=268 is the `HeaderBar`'s own bottom hairline (luminance
41, exactly `Hairline` #202B39), so the scroll viewport starts at y=270.
The one bordered box visible under the header is 43px of fill at
luminance 27, exactly `PanelRaised` #141C26, with rounded bottom corners,
a hairline bottom edge at y=311, and no top edge at all: it is clipped
by the viewport. Below it the gaps measure 22px then 55px before
`CAPTURE AND DELIVERY` begins, which is `Space.sm` (8dp) then
`Space.section` (20dp) at this screen's 2.75 px/dp. That is the
`DriveNoteEditor`'s empty `NoteField` and nothing else in the app: an
unlabelled, empty, bordered box sitting directly under the header, in
the slot the eye expects the hero to occupy. Roughly 900px of report,
hero and drive-profile tiles included, was above the fold. The report
has no scrollbar (by design, rule 7 and the data-ink argument), so a
report opened part-way down is indistinguishable from a report that
rendered wrong.

**How the offset got there.** Two contributors, both real, fixed
differently:

- `LoggingScreen` ran both its layouts through **one shared
  `rememberScrollState()`**. LIVE and COMPLETE are, by this project's
  own design document, two genuinely different layouts of two
  genuinely different lengths, and a `ScrollState` anchors on a pixel
  offset rather than on content, so any offset the live screen ended
  the drive at was inherited unchanged by a report several times its
  length. **Fixed:** the two layouts now hold separate scroll states
  and the report always opens on its own hero.
- `Modifier.imePadding()` sits on the screen's root Column, so
  focusing the drive-note field shrinks the report's scroll viewport by
  the full keyboard height, and Compose's own bring-into-view scrolls
  the focused field up. Dismissing the keyboard restores the viewport
  but not the offset, which parks the report exactly where the
  screenshot found it. **Not "fixed"**, because scrolling to a field
  you tapped is correct behaviour; the mitigation is that the capture
  block's redesign (see DESIGN_SYSTEM.md section 7) cuts enough length
  out of the report that far less of it can hide above the fold.

**Fixed alongside it, a genuinely invisible hero that can still
happen:** `heroFigure`'s two `--` branches painted the numeral `Slate`,
the disabled-text grey, at about 2.2:1 on `Ink` and at 64sp Light
weight. The design document already flags that ratio as unreadable in
sun; it is marginal indoors too. Those branches now use `Ash` (~5.4:1),
so no branch of the hero can render as nothing. Both greys already
carry daylight twins, so nothing about the daylight palette changes.

**Confirmed how:** by pixel measurement of the reported screenshot
(luminance and spacing matched against the theme's own tokens, above)
plus the same-frame data argument, not by reproducing a blank hero,
which the code cannot produce. Build passes; the scroll split is
behavioural and wants one real drive to confirm the report opens on its
hero.

## Retry Analysis failed forever, for real drives with a full upload (found and fixed)

Reported live: two Subaru Outback drives (46 min, 21 min, "Upload
success" on both) stuck on "Analysis failed" no matter how many times
Retry Analysis was tapped.

**Root cause, confirmed directly against the server's own DuckDB
file:** `POST /sessions/{id}/start` is fire-and-forget from the phone
(`StreamingClient.startSession`, called exactly once, at the moment a
drive starts, see `DriveLoggingService.kt`), with no retry of its own,
ever. The server happened to be down at that exact instant for both
drives (this session restarted it repeatedly while working on
unrelated features), so neither session's row in the `sessions` table
was ever created. Nothing else noticed: the bulk backfill endpoints
have no foreign key to the `sessions` table, so measurements/
locations/events all inserted cleanly and the phone correctly reported
"Upload success". Only `/sessions/{id}/analyze` noticed, and only by
crashing: `analysis_worker._analyze`'s `SELECT * FROM sessions WHERE
session_id = ?` came back empty, `sess_row.iloc[0]` raised a bare
`IndexError`, and every subsequent Retry Analysis repeated the
identical crash, because retrying analysis only ever re-sends
`/analyze`, never `/start`.

Confirmed by direct query: `LEFT JOIN sessions ... WHERE
sessions.session_id IS NULL` against `measurements` found exactly two
orphaned session IDs, with 19170 and 8746 measurement rows each,
matching the two stuck Subaru drives exactly.

**Recovered the two existing sessions by hand:** reconstructed their
`sessions` rows from data that was never actually lost (start/end
times from `MIN`/`MAX(wall_time_utc_ms)` in their own measurement
rows, `sessionId` itself as `start_wall_time_utc_ms` per
`DriveLoggingService.kt`'s own convention, vehicle profile from the
screenshot). Both then analyzed successfully on the first real attempt
(22.4 and 24.4 MPG), confirming the missing row, not bad data, was the
entire problem. The phone's local Room copy of `analysisStatus` still
needs one more real Retry Analysis tap each to catch up to what the
server now shows; this fix doesn't reach back and update the phone on
its own.

**Fixed structurally, not by making `/start` more reliable:** rather
than chase every way that one fire-and-forget call could still miss,
`ingest_server.py`'s three bulk endpoints (`measurements`, `locations`,
`events`) each now call `_ensure_session_placeholder(session_id)`
first, an `INSERT OR IGNORE INTO sessions (session_id,
completion_status)`. A session invented this way is missing
`vehicle_profile`/adapter info that only a real `/start` carries, but
that's cosmetic for analysis: `analyze_drive.py` reads the
measurements/locations/events rows, not those fields. Verified: a bulk
call for a brand-new session ID with no prior `/start` at all now
auto-creates its placeholder row, and a real `/start` racing in later
still lands normally alongside it (`INSERT OR IGNORE`, not `REPLACE`,
so it can't clobber real metadata that got there first).
`analysis_worker._analyze` also now checks `sess_row.empty` and raises
a named `ValueError` instead of falling through to the bare
`sess_row.iloc[0]` `IndexError`, cheap insurance for whatever the next
undiscovered way to reach a missing row turns out to be.

## Nine manufacturer-specific enhanced PIDs added (Subaru only, substantially untested)

Prompted by the fuel-rate investigation above: asked for a Subaru-specific
search for enhanced PIDs the way `MazdaEnhancedCommands.kt` already has for
the Mazda, specifically hoping for something closer to a direct fuel
signal than the MAF-based reconstruction. Source: ParsePID
(github.com/giotec/ParsePID)'s `Subaru_mode22_def.csv`, cross-checked
against that same repo's `2014 Forester FB25 non-Turbo Responses.txt`, a
real capture of a 2014 FB25 NA/CVT ECU's own "supported DIDs" bitmap.
That capture is meaningfully stronger evidence than the Mazda CSV had:
it's not a spreadsheet of plausible-looking definitions, it's a real ECU
of the same engine code and model year as this project's Outback
confirming these specific DIDs exist on it. Still a Forester, not this
exact Outback; treat as strong, not certain.

**Every formula hand-verified against its own byte-math extremes before
implementing** (same discipline the Mazda file used, for the same reason:
that file's Knock Control System catch was a real, previously-confirmed
failure mode for community-sourced PID data). All nine checked out
internally consistent, no equivalent of that earlier mismatch.

**Real bug caught and fixed before it ever ran:** wrote several of the new
handlers using bare `Int` arithmetic (`bytesToInt(...) - 50`, etc.) feeding
directly into `"%.1f".format(...)`, which throws at runtime in Kotlin
(`%f` requires a floating-point type). Every existing command in
`MazdaEnhancedCommands.kt` avoids this by ending its arithmetic in a
Float-typed operation (`* 8f`, `/ 32f`); the ones here that had no natural
float-promoting operation needed an explicit `f` suffix or `.toFloat()`
added. Confirmed by grepping every `.format(` call in the new file and
checking each one resolves to Float, then by a clean build.

**Real collision caught and fixed before it ever ran:** the natural name
for the new Target Engine RPM PID, "Target Engine Speed" (the source
file's own name), would have silently matched the existing `speed_kmh`
keyword's bare `\bspeed\b` pattern; confirmed by testing the regex
directly. Renamed to "Target Engine RPM" to sidestep that, matching this
project's existing "Engine RPM" naming for the standard PID, but that
still legitimately contains the word "RPM", which would then silently
match the existing bare `\brpm\b` pattern instead. Fixed with a negative
lookbehind on that pattern (`(?<!target engine )\brpm\b`), the same
category of fix `map_kpa`'s "desired" exclusion already needed for the
Mazda catalog. Verified with a script that cross-checks every canonical
name (old and new, 39 total) against the entire `PID_KEYWORDS` table:
zero collisions, each name matches exactly the one key it should.

**The nine, split by how fast they move** (Tier B for combustion-timescale
signals, Tier C for the electrical system's slower one):
- `Fuel Injector Pulse Width` (0x2210A3, `A*0.256` ms): the one genuinely
  new capability here. This project's fuel-burned estimate has been
  entirely MAF-derived because the standard Engine Fuel Rate PID (Mode 01
  0x5E) returns NO DATA on every session ever logged, confirmed directly
  against the server's full measurement history, Mazda and Subaru both,
  136 failed attempts logged on one drive alone. Pulse width is not
  itself a fuel-mass number: turning it into one needs this injector's
  flow rate and injection frequency, neither known yet, so nothing
  downstream converts it. Captured now as a second, independent signal to
  eventually cross-check the MAF-based estimate against, once that
  conversion exists, not a working second estimate today.
- `Learned Ignition Timing` (0x2210A5, `(A-128)/2` deg): deliberately not
  treated as an instantaneous knock-event signal. Per its own source
  definition and NASIOC discussion, this is closer to a learned/adaptive
  correction than a live knock reading, distinct from the existing
  standard Timing Advance PID and from RomRaider's Feedback/Fine Learning
  Knock Correction, for which no Mode 22 UDS mapping was found at all for
  this ECU (only its proprietary SSM logging, a different protocol this
  project's generic-ELM327 approach can't read).
- `Intake VVT Advance Angle Right`/`Left` (0x2210B4/B5, `A-50` deg): the
  source supplies no real min/max, so the plausibility clamp uses a
  physically chosen range (-20..60) rather than the formula's own raw
  byte-math bounds (-50..205), which the source itself doesn't claim is a
  real cam-angle limit.
- `Alternator Duty` (0x2210B2), `Battery Current` (0x221135, `A-128` A),
  `Battery Temperature` (0x221136, `A-40` C), `Alternator Control Mode`
  (0x221137, enum 0-5), `Target Engine RPM` (0x221121, `((A*256)+B)/4`
  rpm, same 2-byte `/4` scaling as the standard Engine RPM PID, a real
  internal-consistency signal, not coincidence). Electrical load
  ultimately shows up as mechanical load on the engine, worth having
  alongside the economy investigation even though it's a slower signal
  than the combustion-timescale four above.

**Deliberately excluded** despite appearing in the wider ParsePID
definition file: EGR Target/Actual Valve Opening Angle (0x22111B/111C),
marked diesel-only in the source and absent from the Forester capture's
supported-DID set entirely.

**Completely untested against a real vehicle**, same caveat every enhanced
PID in this project carries until a real drive confirms it. Built,
compiles clean, not yet installed as of this entry (phone disconnected).

## Foreground notification froze at "Initializing" for the whole drive (found and fixed)

Reported live: the lock screen / shade notification never updates once a
drive starts, stuck showing whatever it said in the first instant after
connecting.

**Root cause, confirmed by reading the actual call graph, not guessed:**
`updateNotification()` was called exactly once, right after the initial
connect/init sequence finished, before the polling loop had produced any
real samples yet. `LoggingStatus.state` itself updates live on every
single measurement (`connectionState = LOGGING`, `measurementCount + 1`,
etc., inside the `onMeasurement` callback), which is why the in-app UI,
which reads that state directly, looked completely fine the whole time.
Nothing was ever pushing those same live updates back into the
notification shown outside the app. It was built once and never touched
again until the drive ended.

**Fixed** with a ticker coroutine on the session's own timescale (every
3s, launched alongside the existing GPS collector job, cancelled the same
way in the same `finally` block) that calls `updateNotification()` for as
long as the session runs, independent of any one PID or reconnect
attempt.

**Also fixed alongside it, and part of why the notification's existing
Stop action may not have been reachable from a locked phone**: the
notification never called `setVisibility()`, which defaults to
`VISIBILITY_PRIVATE`. On a secured lock screen that can redact a
notification down to a generic "content hidden" line with no action
buttons at all, Stop included. Nothing in this notification is sensitive
(sample counts, connection state), so there was no reason to accept that
default. Set to `VISIBILITY_PUBLIC`, still subject to the device's own
"hide sensitive notifications" setting if the user has that on, which an
app has no business overriding.

Built, compiles clean. Not yet installed or confirmed on a real drive as
of this entry (phone disconnected).

## Send Intent (MacroDroid) automation reported as doing nothing (under investigation)

Reported live, alongside the notification issue above: firing the
configured MacroDroid "Send Intent" macro appears to do nothing at all,
no session starts.

**Checked and ruled out**: the macro's own configuration, confirmed
directly from a screenshot of the actual MacroDroid dialog. Target is
Broadcast (correct: Android 8+ delivers implicit broadcasts to dynamic
receivers only, never to a manifest-declared one like
`AutomationReceiver`, so this has to be explicit). Package
(`com.ericbarone.drivetrace`) and Class
(`com.ericbarone.drivetrace.service.AutomationReceiver`) are both filled
in and exactly correct, which is what makes an explicit broadcast
explicit in the first place; a missing Package/Class was the leading
suspect before seeing the screenshot, and it isn't the answer here.
Action string, and both Extras (`command`/`start`, `token`/a 20-char hex
value matching this project's token format) all look right too.

**Not yet checked**: whether the token in the macro actually matches
what's currently on this device (Settings > Automation), whether
`AutomationReceiver.onReceive` is being entered at all (would show
immediately in `adb logcat -s DriveTraceAutomation`, per that class's own
design), and whether MacroDroid's own trigger actually fired versus the
macro never running in the first place. `AutomationReceiver.kt` already
logs a specific, actionable line for every rejection path (missing
token, wrong token, no adapter ever selected, background-start refused),
so a live logcat capture during a real macro trigger should identify
this in one attempt rather than more code reading. Blocked on the phone
being reachable over adb to capture that.

### Update: root-caused live, via logcat, exactly as planned (fixed)

Two separate, compounding device-level barriers, confirmed one at a
time by reproducing the real macro's broadcast directly with
`adb shell am broadcast` and watching what changed after each fix, not
guessed:

1. **Battery optimization blocked the foreground-service start
   outright**: `ForegroundServiceStartNotAllowedException:
   startForegroundService() not allowed due to mAllowStartForeground
   false`. `AutomationReceiver.dispatch()` already logs the exact fix
   for this one (Settings > Apps > DriveTrace > Battery >
   Unrestricted); applied directly via `adb shell dumpsys deviceidle
   whitelist +com.ericbarone.drivetrace` on the test device instead.
2. **Deeper, only reachable once #1 stopped blocking it, and a real
   crash, not the graceful degradation the code assumed**: this
   service's manifest declares `foregroundServiceType=
   "connectedDevice|location"`. Android 14+ enforces a stricter,
   separate eligibility check on the "location" half specifically:
   starting it from the background without `ACCESS_BACKGROUND_LOCATION`
   throws `SecurityException` straight out of `startForeground()`,
   killing the whole process. This project's own comments (see
   `AutomationReceiver.kt`'s pre-start warning) predicted GPS would
   just come up empty for a background-started session, not that the
   entire session, OBD included, would never start at all. Confirmed
   by reproducing the exact crash with the permission ungranted, then
   confirming it stopped happening the moment the permission was
   granted, both directly via adb, not inferred.

**Fixed**: granted `ACCESS_BACKGROUND_LOCATION` on the test device
(the real fix, already anticipated by the manifest's own comment on
why that permission is declared but never requested in-app). Also
wrapped the `startForeground()` call in `onStartCommand` with a
`try/catch(SecurityException)` that logs one clear, actionable line
and returns cleanly instead of crashing, defense-in-depth for the next
phone that reaches this without the permission granted yet, matching
the reliability rule (section 9: detect the impossible, don't crash)
this project already applies everywhere else.

**Confirmed end to end** with the real MacroDroid macro after both
fixes: clean start, GPS actually recording live (17 fixes over about
15 seconds in a stationary test), clean stop, no crash.

## Stop logging gave no visual feedback while backfill/analysis ran (found and fixed)

Reported live, same session as the automation fix above: tapping Stop
in the app looked identical to still-logging for however long
backfill and analysis took in the background, up to close to a minute
if analysis was slow to answer, with nothing on screen saying so.

**Root cause**: the live/complete split in `LoggingScreen` is driven
entirely by `sessionComplete = connectionState == DISCONNECTED`, which
`DriveLoggingService.stopSession()` only sets at the very end of the
whole backfill-then-analyze chain. Nothing marked the gap in between.
The service was already updating `LoggingUiState.statusMessage`
through that whole sequence ("Verifying complete upload...", "Analyzing
drive..."), correctly, the whole time; nothing on this screen ever
read it.

**Fixed** with a local `stopping` flag, set the instant the Stop
dialog is confirmed rather than waiting on any state round trip, and a
new `StoppingBody` view (pulsing status dot, the service's own
already-correct `statusMessage`) shown in that gap. Also hides the
pinned action bar during that window: leaving "Stop logging" tappable
while a stop was already in flight would have fired a second
`ACTION_STOP` at a service that had already nulled out `sessionJob`,
running backfill and analysis a second time for the same session.

Build clean, installed. Confirmed on the real device for everything
except this specific fix, which needs one more real Stop tap to see;
not yet visually confirmed as of this entry.

## LTFT finally has real data: the MAF hypothesis is now supported, not just plausible

The whole point of this project. Four real Mazda drives now have valid
`Long Term Fuel Trim Bank 1` data (the PID-swap bug fixed earlier this
project is confirmed actually fixed, first time this signal has ever
existed here), each independently analyzed with tonight's corrected
fuel-rate math. Checked against the exact criteria
`MPG_ASSESSMENT_PLAYBOOK.md` already laid out for distinguishing a
vacuum leak from a MAF-calibration issue, not eyeballed:

- **LTFT sits at +5.8% to +8.4% on every drive, consistently, not
  once negative.** Session means: +5.77%, +6.20%, +6.30%, +5.7%
  (a never-cleanly-ended session recovered in the same sitting, see
  the pd.NA fix above). That consistency across four independent
  drives is itself evidence this is a real, repeatable bias, not
  session-to-session noise.
- **Split idle vs. moving**: idle runs about 2 points higher than
  moving (7.5-8.4% vs. 5.6-5.9%) on every drive, a mild gradient, not
  the sharp idle-only spike (+10-25%+) the playbook says a real vacuum/
  PCV leak produces.
- **Split by phase** (city/backroad/highway, matching
  `phase_breakdown()`): elevated at every single phase, same sign,
  same rough magnitude, with only a mild downward gradient at higher
  speed (city ~6.7-7.4%, backroad ~5.7-6.0%, highway ~5.0-5.6%). This
  is exactly the playbook's stated MAF-calibration signature:
  "consistent trim skew... across every phase_breakdown() row,
  rather than an idle-specific pattern."
- **Octane/knock-driven timing retard, checked and not supported**:
  Knock Retard stays near zero (means 0.40-0.41°, brief spikes to
  ~5.5-5.9° only), Timing Advance holds healthy positive values
  (means 17.8-21.7°). No evidence of chronic active knock mitigation
  on current (87 octane) fuel. Doesn't rule out a subtler base-map
  effect, but rules out the dramatic version of this hypothesis.

**What this data can't fully settle**: whether a ~6% MAF bias alone
accounts for the whole remembered MPG drop. No baseline drive exists
from when the car was known-good (the cross-drive comparison the
playbook already flagged as missing), and these four drives are
idle/city-heavy (13.5-24.1% idle fraction), which pulls MPG down on
its own regardless of any sensor issue, not proof by itself of
underperformance against EPA's combined rating.

**What it does settle**: this is no longer "a plausible theory that
can't be checked," it's a real, repeatable, multi-drive-confirmed
signal, in exactly the shape a MAF sensor mildly under-reporting
airflow produces, cleanly distinguished from a vacuum leak by its own
methodology. A MAF sensor clean (cheap, ~10 minutes, isopropyl alcohol
or MAF-specific cleaner) or replacement is a concrete, justified next
step, not a shot in the dark. The direct confirmation test: log
another comparable drive after cleaning it and check whether LTFT
drops back toward 0%.

**Not yet done**: `anomaly_flags()`'s existing fuel-trim check only
fires above +/-10% during cruise, calibrated for a large, obvious skew;
none of these four drives crossed it despite the finding above being
real, which is why it took a manual per-phase query to surface at all
rather than showing up on the report automatically. Worth a second,
lower-threshold flag specifically for "same-sign skew consistent
across every phase" if this keeps mattering, not done tonight since
the immediate ask was the finding itself, not the tooling around it.
