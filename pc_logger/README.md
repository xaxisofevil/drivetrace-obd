# PC drive logger

A Windows/Python alternative to the Android app: connects to a paired
ELM327 over its Bluetooth virtual COM port and polls the same tiered PID
list (see `pids.py`), writing the same `samples_long.csv` / `events.csv` /
`metadata.json` schema as the app's CSV export, so `scripts/analyze_drive.py`
works against either logger's output unmodified.

Reuses the design already proven in the Android app (`app/src/main/kotlin/.../obd/`):
same PID tiers, same drop-after-repeated-failures behavior, same
exponential-backoff reconnect, same one-time VIN/DTC reads at session start.
The difference is transport: Windows exposes a paired Bluetooth Classic SPP
device as a plain COM port, so this is regular `pyserial`, no Bluetooth API
or Android toolchain involved.

There's no GPS here. Run GPSLogger on your phone during the drive (same as
originally planned) and pass its CSV export to the analysis script with
`--gps`; alignment happens by wall clock, not a shared monotonic timer,
since the laptop and phone don't share one.

## Setup

```powershell
cd projects\drivetrace-obd
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

## Pair the adapter

Pair the ELM327 in Windows Settings > Bluetooth & devices like any other
Bluetooth device. Windows will assign it a COM port automatically.

## Run

```powershell
.venv\Scripts\python -m pc_logger.main --list-ports
.venv\Scripts\python -m pc_logger.main --port COM5
```

Press Enter to stop; Ctrl+C also works. Output goes to
`data\raw\pc_session_<timestamp>\` unless `--out` is given.

## Analyze

```powershell
.venv\Scripts\python scripts\analyze_drive.py data\raw\pc_session_<ts> --gps path\to\gpslogger_export.csv
```

## Tests

```powershell
.venv\Scripts\python -m pytest pc_logger\tests\ -v
```

Covers the PID parse formulas, DTC/VIN decoding, and ELM response parsing
(SEARCHING/NO DATA/short-response handling) against known byte sequences.
These don't need real hardware; they're the same kind of coverage the
blueprint's ELM327-emulator tests were meant to provide for the Android app.

## What's not yet verified against real hardware

Everything above is unit-tested or schema-validated, but nothing has talked
to an actual ELM327 yet. The first real test is a parked session: pair the
adapter, run with the engine on, and confirm RPM/speed/coolant/voltage all
populate sensibly before trusting it on the road.
