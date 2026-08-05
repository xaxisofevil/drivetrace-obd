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
  convenience (`adb shell settings put system screen_off_timeout`); reset
  it to the device default for normal day-to-day battery life if this
  becomes a daily-driver setup rather than a testing rig.
