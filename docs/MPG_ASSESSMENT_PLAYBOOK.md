# MPG assessment playbook

A prescriptive, per-failure-mode checklist for "does this drive's data show
a red flag for X." Companion to
[ANALYSIS_STARTING_POINTS.md](ANALYSIS_STARTING_POINTS.md) (which is about
what's computed and what's missing); this is about what to actually look
for once you're looking.

## Step 0: data-quality gate, before checking anything else

A failure mode isn't ruled out just because the data doesn't show it, if
the data itself is bad, "doesn't show it" is meaningless. Check in this
order, stop and fix rather than proceeding if any of these fail:

1. **`vehicle_awake_flags()` output for the session.** If VIN failed and
   RPM never exceeded the idle floor, the session may be entirely
   placeholder data. Don't proceed.
2. **`pid_coverage_report()`** for the specific PIDs the failure mode below
   depends on. A PID with a low sample count or that never once returned
   `OK` (check `quality_flag` breakdown, and check for `PID_COOLDOWN`
   events cycling repeatedly without ever succeeding) means you don't have
   an answer, not that the answer is "no issue."
3. **DTC codes are unverified.** DTC decoding involves nontrivial
   string/bit manipulation of exactly the kind that turned out to be buggy
   elsewhere in this library tonight (see KNOWN_ISSUES.md). The algorithm's
   been traced through and looks correct for the cases checked, but no code
   has been cross-checked against a second scan tool yet. Treat any DTC as
   a lead to verify, not a confirmed finding. Raw ELM response text is now
   captured (`raw_response` column, see DATA_SCHEMA.md) for every read
   going forward, so a session logged **after** this fix can have its DTCs
   spot-checked against the verbatim adapter text; sessions logged before
   it (including the one this playbook uses as a worked example) can't.
4. **How much idle time and how much trip does the session actually have?**
   A 3-minute trip with 20 seconds of idle cannot support a conclusion
   about idle-specific behavior, no matter how clean the data looks.

## Per-failure-mode signatures

### PCV valve / vacuum leak (unmetered air)

- **Look at**: STFT and LTFT, split by idle (`speed_kmh < 2`) vs. moving.
- **Red flag**: trim skewing positive, **and idle-specific**, i.e.
  noticeably worse at idle than at cruise. A leak's air volume is roughly
  constant regardless of RPM, so it's a much bigger fraction of total
  intake air at idle (low total flow) than at speed (high total flow). If
  trim is equally elevated at idle and cruise, suspect something else
  (MAF calibration, fuel delivery) instead, see below.
- **Magnitude**: real vacuum leaks typically show +10 to +25%+ at idle in
  a genuinely bad case. A couple of percent is normal noise, not a flag.
- **Also check**: idle RPM stability (a leak can cause rough/hunting idle),
  and whether P0171/P0174 (System Too Lean, Bank 1/2) shows up in the DTC
  reads, remembering the DTC-verification caveat above.
