#!/usr/bin/env python3
"""
DriveTrace analysis script (blueprint Milestone 7).

Loads a DriveTrace export bundle (the .zip produced by the app's "Export CSV"
button, or an already-unzipped folder containing samples_long.csv,
locations.csv, events.csv, metadata.json) and produces:

  - a PID coverage / latency report
  - combined fuel trim (STFT+LTFT) over time
  - RPM / speed / load / MAF over time
  - estimated fuel rate and instantaneous MPG (clearly marked as an estimate)
  - a warm-up curve (coolant temp vs time)
  - idle fraction, trip distance (GPS vs OBD), and a matched cruise-window table
  - a highway/backroad/city phase breakdown
  - a GPS route + speed/elevation profile
  - plain-language, deliberately cautious anomaly flags (not a diagnosis)

Raw values from samples_long.csv are authoritative; everything here is a
derived view recomputed from them, matching the blueprint's "keep raw values
authoritative" rule.

Usage:
    python analyze_drive.py path/to/drivetrace_..._session-N.zip
    python analyze_drive.py path/to/unzipped_export_folder/
    python analyze_drive.py <bundle> --out path/to/output_dir

Requires: pandas, numpy, matplotlib (see ../.venv)
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless-safe: no display server needed to save PNGs
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# kotlin-obd-api's canonical_name strings are human-readable (e.g. "Short Term
# Fuel Trim Bank 1"), not yet verified against a real captured drive. Matching
# on keywords rather than an exact string is deliberately more forgiving than
# an exact lookup table, so the script degrades to "column missing" instead of
# silently mismatching if the real strings differ slightly.
PID_KEYWORDS: dict[str, list[str]] = {
    "rpm": [r"\brpm\b"],
    "speed_kmh": [r"vehicle speed", r"\bspeed\b"],
    "load_pct": [r"engine load", r"calculated load"],
    "maf_gs": [r"mass air flow", r"\bmaf\b"],
    "stft1_pct": [r"short term.*bank 1"],
    "ltft1_pct": [r"long term.*bank 1"],
    "ce_ratio": [r"equivalence ratio"],
    "voltage": [r"module voltage"],
    "coolant_c": [r"coolant"],
    "iat_c": [r"intake air temp", r"air intake temp"],
    "throttle_pct": [r"throttle position"],
    "map_kpa": [r"intake manifold", r"manifold pressure"],
    "fuel_rail_kpa": [r"fuel rail pressure"],
    "fuel_rate_lph": [r"fuel consumption rate", r"fuel rate"],
    "baro_kpa": [r"barometric"],
    "ambient_c": [r"ambient air temp"],
    "runtime_s": [r"run ?time"],
    "distance_since_clear_km": [r"distance.*codes cleared"],
    "egr_cmd_pct": [r"commanded egr"],
    "egr_error_pct": [r"egr error"],
    "fuel_level_pct": [r"fuel level"],
}

STOICH_AFR_GASOLINE = 14.7  # air:fuel mass ratio at lambda = 1
GASOLINE_DENSITY_G_PER_L = 745.0  # mid-range for pump gasoline; varies ~720-775
LITERS_PER_GALLON = 3.78541
IDLE_SPEED_THRESHOLD_KMH = 2.0
IDLE_RPM_THRESHOLD = 300
LOW_SPEED_MPG_SUPPRESS_KMH = 8.0  # below this, instantaneous MPG is too noisy to trust
CRUISE_WINDOW_S = 10
CRUISE_SPEED_STD_KMH = 3.0
CRUISE_MIN_SPEED_KMH = 15.0
PHASE_BINS_KMH = [(0, 30, "city"), (30, 90, "backroad"), (90, 999, "highway")]
GRID_INTERVAL_S = 1.0


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------


@dataclass
class DriveData:
    metadata: dict
    samples: pd.DataFrame
    locations: pd.DataFrame
    events: pd.DataFrame


def load_bundle(path: Path) -> DriveData:
    if path.is_file() and path.suffix == ".zip":
        tmp_dir = Path(tempfile.mkdtemp(prefix="drivetrace_"))
        with zipfile.ZipFile(path) as zf:
            zf.extractall(tmp_dir)
        folder = tmp_dir
    elif path.is_dir():
        folder = path
    else:
        raise FileNotFoundError(f"{path} is neither a .zip bundle nor a folder")

    metadata = json.loads((folder / "metadata.json").read_text())
    samples = pd.read_csv(folder / "samples_long.csv")
    locations = pd.read_csv(folder / "locations.csv")
    events_path = folder / "events.csv"
    events = pd.read_csv(events_path) if events_path.exists() else pd.DataFrame()

    samples["elapsed_s"] = samples["elapsed_ns"] / 1e9
    if not locations.empty:
        locations["elapsed_s"] = locations["elapsed_ns"] / 1e9
    if not events.empty:
        events["elapsed_s"] = events["elapsed_ns"] / 1e9

    return DriveData(metadata=metadata, samples=samples, locations=locations, events=events)


def match_pid(canonical_names: pd.Series, patterns: list[str]) -> pd.Series:
    combined = "|".join(patterns)
    return canonical_names.str.contains(combined, case=False, regex=True, na=False)


# ---------------------------------------------------------------------------
# Building the analysis-ready snapshot
# ---------------------------------------------------------------------------


def build_snapshot(data: DriveData) -> tuple[pd.DataFrame, dict[str, pd.DataFrame]]:
    """Returns (1-second wide snapshot, {key: raw per-PID series}) via as-of joins.

    This mirrors the blueprint's snapshot_wide_1s.csv idea: nearest-prior-value
    per PID at each second, with an age_s_<key> column so it's clear how stale
    a value was when it got carried forward.
    """
    samples = data.samples
    duration_s = float(samples["elapsed_s"].max()) if not samples.empty else 0.0
    grid = pd.DataFrame({"elapsed_s": np.arange(0, duration_s + GRID_INTERVAL_S, GRID_INTERVAL_S)})

    per_pid: dict[str, pd.DataFrame] = {}
    snapshot = grid.copy()

    for key, patterns in PID_KEYWORDS.items():
        mask = match_pid(samples["canonical_name"].astype(str), patterns)
        series = samples.loc[mask & samples["value_numeric"].notna(), ["elapsed_s", "value_numeric"]]
        series = series.sort_values("elapsed_s").rename(columns={"value_numeric": key})
        per_pid[key] = series
        if series.empty:
            snapshot[key] = np.nan
            snapshot[f"age_s_{key}"] = np.nan
            continue
        joined = pd.merge_asof(grid, series, on="elapsed_s", direction="backward")
        # age = how long ago (in seconds) the carried-forward value was actually sampled
        last_seen = pd.merge_asof(
            grid, series.assign(_seen_at=series["elapsed_s"]), on="elapsed_s", direction="backward"
        )["_seen_at"]
        snapshot[key] = joined[key]
        snapshot[f"age_s_{key}"] = (grid["elapsed_s"] - last_seen).values

    if not data.locations.empty:
        loc = data.locations.sort_values("elapsed_s")
        loc_joined = pd.merge_asof(
            grid, loc[["elapsed_s", "latitude", "longitude", "altitude_m", "speed_mps"]], on="elapsed_s", direction="nearest"
        )
        snapshot["latitude"] = loc_joined["latitude"]
        snapshot["longitude"] = loc_joined["longitude"]
        snapshot["altitude_m"] = loc_joined["altitude_m"]
        snapshot["gps_speed_kmh"] = loc_joined["speed_mps"] * 3.6

    return snapshot, per_pid


# ---------------------------------------------------------------------------
# Derived values (blueprint section 7)
# ---------------------------------------------------------------------------


def add_derived_columns(snap: pd.DataFrame) -> pd.DataFrame:
    snap = snap.copy()

    if "stft1_pct" in snap and "ltft1_pct" in snap:
        snap["combined_trim_pct"] = snap["stft1_pct"] + snap["ltft1_pct"]

    # MAF-estimated fuel consumption: only trustworthy near stoichiometric operation.
    if "maf_gs" in snap:
        near_stoich = snap["ce_ratio"].between(0.9, 1.1) if "ce_ratio" in snap else pd.Series(True, index=snap.index)
        fuel_g_s = snap["maf_gs"] / STOICH_AFR_GASOLINE
        fuel_gal_hr = (fuel_g_s * 3600) / (GASOLINE_DENSITY_G_PER_L * LITERS_PER_GALLON)
        snap["est_fuel_gal_hr"] = fuel_gal_hr.where(near_stoich)

        speed_kmh = snap.get("speed_kmh", snap.get("gps_speed_kmh"))
        if speed_kmh is not None:
            speed_mph = speed_kmh * 0.621371
            with np.errstate(divide="ignore", invalid="ignore"):
                mpg = speed_mph / snap["est_fuel_gal_hr"]
            snap["est_instant_mpg"] = mpg.where(
                (speed_kmh >= LOW_SPEED_MPG_SUPPRESS_KMH) & (snap["est_fuel_gal_hr"] > 0)
            )

    return snap


def compute_idle_fraction(snap: pd.DataFrame) -> float | None:
    if "speed_kmh" not in snap or "rpm" not in snap:
        return None
    valid = snap["speed_kmh"].notna() & snap["rpm"].notna()
    if valid.sum() == 0:
        return None
    idle = valid & (snap["speed_kmh"] < IDLE_SPEED_THRESHOLD_KMH) & (snap["rpm"] > IDLE_RPM_THRESHOLD)
    return float(idle.sum() / valid.sum())


def compute_warmup_duration_s(snap: pd.DataFrame) -> float | None:
    if "coolant_c" not in snap or snap["coolant_c"].dropna().empty:
        return None
    coolant = snap["coolant_c"].dropna()
    plateau = coolant.max() - 3.0  # proxy for "reached operating temp": within 3C of session max
    reached = snap.loc[snap["coolant_c"] >= plateau, "elapsed_s"]
    return float(reached.iloc[0]) if not reached.empty else None


def compute_trip_distance(snap: pd.DataFrame) -> dict[str, float | None]:
    result: dict[str, float | None] = {"gps_km": None, "obd_km": None}
    dt = GRID_INTERVAL_S
    if "gps_speed_kmh" in snap:
        result["gps_km"] = float(np.nansum(snap["gps_speed_kmh"].fillna(0)) * dt / 3600)
    if "speed_kmh" in snap:
        result["obd_km"] = float(np.nansum(snap["speed_kmh"].fillna(0)) * dt / 3600)
    return result


def find_cruise_windows(snap: pd.DataFrame, warmup_end_s: float | None) -> pd.DataFrame:
    """Stable-speed, low-acceleration, already-warmed segments: the best
    apples-to-apples comparison points across the drive."""
    if "speed_kmh" not in snap:
        return pd.DataFrame()

    window_pts = int(CRUISE_WINDOW_S / GRID_INTERVAL_S)
    rolling_std = snap["speed_kmh"].rolling(window_pts, center=True).std()
    is_cruise = (
        (rolling_std < CRUISE_SPEED_STD_KMH)
        & (snap["speed_kmh"] > CRUISE_MIN_SPEED_KMH)
        & (snap["elapsed_s"] >= (warmup_end_s or 0))
    )

    windows = []
    in_window = False
    start_idx = None
    for i, flag in enumerate(is_cruise.fillna(False)):
        if flag and not in_window:
            in_window = True
            start_idx = i
        elif not flag and in_window:
            in_window = False
            windows.append((start_idx, i - 1))
    if in_window:
        windows.append((start_idx, len(is_cruise) - 1))

    rows = []
    for start, end in windows:
        if snap["elapsed_s"].iloc[end] - snap["elapsed_s"].iloc[start] < CRUISE_WINDOW_S:
            continue
        seg = snap.iloc[start : end + 1]
        rows.append(
            {
                "start_s": seg["elapsed_s"].iloc[0],
                "end_s": seg["elapsed_s"].iloc[-1],
                "duration_s": seg["elapsed_s"].iloc[-1] - seg["elapsed_s"].iloc[0],
                "mean_speed_kmh": seg["speed_kmh"].mean(),
                "mean_load_pct": seg["load_pct"].mean() if "load_pct" in seg else np.nan,
                "mean_maf_gs": seg["maf_gs"].mean() if "maf_gs" in seg else np.nan,
                "mean_rpm": seg["rpm"].mean() if "rpm" in seg else np.nan,
                "mean_combined_trim_pct": seg["combined_trim_pct"].mean() if "combined_trim_pct" in seg else np.nan,
            }
        )
    return pd.DataFrame(rows)


def phase_breakdown(snap: pd.DataFrame) -> pd.DataFrame:
    if "speed_kmh" not in snap:
        return pd.DataFrame()

    def label(speed: float) -> str | None:
        if pd.isna(speed):
            return None
        for lo, hi, name in PHASE_BINS_KMH:
            if lo <= speed < hi:
                return name
        return None

    phased = snap.copy()
    phased["phase"] = phased["speed_kmh"].apply(label)
    agg_cols = [c for c in ["load_pct", "maf_gs", "combined_trim_pct", "rpm"] if c in phased.columns]
    if not agg_cols:
        return pd.DataFrame()
    return phased.dropna(subset=["phase"]).groupby("phase")[agg_cols].mean().round(2)


# ---------------------------------------------------------------------------
# PID coverage / latency report
# ---------------------------------------------------------------------------


def pid_coverage_report(samples: pd.DataFrame) -> pd.DataFrame:
    grouped = samples.groupby("canonical_name").agg(
        count=("sequence", "count"),
        first_seen_s=("elapsed_s", "min"),
        last_seen_s=("elapsed_s", "max"),
        median_latency_ms=("latency_ms", "median"),
        p95_latency_ms=("latency_ms", lambda s: s.quantile(0.95)),
    )
    return grouped.sort_values("count", ascending=False).round(1)


# ---------------------------------------------------------------------------
# Anomaly flags (deliberately cautious; see blueprint section 15)
# ---------------------------------------------------------------------------


def anomaly_flags(snap: pd.DataFrame, cruise: pd.DataFrame, events: pd.DataFrame) -> list[str]:
    flags: list[str] = []

    cruise_trim = cruise["mean_combined_trim_pct"].mean() if not cruise.empty else None
    if cruise_trim is not None and not pd.isna(cruise_trim):
        if cruise_trim > 10:
            flags.append(
                f"Combined fuel trim during cruise averages +{cruise_trim:.1f}%. Consistent with unmetered "
                "air, MAF under-reporting, a fuel-delivery shortfall, or an exhaust leak ahead of the "
                "sensor. Not a diagnosis on its own; worth cross-checking against the DTC/monitor read "
                "captured at session start."
            )
        elif cruise_trim < -10:
            flags.append(
                f"Combined fuel trim during cruise averages {cruise_trim:.1f}%. Consistent with excessive "
                "fuel delivery, a stuck purge/injector, MAF over-reporting, or O2 sensor bias. Worth a "
                "repeat drive to confirm before acting on it."
            )

    if "voltage" in snap:
        running_voltage = snap.loc[snap.get("rpm", pd.Series(dtype=float)) > IDLE_RPM_THRESHOLD, "voltage"]
        if not running_voltage.dropna().empty:
            mean_v = running_voltage.mean()
            if mean_v < 13.0:
                flags.append(
                    f"Control module voltage while running averages {mean_v:.1f}V, on the low side for a "
                    "charging alternator. Worth a battery/charging-system check, not conclusive from one drive."
                )
            elif mean_v > 15.5:
                flags.append(
                    f"Control module voltage while running averages {mean_v:.1f}V, on the high side. "
                    "Worth a charging-system check."
                )

    warmup_s = compute_warmup_duration_s(snap)
    if warmup_s is not None and warmup_s > 600:
        flags.append(
            f"Coolant took about {warmup_s / 60:.1f} minutes to reach a stable plateau. If that's longer "
            "than this car normally takes, it's consistent with a thermostat stuck open or a coolant "
            "temperature sensor issue."
        )

    if not events.empty:
        reconnects = events[events["event_type"] == "RECONNECT"]
        if not reconnects.empty:
            flags.append(
                f"{len(reconnects)} Bluetooth reconnect(s) during the session. Check for gaps around those "
                "times before trusting trends that span a reconnect."
            )
        dropped = events[events["event_type"] == "PID_UNSUPPORTED"]
        if not dropped.empty:
            names = ", ".join(dropped["message"].astype(str).str.split(" dropped").str[0].unique())
            flags.append(f"These PIDs were dropped as unsupported by this ECU/adapter combo: {names}.")

    if not flags:
        flags.append("No threshold-based flags triggered. That does not rule out a subtler issue; review the plots.")

    return flags


# ---------------------------------------------------------------------------
# Plots
# ---------------------------------------------------------------------------


def make_plots(snap: pd.DataFrame, out_dir: Path) -> None:
    t = snap["elapsed_s"] / 60  # minutes, easier to read on axes

    if {"stft1_pct", "ltft1_pct"}.issubset(snap.columns):
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.plot(t, snap["stft1_pct"], label="STFT Bank 1", alpha=0.8)
        ax.plot(t, snap["ltft1_pct"], label="LTFT Bank 1", alpha=0.8)
        if "combined_trim_pct" in snap:
            ax.plot(t, snap["combined_trim_pct"], label="Combined", linewidth=2, color="black")
        ax.axhline(0, color="gray", linewidth=0.8)
        ax.set_xlabel("Minutes")
        ax.set_ylabel("Fuel trim (%)")
        ax.set_title("Fuel trim over time")
        ax.legend()
        fig.tight_layout()
        fig.savefig(out_dir / "fuel_trim.png", dpi=150)
        plt.close(fig)

    engine_cols = [c for c in ["rpm", "speed_kmh", "load_pct", "maf_gs"] if c in snap.columns]
    if engine_cols:
        fig, axes = plt.subplots(len(engine_cols), 1, figsize=(10, 2.2 * len(engine_cols)), sharex=True)
        axes = np.atleast_1d(axes)
        for ax, col in zip(axes, engine_cols):
            ax.plot(t, snap[col])
            ax.set_ylabel(col)
        axes[-1].set_xlabel("Minutes")
        fig.suptitle("Engine state over time")
        fig.tight_layout()
        fig.savefig(out_dir / "engine_state.png", dpi=150)
        plt.close(fig)

    if "est_instant_mpg" in snap:
        fig, ax1 = plt.subplots(figsize=(10, 4))
        ax1.plot(t, snap["est_instant_mpg"], color="tab:green")
        ax1.set_ylabel("Estimated instantaneous MPG")
        ax1.set_xlabel("Minutes")
        ax1.set_title("Estimated MPG (MAF-derived; suppressed at low speed and outside stoichiometric ops)")
        fig.tight_layout()
        fig.savefig(out_dir / "estimated_mpg.png", dpi=150)
        plt.close(fig)

    if "coolant_c" in snap:
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.plot(t, snap["coolant_c"])
        warmup_s = compute_warmup_duration_s(snap)
        if warmup_s is not None:
            ax.axvline(warmup_s / 60, color="red", linestyle="--", label="Warm-up complete (est.)")
            ax.legend()
        ax.set_xlabel("Minutes")
        ax.set_ylabel("Coolant temp (C)")
        ax.set_title("Warm-up curve")
        fig.tight_layout()
        fig.savefig(out_dir / "warmup.png", dpi=150)
        plt.close(fig)

    if {"latitude", "longitude"}.issubset(snap.columns) and snap["latitude"].notna().any():
        fig, axes = plt.subplots(1, 2, figsize=(12, 5))
        sc = axes[0].scatter(
            snap["longitude"], snap["latitude"], c=snap.get("gps_speed_kmh", snap.get("speed_kmh")), s=4, cmap="viridis"
        )
        axes[0].set_title("Route (colored by speed)")
        axes[0].set_xlabel("Longitude")
        axes[0].set_ylabel("Latitude")
        fig.colorbar(sc, ax=axes[0], label="km/h")
        if "altitude_m" in snap:
            axes[1].plot(t, snap["altitude_m"])
            axes[1].set_title("Elevation profile")
            axes[1].set_xlabel("Minutes")
            axes[1].set_ylabel("Altitude (m)")
        fig.tight_layout()
        fig.savefig(out_dir / "route.png", dpi=150)
        plt.close(fig)


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def write_report(
    out_dir: Path,
    data: DriveData,
    snap: pd.DataFrame,
    coverage: pd.DataFrame,
    idle_fraction: float | None,
    warmup_s: float | None,
    distance: dict,
    cruise: pd.DataFrame,
    phases: pd.DataFrame,
    flags: list[str],
) -> None:
    meta = data.metadata
    lines = [
        "# DriveTrace analysis report",
        "",
        f"Session {meta.get('sessionId')}  |  {meta.get('startWallTimeUtc')} -> {meta.get('endWallTimeUtc')}",
        f"Vehicle: {meta.get('vehicleProfile')}  |  Protocol: {meta.get('protocol')}  |  "
        f"Adapter: {meta.get('adapterName') or meta.get('adapterAddress')}",
        f"Measurements: {meta.get('measurementCount')}  |  GPS fixes: {meta.get('locationCount')}",
        "",
        "## Summary",
        f"- Idle fraction: {idle_fraction * 100:.1f}%" if idle_fraction is not None else "- Idle fraction: n/a",
        f"- Warm-up duration (est.): {warmup_s / 60:.1f} min" if warmup_s is not None else "- Warm-up duration: n/a",
        f"- Trip distance, GPS: {distance.get('gps_km'):.2f} km" if distance.get("gps_km") is not None else "- Trip distance, GPS: n/a",
        f"- Trip distance, OBD speed: {distance.get('obd_km'):.2f} km" if distance.get("obd_km") is not None else "- Trip distance, OBD: n/a",
        "",
        "## Anomaly flags (cautious; not a diagnosis)",
    ]
    lines += [f"- {f}" for f in flags]

    lines += ["", "## Phase breakdown (mean by speed band)", ""]
    lines.append(phases.to_markdown() if not phases.empty else "_no phase data_")

    lines += ["", "## Matched cruise windows", ""]
    if not cruise.empty:
        lines.append(cruise.round(2).to_markdown(index=False))
    else:
        lines.append("_no stable cruise windows found_")

    lines += ["", "## PID coverage / latency", ""]
    lines.append(coverage.to_markdown() if not coverage.empty else "_no samples_")

    (out_dir / "analysis_report.md").write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=Path, help="Path to the export .zip or an unzipped export folder")
    parser.add_argument("--out", type=Path, default=None, help="Output directory (default: ./output/<session-id>/)")
    args = parser.parse_args()

    data = load_bundle(args.bundle)
    snap, _per_pid = build_snapshot(data)
    snap = add_derived_columns(snap)

    idle_fraction = compute_idle_fraction(snap)
    warmup_s = compute_warmup_duration_s(snap)
    distance = compute_trip_distance(snap)
    cruise = find_cruise_windows(snap, warmup_s)
    phases = phase_breakdown(snap)
    coverage = pid_coverage_report(data.samples)
    flags = anomaly_flags(snap, cruise, data.events)

    out_dir = args.out or Path(__file__).resolve().parent.parent / "output" / str(data.metadata.get("sessionId", "session"))
    out_dir.mkdir(parents=True, exist_ok=True)

    snap.to_csv(out_dir / "snapshot_1s.csv", index=False)
    make_plots(snap, out_dir)
    write_report(out_dir, data, snap, coverage, idle_fraction, warmup_s, distance, cruise, phases, flags)

    print(f"Wrote analysis to {out_dir}")
    for f in flags:
        print(f"- {f}")


if __name__ == "__main__":
    main()
