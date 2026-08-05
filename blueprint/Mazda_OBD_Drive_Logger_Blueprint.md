# Mazda OBD Drive Logger — Claude Code Build Blueprint

**Target vehicle:** 2020 Mazda 6 Grand Touring, 2.5L turbo  
**Target device:** Android phone + Bluetooth Classic ELM327 v1.5-style adapter  
**Primary mission:** Reliably record a complete 45-minute drive for offline diagnosis of a sustained fuel-economy decline (historically ~34 MPG on this route and ~24 MPG tank average; now ~29 MPG route and ~19 MPG tank average).

## 1. Product decision

Build a focused, offline-first Android logger rather than a full diagnostic scanner. The first release should do four things exceptionally well:

1. Connect to a previously paired Bluetooth Classic ELM327 adapter.
2. Discover which standard Mode 01 PIDs the car actually supports.
3. Log a prioritized rotating set of powertrain PIDs, GPS, phone motion, timestamps, connection health, and raw ELM responses for an entire drive.
4. Export one self-contained session bundle for later Python analysis.

Do **not** attempt ECU coding, DTC clearing, proprietary Mazda commands, cloud accounts, AI diagnosis, or a polished gauge dashboard in v1.

## 2. Open-source reuse strategy

### Recommended foundation

**`eltonvs/kotlin-obd-api` (Apache-2.0)**  
Use for OBD command definitions, parsing, ELM AT setup, and its transport-agnostic `InputStream`/`OutputStream` design. This is the cleanest fit for a modern Kotlin application because Bluetooth transport remains under our control.

### Reference implementation to study, not copy wholesale

**`fr3ts0n/AndrOBD` (GPL-3.0)**  
Study its device compatibility, supported-PID handling, live-data recording, saved-session loading, and CSV export. Because it is GPL-3.0, do not copy code into an Apache/proprietary project unless the entire derivative is intentionally GPL-compatible.

### Testing dependency

**`Ircama/ELM327-emulator`**  
Use in desktop/integration tests to simulate ELM327 responses, malformed replies, timeouts, disconnects, multiple ECUs, and anomalous readings without sitting in the car.

### Historical references only

- `pires/android-obd-reader`: Apache-2.0 and useful for initialization sequences, but explicitly abandoned and built around very old Android tooling.
- `Tomiwa-Ot/obd`: Apache-2.0 Java library with Bluetooth examples; useful as a fallback reference, but less attractive than the Kotlin API.
- `Wal33D/OBD-Droid`: inspect architecture and CSV/GPS logging concepts, but verify code completeness and license before depending on it.

## 3. Architecture

Use Kotlin, Jetpack Compose, coroutines/Flow, Room, and a foreground service.

```text
UI (Compose)
  ├── Setup screen
  ├── Preflight screen
  ├── Active session screen
  └── Session/export screen

DriveLoggingService (foregroundServiceType="connectedDevice|location")
  ├── BluetoothClassicTransport
  ├── Elm327Session
  ├── CapabilityDiscovery
  ├── AdaptivePidScheduler
  ├── LocationCollector
  ├── PhoneSensorCollector
  ├── SessionRepository (Room + append-only raw log)
  └── HealthMonitor / reconnect state machine

ExportManager
  └── ZIP: metadata.json + samples_long.csv + snapshots_wide.csv + raw_elm.log + events.csv
```

The foreground service is mandatory so logging continues with the screen off or while navigation is visible. Start it only from an explicit user action while the app is foregrounded. Show a persistent notification with session duration, samples received, connection state, and a Stop action.

## 4. Android implementation requirements

- Minimum SDK: 26 or higher.
- Target current stable Android SDK.
- Bluetooth Classic SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`.
- Android 12+: request `BLUETOOTH_CONNECT`; request `BLUETOOTH_SCAN` only if scanning is implemented. Prefer selecting from bonded devices for v1.
- Location: request precise foreground location only if GPS logging is enabled. Do not request background location; the active foreground service covers the user-initiated drive.
- Android 14+: declare foreground service types and permissions for `connectedDevice` and `location` as applicable.
- Store live data in Room in small transactions; also write raw adapter traffic to an append-only file so parser defects do not destroy evidence.
- Export through Android's Storage Access Framework with `ACTION_CREATE_DOCUMENT`; no broad storage permission.
- Acquire a partial wake lock only during an active session, and release it deterministically.
- Add a clear warning: configure and start logging while parked; do not operate the phone while driving.

## 5. ELM327 connection and initialization

Implement a strict state machine:

```text
DISCONNECTED → CONNECTING_SOCKET → INITIALIZING_ELM → DETECTING_PROTOCOL
→ DISCOVERING_CAPABILITIES → READY → LOGGING
        ↘ RETRY_WAIT / FAILED
