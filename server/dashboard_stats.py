"""
Aggregate, read-only, multi-session statistics for the DriveTrace dashboard
(dashboard-mockups/01-instrument-dense.html, and its deployed counterpart in
xaxisofevil/quick's drivetrace-dashboard site).

Why this lives here rather than as a standalone script: a second
`duckdb.connect(..., read_only=True)` against server/drivetrace.duckdb fails
with the same single-writer-lock IOException every direct query against this
file hits while ingest_server.py holds its write connection open, confirmed
directly. The only process that can safely read this data is the one that
already owns that connection, so this module is imported by ingest_server.py
and reuses its `_conn` / `_db_lock` rather than opening a second handle.

Reuses scripts/analyze_drive.py's existing per-session functions the same
way server/analysis_worker.py does for a single session, just run across
every eligible session instead of one, and skipping the parts of that
pipeline the dashboard doesn't need (matplotlib plots, the markdown report,
the CSV snapshot dump) since those are disk writes a read endpoint has no
business doing on every request.

A drive only makes it into the `drives` list if it has valid combined
fuel-trim data and at least MIN_DRIVE_DIST_KM of travel, matching the filter
description dashboard-mockups/01-instrument-dense.html's own notice already
carried ("excludes short test/idle sessions"). Everything in `overview` is
computed across every session on the server regardless of that filter.
"""

from __future__ import annotations

import re
import sys
import threading
import time
from pathlib import Path
from typing import Any

import duckdb
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts import analyze_drive as ad  # noqa: E402

MIN_DRIVE_DIST_KM = 1.0

# Cheap in-process cache: rebuilding every session's 1-second snapshot on
# every dashboard load/refresh-interval tick is wasted work when nothing
# changed. Short enough that a drive logged moments ago still shows up
# quickly on a manual refresh.
_CACHE_TTL_S = 120
_cache: dict[str, Any] = {"computed_at": 0.0, "payload": None}
_cache_lock = threading.Lock()

# Matches a number anywhere in a driver-logged note that also mentions
# "dash"/"dashboard" ("15.9 on dashboard", "23mpg on dash"), the same
# free-text convention the notes field has used since the MAF investigation
# started (see KNOWN_ISSUES.md). Deliberately not anchored to a fixed
# phrasing so a future note in the same spirit still gets picked up, rather
# than hardcoding the handful of comparisons that existed when this was
# first written.
_DASH_NOTE_RE = re.compile(r"(\d+(?:\.\d+)?)")


def _dash_reading(note: str | None) -> float | None:
    if not note or "dash" not in note.lower():
        return None
    m = _DASH_NOTE_RE.search(note)
    return float(m.group(1)) if m else None


def _fmt_date(ms: float | None) -> str | None:
    if ms is None or pd.isna(ms):
        return None
    return pd.to_datetime(ms, unit="ms", utc=True).strftime("%Y-%m-%d")


def _fmt_datetime(ms: float | None) -> str | None:
    if ms is None or pd.isna(ms):
        return None
    return pd.to_datetime(ms, unit="ms", utc=True).strftime("%Y-%m-%d %H:%M")


def _clean(v: Any) -> Any:
    """pandas/numpy scalars and NaN don't survive FastAPI's JSON encoder as-is
    (NaN is a hard ValueError, numpy scalar types aren't recognized)."""
    if v is None:
        return None
    if isinstance(v, float) and v != v:  # NaN
        return None
    try:
        if pd.isna(v):
            return None
    except (TypeError, ValueError):
        pass
    if hasattr(v, "item"):  # numpy scalar -> python scalar
        v = v.item()
    if isinstance(v, float) and v != v:
        return None
    return v


def _round(v: Any, ndigits: int) -> Any:
    v = _clean(v)
    return round(v, ndigits) if v is not None else None


