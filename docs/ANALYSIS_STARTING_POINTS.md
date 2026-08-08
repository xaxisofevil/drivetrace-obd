# Analysis starting points

For picking this up cold and digging into a drive's data to find where fuel
efficiency is being lost. Read [DATA_SCHEMA.md](DATA_SCHEMA.md) first for
field names; this document is about what to actually do with them.

## The actual question

From the original blueprint: this car used to get ~34 MPG on a known route
(~24 MPG tank average), and now gets ~29 MPG on the same route (~19 MPG tank
average), a roughly 15% drop. The blueprint's section 15 lists the
diagnostic hypotheses this data is meant to distinguish between:

- **Positive fuel trims** (STFT/LTFT combined skewing positive): unmetered
  air, MAF under-reporting, a fuel-delivery shortfall, or an exhaust leak
  ahead of the sensor
- **Negative trims**: excessive fuel delivery, a stuck purge/injector, MAF
  over-reporting, O2 sensor bias
- **Normal trims but high MAF/load at matched speed**: rolling resistance,
  brake drag, alignment, tire change, aerodynamic drag, drivetrain load, or
  a route/traffic change (not the car at all)
- **Prolonged warm-up / low coolant temp**: thermostat or temperature-sensor
  issue
- **Voltage abnormality**: charging-system load or sensor-reference issue
- **Normal engine data but MPG discrepancy anyway**: idle time, traffic,
  driving pattern, tire diameter/calibration, fuel formulation, HVAC use, or
  a change in how the trip computer itself estimates MPG
- **Coincident with an oil service**: verify oil level/viscosity, that the
  airbox/MAF/intake wasn't disturbed, and no undertray/brake/tire issue
  happened around the same time; the oil brand itself is very unlikely to
  explain ~5 MPG

`anomaly_flags()` and `vehicle_awake_flags()` in `analyze_drive.py` already
check a few of these automatically (trim skew, voltage range, warm-up
duration). They're deliberately cautious, phrased as "consistent with X,"
never "diagnosed as X." Read the actual flag text before trusting a
conclusion, it's designed to be a lead, not an answer.

## What's already computed, per drive

Everything in `analyze_drive.py`, run either manually or automatically via
`analysis_worker.py`:

- `build_snapshot`: 1-second-grid as-of join of every PID, with an
  `age_s_<key>` column showing how stale each value was when carried
  forward (a PID sampled every 20s will show up to ~20s of staleness; don't
  mistake that for the value having "not changed")
- `add_derived_columns`: combined trim (STFT+LTFT), plus bank-2 combined
  trim and bank-to-bank asymmetry on a real multi-bank vehicle (e.g. the
  Subaru's boxer engine; not applicable to the Mazda's single-bank
  inline-4), boost pressure (MAP - Barometric, turbo health: a boost leak
  shows as trim going lean specifically under load, not idle, the mirror
  image of a PCV leak's idle-heavy pattern; MAF reading normal while boost
  underperforms for the same RPM/throttle points at a leak between the
  turbo and the cylinders rather than a compressor problem), intake air
  temp above ambient (a widening gap under boost points at a heat-soaked
  or failing intercooler), desired/target boost on the Mazda specifically
  (`boost_desired_kpa`, from a community-sourced enhanced PID, see
  KNOWN_ISSUES.md, a persistent gap vs. actual boost is itself a
  turbo-health signal), MAF-estimated fuel rate (gated on equivalence
  ratio near 1.0), instantaneous MPG (suppressed below ~8 km/h)
- `compute_idle_fraction`, `compute_warmup_duration_s`,
  `compute_trip_distance` (GPS vs OBD, compared), `compute_overall_mpg`
  (total distance / total fuel burned, not an average of the
  speed-suppressed instantaneous values)
- `find_cruise_windows`: stable-speed, low-acceleration, already-warmed
  segments: the best apples-to-apples comparison points *within* one drive
- `phase_breakdown`: mean load/MAF/trim/RPM grouped into highway
  (>90 km/h) / backroad (30-90) / city (<30) bands
- `pid_coverage_report`: sample counts and latency per PID, useful for
  spotting a PID that's mostly `IMPLAUSIBLE` or barely sampled at all
  before trusting derived numbers built from it