- **Worked example**: session 7 (this project's actual test drive) showed
  STFT idle mean +2.5% vs +0.7% moving, a mild skew but well under the
  magnitude a real leak produces. LTFT had zero valid readings all session
  (6 cooldown cycles, always `NoDataException`), which is itself the
  finding, no long-term-trim picture exists for that drive at all. DTCs
  present were chassis (`C`) codes, not the powertrain (`P`) codes this
  failure mode would produce. Conclusion: no strong signal either way, the
  data's too thin (3 minutes, almost no idle) and too incomplete (no LTFT)
  to trust a verdict.

### MAF sensor (under- or over-reporting)

- **Look at**: combined trim (STFT+LTFT) vs. MAF reading, across **all**
  speed/load ranges, not just idle. Unlike a vacuum leak, a miscalibrated
  MAF sensor's error is roughly proportional across the whole operating
  range, so the trim skew should look similar at idle, cruise, and load,
  not concentrated at idle.
- **Red flag**: consistent trim skew (same sign, similar magnitude) across
  every `phase_breakdown()` row (city/backroad/highway), rather than an
  idle-specific pattern.
- **Also check**: does MAF-derived load track OBD's own `Engine Load` PID
  sensibly at matched RPM/throttle, a MAF badly out of calibration can
  produce a load reading that doesn't match throttle position the way it
  should.

### O2 sensor bias / stuck sensor

- **Look at**: `Fuel-Air Commanded Equivalence Ratio` over time. This
  should hover near 1.0 during steady cruise (closed-loop operation) and
  move away from 1.0 deliberately during enrichment (hard acceleration,
  cold start).
- **Red flag**: the ratio pinned at exactly one value for an extended
  period regardless of driving conditions (a stuck sensor stops the
  ECU from correcting at all, so trims may look deceptively "normal" while
  the underlying mixture is actually off), or trim compensating hard in one
  direction consistently at every phase (see MAF above, similar signature,
  different root cause, distinguishing them needs component-level testing
  beyond what OBD alone can show).

### Thermostat / cooling issue

- **Look at**: `compute_warmup_duration_s()` and the raw coolant-temp curve
  from `make_plots()`'s warmup chart.
- **Red flag**: warm-up taking meaningfully longer than this specific
  car/engine normally does, or coolant temp never settling onto a stable
  plateau at all (oscillating, or climbing indefinitely).
- **Caveat**: "meaningfully longer than normal" needs a baseline from when
  the car was known-good. A single drive's warm-up time in isolation only
  tells you it happened, not whether it's slow, this is a cross-drive
  comparison, same gap flagged in ANALYSIS_STARTING_POINTS.md.

### Rolling resistance / brake drag / alignment / tire issue

- **Look at**: `Engine Load` and `Mass Air Flow` at **matched speed**
  across cruise windows, ideally across multiple drives, on flat ground.
- **Red flag**: elevated load/MAF at the same speed band **with normal
  fuel trims**. This is the key distinguishing feature from every failure
  mode above: the engine is working harder to maintain the same speed, but
  the fuel system itself isn't correcting for anything, because there's
  nothing chemically wrong with the air/fuel mixture, the car is just
  fighting more resistance.
- **This is the load-at-matched-speed comparison that doesn't exist yet as
  a built function** (see ANALYSIS_STARTING_POINTS.md), currently requires
  manually comparing `cruise` window tables across sessions.
- **Physical checks this data can't replace**: tire pressure (a simple,
  extremely common, extremely overlooked cause of a MPG drop this size),
  brake caliper drag (feel for uneven heat at each wheel after a drive),
  alignment.

### Fuel formulation / driving pattern / trip-computer changes

- **Look at**: nothing in the OBD data, this is the category the blueprint
  calls out as "normal engine data but MPG discrepancy anyway." Idle
  fraction, traffic pattern, HVAC/AC use, ambient temperature (already
  logged, Tier C), tire diameter or a tire change affecting the speedo/trip
  computer's own calibration, or a seasonal fuel-blend change are all real,
  common, and invisible to engine-sensor data. Don't force a mechanical
  explanation onto a pattern this category actually explains better.

## What "inconclusive" looks like, and what actually resolves it

Every worked example above needs things that don't exist yet for this
project as of this writing:
- **A real diagnostic drive**: sustained highway + backroad + city, on the
  known comparison route if possible, matching the original ask, not a
  3-minute errand.
- **A working LTFT signal**: currently failing 100% of the time in the one
  real drive checked so far. Source-level review ruled out a parsing
  difference between STFT and LTFT (identical command class, differs only
  by PID), and the failure pattern (starts in the first few seconds, never
  once recovers across the whole drive) rules out "adapter just needed to
  settle." Leading theory is a genuine vehicle/ECU condition this specific
  short trip never reached, unconfirmed. The raw-capture fix (see
  KNOWN_ISSUES.md) will show the literal adapter text on the next
  occurrence, that's what actually settles it either way.
- **A second drive to compare against**: single-drive analysis can flag
  "this looks off" but can't establish "this changed," which is what the
  underlying question actually is.
- **DTC cross-verification**: run a second, independent scan tool once
  during a real drive and compare its reported codes against this
  project's decoded ones, that's the fastest way to build confidence in
  (or catch a bug in) the DTC pipeline specifically.
