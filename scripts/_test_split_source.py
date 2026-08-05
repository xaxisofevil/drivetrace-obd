"""Quick smoke test: PC-logger-style bundle (no locations.csv) + a separate
GPSLogger CSV, verifying analyze_drive.py's cross-device wall-clock merge.
Not a permanent fixture, just a one-off validation script."""

import json
import subprocess
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "raw" / "split_source_test"
OUT.mkdir(parents=True, exist_ok=True)

t0 = 1754345000000  # arbitrary epoch ms, same "instant" for both fake devices

# PC logger side: samples_long.csv + events.csv + metadata.json, NO locations.csv
samples = pd.DataFrame(
    [
        {"sequence": 1, "wall_time_utc_ms": t0 + 0, "elapsed_ns": 0, "pid": "0C", "canonical_name": "Engine RPM",
         "value_numeric": 1500, "value_text": "", "unit": "RPM", "latency_ms": 120, "quality_flag": "OK"},
        {"sequence": 2, "wall_time_utc_ms": t0 + 1000, "elapsed_ns": int(1e9), "pid": "0D", "canonical_name": "Vehicle Speed",
         "value_numeric": 60, "value_text": "", "unit": "km/h", "latency_ms": 110, "quality_flag": "OK"},
        {"sequence": 3, "wall_time_utc_ms": t0 + 2000, "elapsed_ns": int(2e9), "pid": "0D", "canonical_name": "Vehicle Speed",
         "value_numeric": 65, "value_text": "", "unit": "km/h", "latency_ms": 110, "quality_flag": "OK"},
    ]
)
samples.to_csv(OUT / "samples_long.csv", index=False)
pd.DataFrame(columns=["elapsed_ns", "wall_time_utc_ms", "event_type", "severity", "message"]).to_csv(
    OUT / "events.csv", index=False
)
(OUT / "metadata.json").write_text(
    json.dumps(
        {
            "sessionId": 1, "startWallTimeUtc": t0, "endWallTimeUtc": t0 + 2000,
            "vehicleProfile": "2020 Mazda 6 2.5T", "adapterName": None, "adapterAddress": "COM5",
            "protocol": "ISO 15765-4", "appVersion": "pc_logger-0.1.0", "phoneModel": "PC:test",
            "notes": None, "completionStatus": "COMPLETED", "measurementCount": 3, "locationCount": 0,
        }
    )
)

# Phone side: a raw GPSLogger CSV with its real column names, clock offset
# by a couple seconds from the PC (simulating imperfect but close sync).
gps = pd.DataFrame(
    [
        {"time": "2026-08-04T18:30:01Z", "lat": 42.36, "lon": -71.06, "elevation": 40, "accuracy": 5,
         "bearing": 90, "speed": 16.7, "satellites": 8, "provider": "gps", "timestamp_ms": t0 + 500},
        {"time": "2026-08-04T18:30:02Z", "lat": 42.361, "lon": -71.059, "elevation": 41, "accuracy": 5,
         "bearing": 91, "speed": 18.0, "satellites": 8, "provider": "gps", "timestamp_ms": t0 + 1500},
    ]
)
gps_path = OUT / "gpslogger_raw.csv"
gps.to_csv(gps_path, index=False)

result = subprocess.run(
    [sys.executable, str(ROOT / "scripts" / "analyze_drive.py"), str(OUT), "--gps", str(gps_path),
     "--out", str(ROOT / "output" / "split_source_test")],
    capture_output=True, text=True,
)
print(result.stdout)
print(result.stderr, file=sys.stderr)
sys.exit(result.returncode)
