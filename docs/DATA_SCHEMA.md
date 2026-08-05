# Data schema

Three shapes of the same data exist: local Room (Android app), CSV export
bundle, and the DuckDB server. All three carry the same fields; only the
names' casing/format differs (Room/Kotlin is camelCase, CSV/DuckDB is
snake_case).

## Measurements (the core signal)

| CSV / DuckDB column | Room field | Type | Notes |
|---|---|---|---|
| `sequence` | `sequence` | int | Monotonic per session, shared across reconnects |
| `wall_time_utc_ms` | `wallTimeUtc` | epoch ms | |
| `elapsed_ns` | `elapsedNs` | ns since session start | Monotonic clock, not wall clock |
| `pid` | `pidTag` | string | e.g. `"0C"`, or the library's internal tag for some commands |
| `canonical_name` | `canonicalName` | string | **See confirmed PID table below** — these are the real strings, not assumptions |
| `value_numeric` | `valueNumeric` | double, nullable | Null if unparseable or flagged implausible |
| `value_text` | `valueText` | string, nullable | Set when `value_numeric` is null: either the raw string, or the same value when flagged implausible (never silently discarded) |
| `unit` | `unit` | string | |
| `latency_ms` | `latencyMs` | int | Round-trip time for that one command |
| `quality_flag` | `qualityFlag` | string | `"OK"` or `"IMPLAUSIBLE"` (see below) |
| `raw_response` | `rawResponse` | string, nullable | Verbatim ELM text for this exact read, before the library's own whitespace/bus-init/colon cleanup. Added after session 7; older rows have `null` here, not a bug. See KNOWN_ISSUES.md |

### Confirmed real PID canonical_name strings

