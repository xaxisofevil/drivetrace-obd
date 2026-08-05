"""
DriveTrace ingest server: accepts a best-effort live stream from the Android
app while it's on the road, writes straight into DuckDB. This is NOT the
authoritative data path, local Room + CSV export on the phone is, per the
blueprint's "keep raw values authoritative, never bet reliability on the
last hop" rule. This server is for live visibility/debugging and for
skipping the manual export/transfer step when the network happens to be up.

Run:
    uvicorn server.ingest_server:app --host 0.0.0.0 --port 8090

Auth: every request needs `Authorization: Bearer <token>`, where <token>
matches the DRIVETRACE_INGEST_TOKEN environment variable. Generate one with
`python -c "import secrets; print(secrets.token_urlsafe(32))"` and put it in
both this server's environment and the Android app's local.properties (see
server/README.md), never commit it.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Optional

import duckdb
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from . import analysis_worker

DB_PATH = Path(os.environ.get("DRIVETRACE_DB_PATH", Path(__file__).resolve().parent / "drivetrace.duckdb"))
EXPECTED_TOKEN = os.environ.get("DRIVETRACE_INGEST_TOKEN")

app = FastAPI(title="DriveTrace ingest server")
_db_lock = threading.Lock()
_conn = duckdb.connect(str(DB_PATH))


def _init_schema() -> None:
    with _db_lock:
        _conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                session_id BIGINT PRIMARY KEY,
                start_wall_time_utc_ms BIGINT,
                end_wall_time_utc_ms BIGINT,
                vehicle_profile VARCHAR,
                adapter_name VARCHAR,
                adapter_address VARCHAR,
                protocol VARCHAR,
                app_version VARCHAR,
                phone_model VARCHAR,
                notes VARCHAR,
                completion_status VARCHAR
            )
            """
        )
        _conn.execute(
            """
            CREATE TABLE IF NOT EXISTS measurements (
                session_id BIGINT,
                sequence BIGINT,
                wall_time_utc_ms BIGINT,
                elapsed_ns BIGINT,
                pid VARCHAR,
                canonical_name VARCHAR,
                value_numeric DOUBLE,
                value_text VARCHAR,
                unit VARCHAR,
                latency_ms BIGINT,
                quality_flag VARCHAR,
                received_at_utc_ms BIGINT,
                raw_response VARCHAR
            )
            """
        )
        # ALTER for DBs created before raw_response existed; CREATE TABLE IF NOT EXISTS above
        # only applies to a brand new file. See KNOWN_ISSUES.md's "DTC decoding is unverified"
        # section, this is the raw-capture fix that lets a parsed value be independently
        # re-checked against what the adapter actually said.
        _conn.execute(
            "ALTER TABLE measurements ADD COLUMN IF NOT EXISTS raw_response VARCHAR"
        )
        _conn.execute(
            """
            CREATE TABLE IF NOT EXISTS locations (
                session_id BIGINT,
                elapsed_ns BIGINT,
                wall_time_utc_ms BIGINT,
                latitude DOUBLE,
                longitude DOUBLE,
                altitude_m DOUBLE,
                speed_mps DOUBLE,
                bearing_deg DOUBLE,
                horizontal_accuracy_m DOUBLE,
                provider VARCHAR,
                received_at_utc_ms BIGINT
            )
            """
        )
        _conn.execute(
            """
            CREATE TABLE IF NOT EXISTS events (
                session_id BIGINT,
                elapsed_ns BIGINT,
                wall_time_utc_ms BIGINT,
                event_type VARCHAR,
                severity VARCHAR,
                message VARCHAR,
                received_at_utc_ms BIGINT
            )
            """
        )


_init_schema()


def _require_auth(authorization: Optional[str] = Header(None)) -> None:
    if not EXPECTED_TOKEN:
        raise HTTPException(status_code=500, detail="Server has no DRIVETRACE_INGEST_TOKEN configured")
    if authorization != f"Bearer {EXPECTED_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid or missing bearer token")


class SessionStart(BaseModel):
    session_id: int
    start_wall_time_utc_ms: int
    vehicle_profile: str
    adapter_name: Optional[str] = None
    adapter_address: Optional[str] = None
    app_version: Optional[str] = None
    phone_model: Optional[str] = None
    notes: Optional[str] = None


class SessionEnd(BaseModel):
    end_wall_time_utc_ms: int
    completion_status: str


class Measurement(BaseModel):
    session_id: int
    sequence: int
    wall_time_utc_ms: int
    elapsed_ns: int
    pid: str
    canonical_name: str
    value_numeric: Optional[float] = None
    value_text: Optional[str] = None
    unit: str
    latency_ms: int
    quality_flag: str
    raw_response: Optional[str] = None


class Location(BaseModel):
    session_id: int
    elapsed_ns: int
    wall_time_utc_ms: int
    latitude: float
    longitude: float
    altitude_m: Optional[float] = None
    speed_mps: Optional[float] = None
    bearing_deg: Optional[float] = None
    horizontal_accuracy_m: Optional[float] = None
    provider: Optional[str] = None


class Event(BaseModel):
    session_id: int
    elapsed_ns: int
    wall_time_utc_ms: int
    event_type: str
    severity: str
    message: str


def _now_ms() -> int:
    import time

    return int(time.time() * 1000)