def _session_drive(session_id: int, sess_row: pd.Series, samples: pd.DataFrame,
                    locations: pd.DataFrame) -> dict[str, Any] | None:
    if samples.empty:
        return None

    events = pd.DataFrame(columns=["elapsed_ns", "wall_time_utc_ms", "event_type", "severity", "message"])
    data = ad.DriveData(metadata={"sessionId": int(session_id)}, samples=samples, locations=locations, events=events)
    data = ad.finalize_timing(data)
    snap, _ = ad.build_snapshot(data)
    snap = ad.add_derived_columns(snap)

    if "combined_trim_pct" not in snap or snap["combined_trim_pct"].dropna().empty:
        return None

    distance = ad.compute_trip_distance(snap)
    dist_km = distance.get("gps_km") or distance.get("obd_km")
    if not dist_km or dist_km < MIN_DRIVE_DIST_KM:
        return None

    overall_mpg = ad.compute_overall_mpg(snap, dist_km)
    idle_fraction = ad.compute_idle_fraction(snap)
    warmup_s = ad.compute_warmup_duration_s(snap)
    cruise = ad.find_cruise_windows(snap, warmup_s)
    phases = ad.phase_breakdown(snap)

    trim = snap["combined_trim_pct"]
    trim_idle = trim_moving = None
    if "speed_kmh" in snap and "rpm" in snap:
        idle_mask = (snap["speed_kmh"] < ad.IDLE_SPEED_THRESHOLD_KMH) & (snap["rpm"] > ad.IDLE_RPM_THRESHOLD)
        if idle_mask.any():
            trim_idle = trim[idle_mask].mean()
        if (~idle_mask).any():
            trim_moving = trim[~idle_mask].mean()

    notes = _clean(sess_row.get("notes"))
    mpg = _round(overall_mpg, 1)
    dash = _dash_reading(notes)

    phases_out = []
    if not phases.empty:
        for phase_name, row in phases.iterrows():
            phases_out.append({
                "phase": phase_name,
                "load_pct": _round(row.get("load_pct"), 2),
                "maf_gs": _round(row.get("maf_gs"), 2),
                "combined_trim_pct": _round(row.get("combined_trim_pct"), 2),
                "rpm": _round(row.get("rpm"), 2),
            })

    return {
        "session_id": int(session_id),
        "date": _fmt_date(sess_row.get("start_wall_time_utc_ms")),
        "datetime": _fmt_datetime(sess_row.get("start_wall_time_utc_ms")),
        "vehicle": _clean(sess_row.get("vehicle_profile")),
        "mpg": mpg,
        "trim": _round(trim.mean(), 2),
        "ltft": _round(snap["ltft1_pct"].mean(), 2) if "ltft1_pct" in snap else None,
        "stft": _round(snap["stft1_pct"].mean(), 2) if "stft1_pct" in snap else None,
        "trim_idle": _round(trim_idle, 2),
        "trim_moving": _round(trim_moving, 2),
        "idle_pct": _round(idle_fraction * 100, 1) if idle_fraction is not None else None,
        "warmup_min": _round(warmup_s / 60, 1) if warmup_s is not None else None,
        "knock_deg": _round(snap["knock_retard_deg"].mean(), 2) if "knock_retard_deg" in snap and snap["knock_retard_deg"].notna().any() else None,
        "dist_km": _round(dist_km, 2),
        "notes": notes,
        "dash_mpg": dash,
        "dash_gap_mpg": _round(mpg - dash, 1) if (mpg is not None and dash is not None) else None,
        "phases": phases_out,
        "cruise_windows": int(len(cruise)),
    }


def _compute(conn: duckdb.DuckDBPyConnection, db_lock: threading.Lock) -> dict[str, Any]:
    with db_lock:
        sessions = conn.execute(
            "SELECT session_id, start_wall_time_utc_ms, end_wall_time_utc_ms, vehicle_profile, "
            "notes, completion_status FROM sessions ORDER BY start_wall_time_utc_ms"
        ).fetchdf()
        measurements = conn.execute(
            "SELECT session_id, sequence, wall_time_utc_ms, elapsed_ns, pid, canonical_name, "
            "value_numeric, value_text, unit, latency_ms, quality_flag FROM measurements "
            "ORDER BY session_id, sequence"
        ).fetchdf()
        locations = conn.execute(
            "SELECT session_id, elapsed_ns, wall_time_utc_ms, latitude, longitude, altitude_m, "
            "speed_mps, bearing_deg, horizontal_accuracy_m, provider FROM locations "
            "ORDER BY session_id, elapsed_ns"
        ).fetchdf()

    overview = {
        "session_count": int(len(sessions)),
        "vehicle_profiles": {
            k: int(v) for k, v in sessions["vehicle_profile"].dropna().value_counts().items()
        },
        "completion_statuses": {
            k: int(v) for k, v in sessions["completion_status"].dropna().value_counts().items()
        },
        "date_min_ms": _clean(sessions["start_wall_time_utc_ms"].min()) if not sessions.empty else None,
        "date_max_ms": _clean(sessions["start_wall_time_utc_ms"].max()) if not sessions.empty else None,
    }

    meas_by_session = {sid: df for sid, df in measurements.groupby("session_id")} if not measurements.empty else {}
    loc_by_session = {sid: df for sid, df in locations.groupby("session_id")} if not locations.empty else {}
    empty_locations = pd.DataFrame(columns=["elapsed_ns", "wall_time_utc_ms", "latitude", "longitude",
                                             "altitude_m", "speed_mps", "bearing_deg",
                                             "horizontal_accuracy_m", "provider"])

    drives: list[dict[str, Any]] = []
    for _, sess_row in sessions.iterrows():
        sid = int(sess_row["session_id"])
        # A drive whose vehicle can't be confirmed can't be trusted for a per-vehicle trend
        # (see dashboard-mockups/01-instrument-dense.html's own notice: this field is null on
        # roughly half of all sessions, predating the field or logged via pc_logger).
        if pd.isna(sess_row.get("vehicle_profile")):
            continue
        samples = meas_by_session.get(sid)
        if samples is None or samples.empty:
            continue
        locs = loc_by_session.get(sid, empty_locations)
        try:
            drive = _session_drive(sid, sess_row, samples.reset_index(drop=True), locs.reset_index(drop=True))
        except Exception:
            # One malformed/edge-case session (e.g. mid-drive disconnect with almost no
            # samples) should never take the whole dashboard down; skip and keep going.
            continue
        if drive is not None:
            drives.append(drive)

    drives.sort(key=lambda d: d["session_id"])

    return {
        "overview": overview,
        "drives": drives,
        "computed_at_ms": int(time.time() * 1000),
    }


def compute_summary(conn: duckdb.DuckDBPyConnection, db_lock: threading.Lock, force: bool = False) -> dict[str, Any]:
    with _cache_lock:
        cached = _cache["payload"]
        fresh = cached is not None and (time.time() - _cache["computed_at"]) < _CACHE_TTL_S
        if fresh and not force:
            return cached

    payload = _compute(conn, db_lock)

    with _cache_lock:
        _cache["payload"] = payload
        _cache["computed_at"] = time.time()
    return payload
