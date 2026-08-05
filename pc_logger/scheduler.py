"""
Tiered PID scheduler: mirrors PidScheduler.kt in the Android app. Tier A
polls continuously, Tier B/C are time-gated so slow PIDs never starve the
fast ones. A PID that fails repeatedly goes into cooldown rather than being
permanently dropped: confirmed on a real drive that a PID (LTFT) can fail
twice right after connecting (adapter still settling) and then never get
retried for the rest of the session under a permanent-drop policy, despite
genuinely being supported by the vehicle.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

from . import elm
from .pids import PidDef, TIER_A, TIER_B, TIER_C
from .transport import ObdTimeoutError, SerialTransport

TIER_B_INTERVAL_S = 3.0
TIER_C_INTERVAL_S = 20.0
MAX_CONSECUTIVE_FAILURES = 2
COOLDOWN_S = 30.0


@dataclass
class Measurement:
    sequence: int
    wall_time_utc_ms: int
    elapsed_ns: int
    pid: str
    canonical_name: str
    value_numeric: float | None
    value_text: str | None
    unit: str
    latency_ms: int
    quality_flag: str


@dataclass
class SchedulerEvent:
    elapsed_ns: int
    event_type: str
    severity: str
    message: str


@dataclass
class _Rotating:
    pid_def: PidDef
    consecutive_failures: int = 0
    cooldown_until_ns: int = 0

    def is_available(self, now_ns: int) -> bool:
        return now_ns >= self.cooldown_until_ns


class PidScheduler:
    def __init__(
        self,
        transport: SerialTransport,
        start_monotonic_ns: int,
        on_measurement: Callable[[Measurement], None],
        on_event: Callable[[SchedulerEvent], None],
        sequence_start: int = 0,
    ):
        self.transport = transport
        self.start_monotonic_ns = start_monotonic_ns
        self.on_measurement = on_measurement
        self.on_event = on_event
        self._sequence = sequence_start

        self.tier_a = [_Rotating(p) for p in TIER_A]
        self.tier_b = [_Rotating(p) for p in TIER_B]
        self.tier_c = [_Rotating(p) for p in TIER_C]
        self._tier_a_idx = 0
        self._tier_b_idx = 0
        self._tier_c_idx = 0
        self._last_tier_b_ns = 0
        self._last_tier_c_ns = 0

    @property
    def sequence(self) -> int:
        return self._sequence

    def _elapsed_ns(self) -> int:
        return time.monotonic_ns() - self.start_monotonic_ns

    def run_one_time_reads(self) -> dict[str, str]:
        results: dict[str, str] = {}
        vin = elm.read_vin(self.transport)
        if vin:
            results["VIN"] = vin
        for mode, label in [("03", "CURRENT_DTCS"), ("07", "PENDING_DTCS"), ("0A", "PERMANENT_DTCS")]:
            codes = elm.read_dtcs(self.transport, mode)
            results[label] = ",".join(codes) if codes else "(none)"
        return results

    def run(self, should_continue: Callable[[], bool]) -> None:
        while should_continue():
            self._poll_next(self.tier_a, "_tier_a_idx")

            now = self._elapsed_ns()
            if now - self._last_tier_b_ns >= TIER_B_INTERVAL_S * 1e9 and any(r.is_available(now) for r in self.tier_b):
                self._poll_next(self.tier_b, "_tier_b_idx")
                self._last_tier_b_ns = now
            if now - self._last_tier_c_ns >= TIER_C_INTERVAL_S * 1e9 and any(r.is_available(now) for r in self.tier_c):
                self._poll_next(self.tier_c, "_tier_c_idx")
                self._last_tier_c_ns = now

    def _poll_next(self, tier: list[_Rotating], idx_attr: str) -> None:
        now = self._elapsed_ns()
        if not tier or not any(r.is_available(now) for r in tier):
            return
        idx = getattr(self, idx_attr) % len(tier)
        attempts = 0
        while not tier[idx].is_available(now) and attempts < len(tier):
            idx = (idx + 1) % len(tier)
            attempts += 1
        self._poll_one(tier[idx])
        setattr(self, idx_attr, (idx + 1) % len(tier))

    def _poll_one(self, entry: _Rotating) -> None:
        elapsed_ns = self._elapsed_ns()
        wall_ms = int(time.time() * 1000)
        self._sequence += 1
        try:
            value, latency_ms = elm.query_pid(self.transport, entry.pid_def)
            entry.consecutive_failures = 0
            self.on_measurement(
                Measurement(
                    sequence=self._sequence,
                    wall_time_utc_ms=wall_ms,
                    elapsed_ns=elapsed_ns,
                    pid=entry.pid_def.pid,
                    canonical_name=entry.pid_def.name,
                    value_numeric=value,
                    value_text=None,
                    unit=entry.pid_def.unit,
                    latency_ms=latency_ms,
                    quality_flag="OK",
                )
            )
        except (elm.NoDataError, elm.AdapterError, ObdTimeoutError) as e:
            entry.consecutive_failures += 1
            if entry.consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                entry.cooldown_until_ns = elapsed_ns + int(COOLDOWN_S * 1e9)
                entry.consecutive_failures = 0
                self.on_event(
                    SchedulerEvent(
                        elapsed_ns=elapsed_ns,
                        event_type="PID_COOLDOWN",
                        severity="INFO",
                        message=f"{entry.pid_def.name} paused {COOLDOWN_S:.0f}s after repeated {e.__class__.__name__}, will retry",
                    )
                )
