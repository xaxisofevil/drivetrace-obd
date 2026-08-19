# DriveTrace

An offline-first drive logger built to answer one question: **why did fuel
economy on a 2020 Mazda 6 2.5T drop from ~34 MPG / ~24 MPG tank average to
~29 MPG / ~19 MPG tank average on the same route?** See
`blueprint/Mazda_OBD_Drive_Logger_Blueprint.md` for the original spec this
project was built from.

It logs OBD-II data (RPM, speed, load, MAF, fuel trims, coolant temp, and
more, see [docs/DATA_SCHEMA.md](docs/DATA_SCHEMA.md)) from a Bluetooth
ELM327 adapter plus GPS, via **two interchangeable loggers**:

- **Android app** (`app/`): phone-based, Bluetooth to the car, GPS built in
- **PC logger** (`pc_logger/`): Windows laptop in the car, Bluetooth to the
  car directly, GPS from a phone running a separate app (GPSLogger)

Both write the identical CSV/DuckDB schema, so
[`scripts/analyze_drive.py`](scripts/README.md) works against either one's
output unmodified.

## Architecture at a glance

```
Android app  ----\
                   >---- identical schema ----> analyze_drive.py --> report + plots
PC logger    ----/                                    ^
                                                       |
Android app -- live stream + backfill --> DuckDB ingest server (server/)
                                                       |
                                              auto-analysis on Stop,
                                              results polled back to the app
```

Full details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Where to start

| I want to... | Go to |
|---|---|
| Set up and run the Android app | `app/` (Gradle project), pair the adapter in Android Bluetooth settings first |
| Set up and run the PC logger instead | [pc_logger/README.md](pc_logger/README.md) |
| Start and stop logging from MacroDroid/Tasker (e.g. on car Bluetooth) | [docs/AUTOMATION.md](docs/AUTOMATION.md) |
| Set up the home server (live streaming, auto-analysis) | [server/README.md](server/README.md) |
| Move the server to another machine | [docs/SERVER_MIGRATION_PLAN.md](docs/SERVER_MIGRATION_PLAN.md) |
| Run analysis on an exported drive | [scripts/README.md](scripts/README.md) |
| Understand the exact data schema / PID list | [docs/DATA_SCHEMA.md](docs/DATA_SCHEMA.md) |
| Add or change a screen without breaking the visual language | [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) |
| **Dig into a drive's data to find where fuel is being lost** | [docs/ANALYSIS_STARTING_POINTS.md](docs/ANALYSIS_STARTING_POINTS.md) |
| Understand a weird behavior or known bug before assuming it's new | [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) |
| **Pick up work from a different AI assistant / see where things stand right now** | [docs/HANDOFF.md](docs/HANDOFF.md) |
| Evaluate what's needed before selling/distributing this | [docs/COMMERCIAL_READINESS.md](docs/COMMERCIAL_READINESS.md) |

## Status

Validated against one real vehicle (2020 Mazda 6 2.5T) and one ELM327 clone
adapter across multiple real drives; none yet matching the original
sustained highway+backroad+city profile the diagnostic question actually
needs. The pipeline itself, connection, logging, export, streaming,
backfill, auto-analysis, trip history, forced retry, is proven working
end to end.

A second vehicle (2014 Subaru Outback 2.5i) was added at the architecture
level, picking a vehicle is now a Setup-screen choice instead of a
hardcoded constant, see `VehicleProfile.kt`/`PidCatalog.kt`, but its PID
catalog is untested as of writing, don't assume it's validated the way
the Mazda's is.
