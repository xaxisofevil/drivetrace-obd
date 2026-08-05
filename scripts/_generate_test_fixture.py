#!/usr/bin/env python3
"""
Generates a synthetic export bundle matching DriveTrace's exact CSV/JSON schema,
so analyze_drive.py can be tested before any real drive data exists. Not part
of the app or a real drive; delete or ignore this once real data is available.

Simulates a ~45-minute drive: cold start/idle, highway cruise, backroad, city
with light traffic, roughly matching the drive Eric described.
"""

import json
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

rng = np.random.default_rng(42)

DURATION_S = 45 * 60
OUT_DIR = Path(__file__).resolve().parent.parent / "data" / "raw" / "synthetic_test_bundle"


def build_speed_profile(t: np.ndarray) -> np.ndarray:
    speed = np.zeros_like(t, dtype=float)
    # 0-2min: idle before moving
    speed[t < 120] = 0
    # 2-5min: city getting to the highway
    mask = (t >= 120) & (t < 300)
    speed[mask] = np.clip((t[mask] - 120) / 180 * 45, 0, 45)
    # 5-28min: highway cruise ~110 km/h with gentle variation
    mask = (t >= 300) & (t < 1680)
    speed[mask] = 110 + 6 * np.sin((t[mask] - 300) / 90) + rng.normal(0, 2, mask.sum())
    # 28-38min: backroads ~55 km/h with more variation (turns, stop signs)
    mask = (t >= 1680) & (t < 2280)
    speed[mask] = 55 + 15 * np.sin((t[mask] - 1680) / 40) + rng.normal(0, 4, mask.sum())
    speed[mask] = np.clip(speed[mask], 0, 90)
    # 38-45min: city with light traffic, some full stops
    mask = t >= 2280
    local = t[mask] - 2280
    speed[mask] = np.clip(25 + 20 * np.sin(local / 60), 0, 50)
    # sprinkle in a couple of full stops during the city segment
    stop_windows = [(2350, 2380), (2500, 2520), (2650, 2670)]
    for s, e in stop_windows:
        speed[(t >= s) & (t < e)] = 0
    return np.clip(speed, 0, None)