```

Initialization sequence, accepting clone-specific variation:

```text
ATZ       reset
ATE0      echo off
ATL0      linefeeds off
ATS0      spaces off
ATH0      headers off initially
ATSP0     automatic protocol
ATAT1     adaptive timing
ATST32    conservative timeout starting point
ATI       capture reported adapter identity/version
ATDP      capture detected protocol
0100      force ECU communication and retrieve supported PIDs 01–20
0120/0140/0160... only when prior bitmap indicates the next range
```

Rules:

- Every command ends with `\r`.
- Read until the `>` prompt, not until a fixed byte count.
- Strip command echo, whitespace, `SEARCHING...`, and prompt markers.
- Preserve the exact raw response with monotonic send/receive timestamps.
- Handle `NO DATA`, `STOPPED`, `UNABLE TO CONNECT`, `CAN ERROR`, `BUS INIT`, `?`, and timeouts as typed outcomes rather than parser crashes.
- Permit one command in flight at a time. Cheap ELM clones are usually serial request/response devices.
- On repeated failures, reconnect the RFCOMM socket and rerun initialization; never silently continue a session with stale values.

## 6. PID logging strategy

A cheap ELM adapter cannot sample every PID rapidly. Use priority tiers and a rotating scheduler. Log each measurement as its own timestamped row rather than pretending all PIDs were sampled simultaneously.

### Tier A — sample as frequently as the adapter allows

| PID | Signal | Why it matters |
|---|---|---|
| 010C | Engine RPM | Operating state and load context |
| 010D | Vehicle speed | Drive segmentation and distance cross-check |
| 0104 | Calculated engine load | Detect changed load/drag |
| 0110 | MAF, g/s | Airflow and fuel-use estimation |
| 0106 | STFT Bank 1 | Immediate fuel correction |
| 0107 | LTFT Bank 1 | Learned mixture correction |
| 0144 | Commanded equivalence ratio | Enrichment/open-loop context, if supported |
| 0142 | Control module voltage | Charging-system or sensor-reference issues |

Target combined Tier A cycle: roughly 1–3 complete cycles/second on a cheap adapter; record actual latency rather than enforcing an unrealistic rate.

### Tier B — sample every 2–5 seconds

| PID | Signal |
|---|---|
| 0105 | Coolant temperature |
| 010F | Intake-air temperature |
| 0111 | Throttle position |
| 0149 | Accelerator pedal position D, if supported |
| 014B | Accelerator pedal position E, if supported |
| 010B | Intake manifold absolute pressure |
| 0123 | Fuel-rail pressure, if applicable/supported |
| 015E | Engine fuel rate, if supported |
| 011C | OBD standard / vehicle compliance |

### Tier C — sample every 10–30 seconds or at state changes

- Fuel-system status (0103)
- Barometric pressure (0133)
- Ambient air temperature (0146)
- Run time since engine start (011F)
- Distance since codes cleared (0131)
- Warm-ups and distance since DTC clear (0130/0131 where supported)
- Oxygen/wideband sensor PIDs reported as supported
- Commanded EGR / purge where supported

### One-time session metadata

- VIN (Mode 09 PID 02), when available
- Supported PID bitmaps
- Monitor status and readiness
- Current, pending, and permanent DTCs — **read only**, never clear
- Adapter identity, protocol, phone model, Android version, app version
- User-entered notes: fuel octane, HVAC setting, passengers/cargo, tire pressure, weather note, recent service

## 7. Derived values to calculate after collection

Keep raw values authoritative. Derivations may be displayed live, but recompute offline.

- **Combined trim:** `STFT + LTFT` at each available point.
- **MAF-estimated gasoline consumption:** approximate fuel mass from MAF divided by stoichiometric AFR when commanded equivalence ratio is near 1; convert using fuel density. Mark as an estimate, especially during enrichment.
- **Instantaneous MPG:** GPS/OBD speed divided by estimated gallons/hour; suppress at very low speed.
- **Trip distance:** integrate GPS and separately integrate OBD speed; compare the two.
- **Idle fraction:** percentage of session with speed near zero and engine running.
- **Warm-up duration:** time until coolant reaches a stable operating band.
- **Cruise windows:** stable speed, low acceleration, warmed engine; these are the best apples-to-apples segments.
- **Load-at-speed:** compare calculated load and MAF within matched speed/grade/acceleration windows.

## 8. Data model and export contract

### Room entities

**Session**

```text
session_id, start_wall_time_utc, start_elapsed_ns, end_wall_time_utc,
vehicle_profile, adapter_name, adapter_mac_hash, adapter_identity,
protocol, app_version, phone_model, notes, completion_status
```

**Measurement (long format)**

```text
session_id, sequence, wall_time_utc, elapsed_ns,
source, pid, canonical_name, value_numeric, value_text, unit,
request_started_ns, response_received_ns, latency_ms,
quality_flag, raw_response_ref
```

**LocationSample**

```text
session_id, elapsed_ns, wall_time_utc, latitude, longitude,
altitude_m, speed_mps, bearing_deg, horizontal_accuracy_m,
vertical_accuracy_m, provider
```

**Event**

```text
session_id, elapsed_ns, event_type, severity, message, details_json
```

### Export bundle

```text
mazda_obd_YYYY-MM-DD_HH-mm_session-id.zip
  metadata.json
  samples_long.csv
  snapshots_wide_1s.csv
  locations.csv
  events.csv
  raw_elm.log
  supported_pids.json
  dtcs.json
  checksums.sha256