@app.post("/sessions/{session_id}/start", dependencies=[Depends(_require_auth)])
def start_session(session_id: int, body: SessionStart):
    with _db_lock:
        _conn.execute(
            """
            INSERT OR REPLACE INTO sessions
                (session_id, start_wall_time_utc_ms, vehicle_profile, adapter_name,
                 adapter_address, app_version, phone_model, notes, completion_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS')
            """,
            [session_id, body.start_wall_time_utc_ms, body.vehicle_profile, body.adapter_name,
             body.adapter_address, body.app_version, body.phone_model, body.notes],
        )
    return {"ok": True}


@app.post("/sessions/{session_id}/end", dependencies=[Depends(_require_auth)])
def end_session(session_id: int, body: SessionEnd):
    with _db_lock:
        _conn.execute(
            "UPDATE sessions SET end_wall_time_utc_ms = ?, completion_status = ? WHERE session_id = ?",
            [body.end_wall_time_utc_ms, body.completion_status, session_id],
        )
    return {"ok": True}


@app.post("/measurements", dependencies=[Depends(_require_auth)])
def post_measurement(m: Measurement):
    with _db_lock:
        _conn.execute(
            """
            INSERT INTO measurements VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [m.session_id, m.sequence, m.wall_time_utc_ms, m.elapsed_ns, m.pid, m.canonical_name,
             m.value_numeric, m.value_text, m.unit, m.latency_ms, m.quality_flag, _now_ms(), m.raw_response],
        )
    return {"ok": True}


@app.post("/locations", dependencies=[Depends(_require_auth)])
def post_location(loc: Location):
    with _db_lock:
        _conn.execute(
            "INSERT INTO locations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [loc.session_id, loc.elapsed_ns, loc.wall_time_utc_ms, loc.latitude, loc.longitude,
             loc.altitude_m, loc.speed_mps, loc.bearing_deg, loc.horizontal_accuracy_m, loc.provider, _now_ms()],
        )
    return {"ok": True}


@app.post("/events", dependencies=[Depends(_require_auth)])
def post_event(e: Event):
    with _db_lock:
        _conn.execute(
            "INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?)",
            [e.session_id, e.elapsed_ns, e.wall_time_utc_ms, e.event_type, e.severity, e.message, _now_ms()],
        )
    return {"ok": True}


@app.post("/sessions/{session_id}/measurements/bulk", dependencies=[Depends(_require_auth)])
def bulk_measurements(session_id: int, items: list[Measurement]):
    """Full-replace backfill, run once at Stop: local Room is authoritative, this makes the
    server's copy match it exactly regardless of what the live per-item stream missed during
    the drive. Delete-then-insert in one transaction rather than trying to dedup against
    whatever already streamed in, simpler and provably correct since this only runs after
    logging has already stopped (no concurrent live writes to race against)."""
    now = _now_ms()
    with _db_lock:
        _conn.execute("BEGIN TRANSACTION")
        try:
            _conn.execute("DELETE FROM measurements WHERE session_id = ?", [session_id])
            for m in items:
                _conn.execute(
                    "INSERT INTO measurements VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [m.session_id, m.sequence, m.wall_time_utc_ms, m.elapsed_ns, m.pid, m.canonical_name,
                     m.value_numeric, m.value_text, m.unit, m.latency_ms, m.quality_flag, now, m.raw_response],
                )
            _conn.execute("COMMIT")
        except Exception:
            _conn.execute("ROLLBACK")
            raise
    return {"ok": True, "count": len(items)}


@app.post("/sessions/{session_id}/locations/bulk", dependencies=[Depends(_require_auth)])
def bulk_locations(session_id: int, items: list[Location]):
    now = _now_ms()
    with _db_lock:
        _conn.execute("BEGIN TRANSACTION")
        try:
            _conn.execute("DELETE FROM locations WHERE session_id = ?", [session_id])
            for loc in items:
                _conn.execute(
                    "INSERT INTO locations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [loc.session_id, loc.elapsed_ns, loc.wall_time_utc_ms, loc.latitude, loc.longitude,
                     loc.altitude_m, loc.speed_mps, loc.bearing_deg, loc.horizontal_accuracy_m, loc.provider, now],
                )
            _conn.execute("COMMIT")
        except Exception:
            _conn.execute("ROLLBACK")
            raise
    return {"ok": True, "count": len(items)}


@app.post("/sessions/{session_id}/events/bulk", dependencies=[Depends(_require_auth)])
def bulk_events(session_id: int, items: list[Event]):
    now = _now_ms()
    with _db_lock:
        _conn.execute("BEGIN TRANSACTION")
        try:
            _conn.execute("DELETE FROM events WHERE session_id = ?", [session_id])
            for e in items:
                _conn.execute(
                    "INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [e.session_id, e.elapsed_ns, e.wall_time_utc_ms, e.event_type, e.severity, e.message, now],
                )
            _conn.execute("COMMIT")
        except Exception:
            _conn.execute("ROLLBACK")
            raise
    return {"ok": True, "count": len(items)}


@app.post("/sessions/{session_id}/analyze", dependencies=[Depends(_require_auth)])
def trigger_analysis(session_id: int):
    """Signal to run once the app's stop-time backfill has succeeded: local data is now
    guaranteed complete in DuckDB, safe to analyze. Runs in a background thread so this
    returns immediately; poll /sessions/{id}/analysis for the result."""
    analysis_worker.request_analysis(session_id, _conn, _db_lock)
    return {"ok": True, "status": "running"}


@app.get("/sessions/{session_id}/analysis", dependencies=[Depends(_require_auth)])
def get_analysis(session_id: int):
    return analysis_worker.get_status(session_id)


@app.get("/health")
def health():
    with _db_lock:
        count = _conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    return {"status": "ok", "sessions": count, "db_path": str(DB_PATH)}
