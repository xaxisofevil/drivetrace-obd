"""
Writes the same samples_long.csv / events.csv / metadata.json schema as the
Android app's CsvExporter.kt, so analyze_drive.py works against either
logger's output unmodified. There's no locations.csv here; GPS stays on the
phone via GPSLogger and gets merged in by analyze_drive.py.
"""

from __future__ import annotations

import csv
import json
import platform
import time
from pathlib import Path

from .scheduler import Measurement, OneTimeReadResult, SchedulerEvent

SAMPLES_HEADER = [
    "sequence", "wall_time_utc_ms", "elapsed_ns", "pid", "canonical_name",
    "value_numeric", "value_text", "unit", "latency_ms", "quality_flag", "raw_response",
]
EVENTS_HEADER = ["elapsed_ns", "wall_time_utc_ms", "event_type", "severity", "message"]


class SessionWriter:
    def __init__(self, out_dir: Path, vehicle_profile: str, port: str):
        self.out_dir = out_dir
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.vehicle_profile = vehicle_profile
        self.port = port
        self.start_wall_ms = int(time.time() * 1000)
        self.protocol: str | None = None
        self._measurement_count = 0
        self._event_count = 0

        self._samples_f = open(self.out_dir / "samples_long.csv", "w", newline="", encoding="utf-8")
        self._samples_writer = csv.writer(self._samples_f)
        self._samples_writer.writerow(SAMPLES_HEADER)

        self._events_f = open(self.out_dir / "events.csv", "w", newline="", encoding="utf-8")
        self._events_writer = csv.writer(self._events_f)
        self._events_writer.writerow(EVENTS_HEADER)

    @property
    def measurement_count(self) -> int:
        return self._measurement_count

    def write_measurement(self, m: Measurement) -> None:
        self._samples_writer.writerow(
            [m.sequence, m.wall_time_utc_ms, m.elapsed_ns, m.pid, m.canonical_name,
             m.value_numeric, m.value_text or "", m.unit, m.latency_ms, m.quality_flag,
             m.raw_response or ""]
        )
        self._measurement_count += 1

    def write_event(self, e: SchedulerEvent) -> None:
        self._events_writer.writerow(
            [e.elapsed_ns, int(time.time() * 1000), e.event_type, e.severity, e.message]
        )
        self._event_count += 1

    def write_one_time_reads(self, results: dict[str, OneTimeReadResult]) -> None:
        now_ms = int(time.time() * 1000)
        for key, result in results.items():
            message = f"{key}={result.value} | raw={result.raw_response}"
            self._events_writer.writerow([0, now_ms, "ONE_TIME_READ", "INFO", message])
            self._event_count += 1

    def flush(self) -> None:
        self._samples_f.flush()
        self._events_f.flush()

    def close(self, completion_status: str) -> None:
        self._samples_f.close()
        self._events_f.close()
        metadata = {
            "sessionId": 1,
            "startWallTimeUtc": self.start_wall_ms,
            "endWallTimeUtc": int(time.time() * 1000),
            "vehicleProfile": self.vehicle_profile,
            "adapterName": None,
            "adapterAddress": self.port,
            "protocol": self.protocol,
            "appVersion": "pc_logger-0.1.0",
            "phoneModel": f"PC:{platform.node()}",
            "notes": None,
            "completionStatus": completion_status,
            "measurementCount": self._measurement_count,
            "locationCount": 0,
        }
        (self.out_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