```

`samples_long.csv` is the source of truth. `snapshots_wide_1s.csv` is a convenience table produced by nearest-value/as-of joining each PID into one-second bins, with `age_ms_<signal>` columns so analysts know how stale each value was.

## 9. Reliability requirements

- Survive screen-off for at least 90 minutes.
- Persist each accepted measurement promptly; a crash should lose no more than five seconds.
- Log command latency, timeouts, parse failures, reconnects, and dropped GPS updates.
- Detect impossible/stuck values and flag them without deleting them.
- Use monotonic time (`elapsedRealtimeNanos`) for ordering and intervals; retain UTC wall time for human use.
- On Bluetooth disconnect, retry with exponential backoff capped at 15 seconds while preserving the same session.
- Provide a conspicuous “logging healthy” indicator based on recent successful PID responses, not merely socket connectivity.
- End-of-session summary must report duration, total measurements, per-PID counts, median/p95 latency, reconnect count, and missing-data percentage.

## 10. UI scope

### Setup

- Select a bonded Bluetooth device.
- Vehicle profile: “2020 Mazda 6 2.5T.”
- Toggle GPS logging.
- Add session notes.
- Test adapter button.

### Preflight

Show explicit pass/fail results:

- Bluetooth permission granted
- Adapter socket opened
- ELM identity returned
- Vehicle ECU responds
- Protocol detected
- Required core PIDs supported
- GPS permission/fix status
- Battery optimization warning acknowledged
- Available storage sufficient

### Active session

Large, glanceable display only:

- Green/yellow/red logger health
- Elapsed time
- Measurements written
- Current connection state
- Last successful sample age
- Stop button requiring confirmation

No complex dashboard in v1; navigation can remain foreground.

## 11. Test plan

### Unit tests

- PID bitmap parsing
- Hex decoding and signed/unsigned formulas
- Echo/whitespace/prompt removal
- Multi-line and multi-ECU replies
- Timeout and malformed-response handling
- Scheduler fairness and priority
- One-second as-of snapshot generation
- MPG and fuel-rate derivations

### Emulator/integration tests

Use ELM327-emulator scenarios for:

- Normal CAN vehicle
- Slow adapter
- Random `NO DATA`
- Disconnect mid-session
- Stuck PID
- Corrupted hexadecimal response
- ECU response after `SEARCHING...`
- App process recreation and session recovery

### Real-car acceptance test while parked

1. Pair adapter in Android settings.
2. Start engine outdoors.
3. Test a five-minute idle session.
4. Confirm RPM, speed=0, coolant warming, voltage, MAF, and fuel trims.
5. Export and inspect CSV before trusting a road session.

## 12. Claude Code implementation sequence

Give Claude Code these milestones one at a time and require tests/build success before moving on.

### Milestone 1 — project skeleton

Create a Kotlin Android app using Compose, coroutines, Room, and a foreground service. Add setup, preflight, logging, and session-list screens with fake data. Establish CI with unit tests and a debug APK artifact.

### Milestone 2 — Bluetooth Classic transport

Implement bonded-device selection and RFCOMM SPP connection. Expose suspend `writeCommand()` and a prompt-delimited response stream. Add instrumented tests around lifecycle cancellation and reconnection.

### Milestone 3 — ELM session and capability discovery

Integrate `kotlin-obd-api` where useful. Implement initialization, adapter metadata, supported-PID discovery, typed errors, and raw traffic recording.

### Milestone 4 — scheduler and persistence

Implement the tiered PID scheduler, long-format Room persistence, quality flags, and service notification. Fake-transport tests must prove that slow Tier C PIDs never starve Tier A.

### Milestone 5 — GPS and session health

Add fused location updates, monotonic timestamp alignment, wake lock, health scoring, reconnect logic, and crash-safe session recovery.

### Milestone 6 — export

Create the ZIP export contract, long CSV, one-second wide snapshots, metadata, DTCs, raw log, and checksums through `ACTION_CREATE_DOCUMENT`.

### Milestone 7 — analysis notebook

Add a Python notebook/script that loads a bundle and generates:

- PID coverage and latency report
- STFT/LTFT distributions by idle, acceleration, and cruise
- Combined fuel-trim plot over time
- MAF/load/RPM/speed plots
- Estimated fuel rate and MPG
- Warm-up analysis
- Matched cruise-window table
- GPS route/elevation profile
- Automatic anomaly flags with cautious language

## 13. Copy/paste prompt for Claude Code

```text
Build an offline-first Android application named DriveTrace OBD for a 2020 Mazda 6 2.5T and a paired Bluetooth Classic ELM327 adapter. Follow the attached blueprint exactly.