def main() -> None:
    t = np.arange(0, DURATION_S, 1.0)
    speed_kmh = build_speed_profile(t)
    rpm = np.where(speed_kmh < 1, 750 + rng.normal(0, 15, len(t)), 1200 + speed_kmh * 18 + rng.normal(0, 60, len(t)))
    load_pct = np.clip(15 + speed_kmh * 0.35 + rng.normal(0, 3, len(t)), 5, 95)
    maf_gs = np.clip(2 + speed_kmh * 0.28 + rng.normal(0, 1, len(t)), 1, 60)

    # coolant warms from 15C to ~92C over about 6 minutes, then stays put
    coolant = 92 - 77 * np.exp(-t / 220)
    coolant += rng.normal(0, 0.3, len(t))

    # fuel trims: mostly near zero, mild positive drift to mimic a slight lean condition
    stft1 = rng.normal(2.5, 3.0, len(t))
    ltft1 = 3.0 + 0.5 * np.sin(t / 500) + rng.normal(0, 1.0, len(t))

    ce_ratio = np.clip(1.0 + rng.normal(0, 0.02, len(t)), 0.85, 1.15)
    voltage = np.where(rpm > 900, 14.2 + rng.normal(0, 0.15, len(t)), 12.6 + rng.normal(0, 0.1, len(t)))
    iat = 24 + rng.normal(0, 1, len(t))
    throttle = np.clip(10 + speed_kmh * 0.4 + rng.normal(0, 3, len(t)), 0, 100)
    map_kpa = np.clip(30 + speed_kmh * 0.5 + rng.normal(0, 2, len(t)), 20, 100)
    fuel_rail = 320 + rng.normal(0, 5, len(t))
    fuel_rate = np.clip(maf_gs / 14.7 * 1.2, 0.3, None)
    baro = 99 + rng.normal(0, 0.3, len(t))
    ambient = 21 + rng.normal(0, 0.5, len(t))
    runtime = t.copy()
    fuel_level = 62 - (t / DURATION_S) * 4

    channels = {
        "Engine RPM": ("rpm", "RPM", rpm),
        "Vehicle Speed": ("speed", "km/h", speed_kmh),
        "Calculated Engine Load": ("load", "%", load_pct),
        "Mass Air Flow": ("maf", "g/s", maf_gs),
        "Short Term Fuel Trim Bank 1": ("stft1", "%", stft1),
        "Long Term Fuel Trim Bank 1": ("ltft1", "%", ltft1),
        "Commanded Equivalence Ratio": ("ce_ratio", "ratio", ce_ratio),
        "Control Module Voltage": ("voltage", "V", voltage),
        "Engine Coolant Temperature": ("coolant", "C", coolant),
        "Intake Air Temperature": ("iat", "C", iat),
        "Throttle Position": ("throttle", "%", throttle),
        "Intake Manifold Pressure": ("map", "kPa", map_kpa),
        "Fuel Rail Pressure": ("fuel_rail", "kPa", fuel_rail),
        "Fuel Consumption Rate": ("fuel_rate", "L/h", fuel_rate),
        "Barometric Pressure": ("baro", "kPa", baro),
        "Ambient Air Temperature": ("ambient", "C", ambient),
        "Engine Run Time": ("runtime", "s", runtime),
        "Fuel Level": ("fuel_level", "%", fuel_level),
    }

    # Different PIDs sample at different real-world cadences (Tier A/B/C), so
    # subsample each channel rather than pretending everything is 1Hz.
    tier_stride = {
        "rpm": 1, "speed": 1, "load": 1, "maf": 1, "stft1": 1, "ltft1": 1, "ce_ratio": 1, "voltage": 1,
        "coolant": 3, "iat": 3, "throttle": 3, "map": 3, "fuel_rail": 4, "fuel_rate": 4,
        "baro": 20, "ambient": 20, "runtime": 20, "fuel_level": 25,
    }

    rows = []
    seq = 0
    start_wall_ms = 1754345000000  # arbitrary fixed epoch ms for reproducibility
    for name, (tag, unit, values) in channels.items():
        stride = tier_stride[tag]
        for i in range(0, len(t), stride):
            seq += 1
            rows.append(
                {
                    "sequence": seq,
                    "wall_time_utc_ms": start_wall_ms + int(t[i] * 1000),
                    "elapsed_ns": int(t[i] * 1e9),
                    "pid": tag.upper(),
                    "canonical_name": name,
                    "value_numeric": round(float(values[i]), 3),
                    "value_text": "",
                    "unit": unit,
                    "latency_ms": int(rng.integers(40, 220)),
                    "quality_flag": "OK",
                }
            )
    samples = pd.DataFrame(rows).sort_values("sequence")

    # GPS: simple synthetic path, 1Hz, roughly matching the speed profile
    lat0, lon0 = 42.3601, -71.0589  # arbitrary start point
    heading = np.cumsum(rng.normal(0, 0.01, len(t)))
    dx = (speed_kmh / 3.6) * np.cos(heading)
    dy = (speed_kmh / 3.6) * np.sin(heading)
    lat = lat0 + np.cumsum(dy) / 111_000
    lon = lon0 + np.cumsum(dx) / (111_000 * np.cos(np.radians(lat0)))
    altitude = 40 + 25 * np.sin(t / 900) + rng.normal(0, 1, len(t))

    locations = pd.DataFrame(
        {
            "elapsed_ns": (t * 1e9).astype(np.int64),
            "wall_time_utc_ms": (start_wall_ms + t * 1000).astype(np.int64),
            "latitude": lat,
            "longitude": lon,
            "altitude_m": altitude,
            "speed_mps": speed_kmh / 3.6,
            "bearing_deg": (np.degrees(heading) % 360),
            "horizontal_accuracy_m": rng.uniform(3, 8, len(t)),
            "provider": "gps",
        }
    )

    events = pd.DataFrame(
        [
            {"elapsed_ns": 0, "wall_time_utc_ms": start_wall_ms, "event_type": "ONE_TIME_READ", "severity": "INFO", "message": "VIN=1YVHP80C785M12345"},
            {"elapsed_ns": int(0.5e9), "wall_time_utc_ms": start_wall_ms + 500, "event_type": "ONE_TIME_READ", "severity": "INFO", "message": "TROUBLE_CODES="},
            {"elapsed_ns": int(612e9), "wall_time_utc_ms": start_wall_ms + 612000, "event_type": "RECONNECT", "severity": "WARNING", "message": "UnableToConnectException"},
            {"elapsed_ns": int(2100e9), "wall_time_utc_ms": start_wall_ms + 2100000, "event_type": "PID_UNSUPPORTED", "severity": "INFO", "message": "EGR_ERROR dropped after repeated NoDataException"},
        ]
    )

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    samples.to_csv(OUT_DIR / "samples_long.csv", index=False)
    locations.to_csv(OUT_DIR / "locations.csv", index=False)
    events.to_csv(OUT_DIR / "events.csv", index=False)

    metadata = {
        "sessionId": 1,
        "startWallTimeUtc": "2026-08-04T18:30:00Z",
        "endWallTimeUtc": "2026-08-04T19:15:00Z",
        "vehicleProfile": "2020 Mazda 6 2.5T",
        "adapterName": "OBDII",
        "adapterAddress": "00:1D:A5:68:98:8B",
        "protocol": "ISO 15765-4 (CAN 11/500)",
        "appVersion": "0.1.0",
        "phoneModel": "synthetic-test-fixture",
        "notes": None,
        "completionStatus": "COMPLETED",
        "measurementCount": int(len(samples)),
        "locationCount": int(len(locations)),
    }
    (OUT_DIR / "metadata.json").write_text(json.dumps(metadata, indent=2))

    zip_path = OUT_DIR.parent / "synthetic_test_bundle.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["metadata.json", "samples_long.csv", "locations.csv", "events.csv"]:
            zf.write(OUT_DIR / f, arcname=f)

    print(f"Wrote synthetic fixture to {OUT_DIR} and {zip_path}")


if __name__ == "__main__":
    main()