- `classify_phases` / `find_braking_waste_events`: per-second driving
  phase (idle/accelerating/cruising/coasting/decelerating/braking) and,
  for each braking event, an estimated fuel-equivalent cost of the
  kinetic energy dissipated as brake heat, plus whether a coast phase
  preceded it. Braking is inferred from deceleration rate only, no
  generic OBD-II PID exposes brake pedal position, and the fuel figure
  assumes a representative vehicle mass/engine efficiency, not measured
  for this car; see KNOWN_ISSUES.md for a real staleness bug this
  surfaced and fixed (Throttle Position needed promoting to Tier A).
  Cross-session GPS clustering (recognizing the same real-world spot
  across repeated drives) is the natural next step, not built yet.

## What hasn't been done yet, and probably should be next

**Cross-drive comparison is the actual missing piece.** Every function
above analyzes one drive in isolation. The diagnostic question is
inherently comparative (this drive vs. the historical baseline), and
nothing here does that yet. Concretely:

1. Get at least one drive on the known comparison route logged (the
   original ~34 MPG route). If that's not repeatable, the best fallback is
   comparing cruise-window load/MAF at matched speed bins *across whatever
   drives exist*, even different routes, since a genuine mechanical issue
   (rolling resistance, drag, a sensor problem) should show up as elevated
   load at the same speed regardless of where you're driving.
2. A `compare_drives.py` (doesn't exist yet) that loads two or more
   sessions' `cruise` tables, buckets by speed (e.g. 5 km/h bins), and
   diffs mean load/MAF/trim per bin between drives, that's the load-at-
   matched-speed comparison the blueprint calls for and the single biggest
   gap right now.
3. All logged sessions are already sitting in `server/drivetrace.duckdb`,
   queryable directly:
   ```sql
   SELECT session_id, canonical_name, AVG(value_numeric)
   FROM measurements
   WHERE canonical_name = 'Engine Load' AND quality_flag = 'OK'
   GROUP BY session_id;
   ```
   Join against `locations` on nearest `elapsed_ns` (per session, they
   don't share a timeline across sessions) for the same matched-speed idea
   without needing the CSV export step.
4. **Multi-session trend**: is combined trim drifting positive over time
   across sessions (suggesting a developing lean condition), or has it been
   flat, and the whole answer is elsewhere (tires, driving pattern, fuel
   formulation)? This just needs the per-session `mean_combined_trim_pct`
   already computed by `phase_breakdown`/`find_cruise_windows`, plotted
   session-over-session.
5. **DTC/monitor correlation**: one-time reads capture current/pending/
   permanent DTCs at session start (see `ONE_TIME_READ` events with
   `CURRENT_DTCS`/`PENDING_DTCS`/`PERMANENT_DTCS` keys). Nothing yet
   cross-references "does a pending code correlate with which sessions show
   trim excursions." Worth checking before assuming a code is unrelated
   noise.
6. **Oil-service timing**: if the user can supply the date of the last oil
   service, checking whether the efficiency drop's onset aligns with it
   (per the blueprint's hypothesis) is a simple date comparison against
   session timestamps once enough sessions exist to establish a trend line.

## Data quality caveats to check before trusting any of the above

- `pid_coverage_report` first, always. A PID with mostly `IMPLAUSIBLE`
  flags or very low sample count will make any derived statistic from it
  meaningless, not just noisy.
- Check `vehicle_awake_flags()`'s output for that session before trusting
  *anything* else in it. If RPM never exceeded the idle floor, the whole
  session may be placeholder data from a sleeping ECU.
- VIN never worked at all on the test vehicle (see KNOWN_ISSUES.md) and
  the Android app dropped the check entirely; sessions logged with it
  removed have no VIN event at all, not a failure, `vehicle_awake_flags()`
  correctly stays quiet on VIN for those and relies on RPM plausibility
  alone. Older sessions (or anything logged via `pc_logger`, which still
  attempts VIN) may still carry a VIN-failure flag, that's normal, not a
  sign something broke.
- `age_s_<key>` columns in the snapshot matter more for slow Tier C PIDs
  (barometric pressure, ambient temp, sampled every ~20s) than fast Tier A
  ones. A cruise-window calculation using a barometric reading that's 18
  seconds stale is normal, not a bug.