Priorities: uninterrupted 45–90 minute logging, preservation of raw evidence, accurate timestamps, graceful recovery from cheap-adapter failures, and a deterministic export bundle for Python analysis. This is not a general scanner and must never write to an ECU or clear codes.

Use Kotlin, Jetpack Compose, coroutines/Flow, Room, and a connected-device foreground service. Prefer selecting an already bonded adapter. Implement one-command-at-a-time ELM communication over RFCOMM SPP, prompt-delimited reads, a typed initialization state machine, supported-PID discovery, a priority-based rotating scheduler, GPS logging, raw traffic logs, health metrics, and ZIP/CSV/JSON export through the Storage Access Framework.

Use eltonvs/kotlin-obd-api where it reduces duplicate parsing work, but wrap it behind our own interfaces. Study AndrOBD for behavioral ideas only; do not copy GPL code. Use Ircama/ELM327-emulator for integration tests. Keep transport, parsing, scheduling, persistence, and UI independently testable.

Begin with Milestone 1 only. Create an implementation plan, directory structure, Gradle configuration, data/domain interfaces, fake logger, initial Compose screens, tests, and README. Build and run tests before reporting completion. Do not proceed to Milestone 2 until Milestone 1 is clean.
```

## 14. Procedure for today’s return drive

Because the custom app will not be ready for immediate use, do not rush an untested logger onto the road. First validate the adapter while parked with an existing app or a five-minute prototype test. For the eventual controlled comparison:

1. Confirm tire pressures cold and record them.
2. Record fuel level/octane, passengers/cargo, HVAC setting, outside temperature, wind/rain, and whether the engine begins cold or warm.
3. Mount the phone and start logging before moving.
4. Do not touch the app while driving.
5. Drive the familiar route normally; avoid deliberately changing driving style to “improve” the result.
6. Stop and export only after parking.
7. Preserve the car’s end-of-trip MPG readout as a note/photo, but treat it as a comparison signal rather than ground truth.

A single drive can reveal gross abnormalities, but diagnosing a 20% fuel-economy change is stronger with repeated matched trips in both directions and tank-level fuel calculations.

## 15. Diagnostic hypotheses the dataset should distinguish

- Positive LTFT/STFT: unmetered air, MAF under-reporting, fuel-pressure/delivery issue, exhaust leak ahead of sensor.
- Negative trims: excessive fuel delivery, purge/injector issue, MAF over-reporting, sensor bias.
- Normal trims but high MAF/load at matched speed: rolling resistance, brake drag, alignment, tire change, aerodynamic drag, drivetrain load, route/traffic change.
- Prolonged warm-up or low coolant temperature: thermostat or temperature-sensor issue.
- Voltage abnormality: charging-system load or sensor-reference concern.
- Normal engine data but MPG discrepancy: idle time, traffic, driving pattern, tire diameter/readout calibration, fuel formulation, HVAC use, or trip-computer estimation change.
- Change coincident with an oil service: verify oil level is not overfilled, viscosity/spec is correct, airbox/MAF/intake plumbing was not disturbed, and no undertray or brake/tire issue occurred around the same period. The oil brand itself is unlikely to explain a drop of roughly five MPG.

## Sources checked

- AndrOBD repository: https://github.com/fr3ts0n/AndrOBD
- Kotlin OBD API: https://github.com/eltonvs/kotlin-obd-api
- ELM327 emulator: https://github.com/Ircama/ELM327-emulator
- Legacy Android OBD reader: https://github.com/pires/android-obd-reader
- Java OBD library: https://github.com/Tomiwa-Ot/obd
- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