These came from actually parsing kotlin-obd-api's source and from live
vehicle responses, not assumptions. If you're matching on `canonical_name`
anywhere (see `scripts/analyze_drive.py`'s `PID_KEYWORDS`), use these:

| PID (hex) | `canonical_name` | Unit | Tier | Notes |
|---|---|---|---|---|
| 0C | `Engine RPM` | RPM | A | Fixed upstream (pinned commit), see KNOWN_ISSUES |
| 0D | `Vehicle Speed` | km/h | A | |
| 04 | `Engine Load` | % | A | Not "Calculated Engine Load" |
| 10 | `Mass Air Flow` | g/s | A | Uses `SafeMassAirFlowCommand`, see KNOWN_ISSUES |
| 06 | `Short Term Fuel Trim Bank 1` | % | A | |
| 08 | `Long Term Fuel Trim Bank 1` | % | A | Prone to early-cooldown, see KNOWN_ISSUES |
| 44 | `Fuel-Air Commanded Equivalence Ratio` | ratio | A | Not "Commanded Equivalence Ratio" |
| 42 | `Control Module Power Supply` | V | A | Not "Control Module Voltage"; uses `SafeModuleVoltageCommand` |
| 05 | `Engine Coolant Temperature` | °C | B | |
| 0F | `Air Intake Temperature` | °C | B | |
| 11 | `Throttle Position` | % | B | |
| 0B | `Intake Manifold Pressure` | kPa | B | |
| 22 | `Fuel Rail Pressure` | kPa | B | Uses `SafeFuelRailPressureCommand`; often `NoDataException` on this vehicle |
| 5E | `Fuel Consumption Rate` | L/h | B | Uses `SafeFuelConsumptionRateCommand`; often `NoDataException` on this vehicle |
| 33 | `Barometric Pressure` | kPa | C | |
| 46 | `Ambient Air Temperature` | °C | C | |
| 0E | `Timing Advance` | ° | B | Not in kotlin-obd-api; custom `TimingAdvanceCommand`, added to check for knock-related timing retard (see KNOWN_ISSUES.md) |
| 1F | `Engine Runtime` | s | C | Uses `SafeEngineRuntimeCommand`; library's `RuntimeCommand` queries PID 0F (Intake Air Temp) instead of 1F, root cause of the old "always parses to null" note |
| 31 | `Distance traveled since codes cleared` | km | C | Uses `SafeDistanceSinceCodesClearedCommand` |
| 2C | `Commanded EGR` | % | C | |
| 2D | `EGR Error` | % | C | |
| 2F | `Fuel Level` | % | C | |

One-time reads at session start: VIN (Mode 09 PID 02, **has not worked on
the test vehicle**, throws `NonNumericResponseException`, unresolved and
deprioritized, see KNOWN_ISSUES), fuel type, MIL status, current/pending/
permanent DTCs (read-only, this app and pc_logger never clear codes), and
the ECU's own declared supported-PID bitmask across all five Mode 01
ranges (PID 00/20/40/60/80, tags `AVAILABLE_COMMANDS_PIDS_01_TO_20` etc.,
via `AvailablePIDsCommand`), added to independently verify whether a PID
that keeps returning `NO DATA` (e.g. LONG_TERM_BANK_1, see KNOWN_ISSUES)
is genuinely absent from this ECU or just not answering.

### quality_flag values

- `OK` — parsed successfully and within the plausible range for that PID
- `IMPLAUSIBLE` — parsed to a number outside a physically sane range for
  that PID (see `PLAUSIBLE_RANGES` in `PidScheduler.kt` / `analyze_drive.py`).
  `value_numeric` is null in this case; `value_text` holds the raw
  (nonsensical) value for forensics. Never silently dropped.

## Locations (GPS)

| Column | Type | Notes |
|---|---|---|
| `elapsed_ns`, `wall_time_utc_ms` | | Same clocks as measurements |
| `latitude`, `longitude` | double | |
| `altitude_m`, `speed_mps`, `bearing_deg`, `horizontal_accuracy_m` | double, nullable | |
| `provider` | string | Usually `"gps"` or `"fused"` |

If GPS comes from a separate device (PC logger + phone GPSLogger setup),
`analyze_drive.py --gps <file>` maps GPSLogger's own CSV columns (`lat`,
`lon`, `elevation`, `speed`, `timestamp_ms`, confirmed from GPSLogger's
`CSVFileLogger.java` source, not guessed) onto this schema, and alignment
happens by wall clock rather than the shared monotonic timer the
single-device case uses (two devices don't share a monotonic clock).

## Events

| Column | Type | Notes |
|---|---|---|
| `elapsed_ns`, `wall_time_utc_ms` | | |
| `event_type` | string | See table below |
| `severity` | string | `INFO` or `WARNING` |
| `message` | string | Free text |

| event_type | Meaning |
|---|---|
| `ONE_TIME_READ` | A session-start read (VIN, DTCs, etc.) succeeded; message is `KEY=value \| raw=<verbatim ELM text>` |
| `ONE_TIME_READ_FAILED` | Same, but threw; message is `KEY: <exception, including its raw response text>` |
| `PID_NO_DATA` | A single poll attempt failed (any exception subclass); message includes the verbatim ELM response text for that attempt, logged every time, not just when cooldown triggers |
| `PID_COOLDOWN` | A PID failed twice in a row and is pausing 30s before retrying (not a permanent drop) |
| `IMPLAUSIBLE_VALUE` | A measurement was clamped; message includes the raw value and the range it violated |
| `RECONNECT` | The Bluetooth link dropped and is retrying with exponential backoff |

## Metadata (session-level)

`sessionId` is assigned by the app as `System.currentTimeMillis()` at
session start (same value as `startWallTimeUtc`), **not** a Room
autoincrement column. This is deliberate: an autoincrement counter resets
to 1 after any local database wipe, and since this ID is also the server's
`session_id` with delete-then-replace backfill semantics, a reset counter
colliding with existing server history would silently destroy it. See
KNOWN_ISSUES.md for the real incident this fixed.

`startWallTimeUtc`, `endWallTimeUtc`, `vehicleProfile`,
`adapterName`, `adapterAddress`, `protocol` (detected via `ATDP`),
`appVersion`, `phoneModel`, `notes`, `completionStatus`
(`IN_PROGRESS`/`COMPLETED`/`INTERRUPTED`), `measurementCount`,
`locationCount`.

## Where each shape lives

- **Room** (Android, authoritative): `app/src/main/kotlin/.../data/Entities.kt`
- **CSV bundle**: written by `CsvExporter.kt`, one zip with
  `samples_long.csv`, `locations.csv`, `events.csv`, `metadata.json`
- **DuckDB** (server): schema created in `server/ingest_server.py`'s
  `_init_schema()`; same fields, snake_case, plus a `received_at_utc_ms`
  column server-side for debugging clock skew
