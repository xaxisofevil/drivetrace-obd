"""
Runs analyze_drive.py's existing pipeline against a session already sitting in
this server's DuckDB, triggered by the app right after a successful backfill,
polled by the app afterward. Reuses the same functions the PC-run script
uses; this isn't a separate analysis path to keep in sync, it's the same one.

Full plots + the markdown report still get written to disk (output/<id>/)
exactly as a manual `python analyze_drive.py` run would, for later deep
review. What gets sent back to the phone is a compact JSON summary, a phone
screen isn't the place for matplotlib figures.
"""

from __future__ import annotations

import sys
import threading
from pathlib import Path
from typing import Any

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts import analyze_drive as ad  # noqa: E402

OUTPUT_ROOT = Path(__file__).resolve().parent.parent / "output"

_state: dict[int, dict[str, Any]] = {}
_state_lock = threading.Lock()


def _sanitize(value: Any) -> Any:
    """pandas/numpy computations on sparse data can produce NaN, which Starlette's JSON
    encoder rejects outright (not just discouraged, a hard ValueError). Recursively swap
    NaN/inf for None so the result is always valid JSON."""
    if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
        return None
    if isinstance(value, dict):
        return {k: _sanitize(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_sanitize(v) for v in value]
    return value


def get_status(session_id: int) -> dict[str, Any]:
    with _state_lock:
        return dict(_state.get(session_id, {"status": "not_requested"}))


def request_analysis(session_id: int, conn: duckdb.DuckDBPyConnection, db_lock: threading.Lock) -> None:
    with _state_lock:
        _state[session_id] = {"status": "running"}
    thread = threading.Thread(target=_run, args=(session_id, conn, db_lock), daemon=True)
    thread.start()


def _run(session_id: int, conn: duckdb.DuckDBPyConnection, db_lock: threading.Lock) -> None:
    try:
        summary = _analyze(session_id, conn, db_lock)
        with _state_lock:
            _state[session_id] = {"status": "done", "result": summary}
    except Exception as e:
        with _state_lock:
            _state[session_id] = {"status": "failed", "error": f"{e.__class__.__name__}: {e}"}


def _analyze(session_id: int, conn: duckdb.DuckDBPyConnection, db_lock: threading.Lock) -> dict[str, Any]:
    with db_lock:
        samples = conn.execute(
            "SELECT sequence, wall_time_utc_ms, elapsed_ns, pid, canonical_name, value_numeric, "
            "value_text, unit, latency_ms, quality_flag FROM measurements WHERE session_id = ? ORDER BY sequence",
            [session_id],
        ).fetchdf()
        locations = conn.execute(
            "SELECT elapsed_ns, wall_time_utc_ms, latitude, longitude, altitude_m, speed_mps, "
            "bearing_deg, horizontal_accuracy_m, provider FROM locations WHERE session_id = ? ORDER BY elapsed_ns",
            [session_id],
        ).fetchdf()
        events = conn.execute(
            "SELECT elapsed_ns, wall_time_utc_ms, event_type, severity, message FROM events "
            "WHERE session_id = ? ORDER BY elapsed_ns",
            [session_id],
        ).fetchdf()
        sess_row = conn.execute("SELECT * FROM sessions WHERE session_id = ?", [session_id]).fetchdf()

    if samples.empty:
        raise ValueError(f"No measurements found for session {session_id}")

    sess = sess_row.iloc[0]
    metadata = {
        "sessionId": session_id,
        "startWallTimeUtc": int(sess["start_wall_time_utc_ms"]) if sess["start_wall_time_utc_ms"] is not None else None,
        "endWallTimeUtc": int(sess["end_wall_time_utc_ms"]) if sess["end_wall_time_utc_ms"] is not None else None,
        "vehicleProfile": sess["vehicle_profile"],
        "adapterName": sess["adapter_name"],
        "adapterAddress": sess["adapter_address"],
        "protocol": sess["protocol"],
        "appVersion": sess["app_version"],
        "phoneModel": sess["phone_model"],
        "notes": sess["notes"],
        "completionStatus": sess["completion_status"],
        "measurementCount": len(samples),
        "locationCount": len(locations),
    }

    data = ad.DriveData(metadata=metadata, samples=samples, locations=locations, events=events)
    data = ad.finalize_timing(data)
    snap, _ = ad.build_snapshot(data)
    snap = ad.add_derived_columns(snap)

    idle_fraction = ad.compute_idle_fraction(snap)
    warmup_s = ad.compute_warmup_duration_s(snap)
    distance = ad.compute_trip_distance(snap)
    overall_mpg = ad.compute_overall_mpg(snap, distance.get("gps_km") or distance.get("obd_km"))
    cruise = ad.find_cruise_windows(snap, warmup_s)
    phases = ad.phase_breakdown(snap)
    coverage = ad.pid_coverage_report(data.samples)
    flags = ad.vehicle_awake_flags(data.samples, data.events) + ad.anomaly_flags(snap, cruise, data.events)
    driving_phase = ad.classify_phases(snap)
    braking_events = ad.find_braking_waste_events(snap, driving_phase)

    out_dir = OUTPUT_ROOT / str(session_id)
    out_dir.mkdir(parents=True, exist_ok=True)
    snap["phase"] = driving_phase
    snap.to_csv(out_dir / "snapshot_1s.csv", index=False)
    ad.make_plots(snap, out_dir)
    ad.write_report(
        out_dir, data, snap, coverage, idle_fraction, warmup_s, distance, overall_mpg, cruise, phases, flags,
        braking_events=braking_events,
    )

    return _sanitize({
        "idle_fraction_pct": round(idle_fraction * 100, 1) if idle_fraction is not None else None,
        "warmup_minutes": round(warmup_s / 60, 1) if warmup_s is not None else None,
        "distance_gps_km": round(distance["gps_km"], 2) if distance.get("gps_km") is not None else None,
        "distance_obd_km": round(distance["obd_km"], 2) if distance.get("obd_km") is not None else None,
        "overall_mpg": round(overall_mpg, 1) if overall_mpg is not None else None,
        "cruise_window_count": int(len(cruise)),
        "phases": phases.reset_index().to_dict(orient="records") if not phases.empty else [],
        "flags": flags,
        "braking_event_count": int(len(braking_events)),
        "braking_fuel_equiv_ml": round(float(braking_events["fuel_equiv_ml"].sum()), 1) if not braking_events.empty else 0.0,
        "braking_events_without_coast": int((~braking_events["coasted_first"]).sum()) if not braking_events.empty else 0,
        "report_dir": str(out_dir),
    })
