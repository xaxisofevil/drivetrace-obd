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
    # Negative lookbehind excludes "Target Engine RPM" (a new Subaru enhanced PID, see
    # target_rpm below): the bare \brpm\b pattern would otherwise match it too as a substring,
    # confirmed directly with the same regex before fixing, silently mixing the ECU's target
    # into the actual-RPM column. Same class of bug as map_kpa's "desired" exclusion below.
    "rpm": [r"(?<!target engine )\brpm\b"],
    "speed_kmh": [r"vehicle speed", r"\bspeed\b"],
    "load_pct": [r"engine load", r"calculated load"],
    "maf_gs": [r"mass air flow", r"\bmaf\b"],
    "stft1_pct": [r"short term.*bank 1"],
    "ltft1_pct": [r"long term.*bank 1"],
    # Bank 2: unused by the Mazda catalog (no Bank 2 on that engine), populated on vehicles with
    # a real second bank (e.g. SubaruPidCatalog's boxer engine).
    "stft2_pct": [r"short term.*bank 2"],
    "ltft2_pct": [r"long term.*bank 2"],
    "ce_ratio": [r"equivalence ratio"],
    "voltage": [r"module voltage", r"power supply"],  # kotlin-obd-api actually names PID 42 "Control Module Power Supply"
    "coolant_c": [r"coolant"],
    "iat_c": [r"intake air temp", r"air intake temp"],
    "throttle_pct": [r"throttle position"],
    # Negative lookahead excludes "Intake Manifold Pressure Desired" (the new enhanced PID,
    # see map_desired_kpa below): both this pattern and "manifold pressure" alone would
    # otherwise match that longer name too as a substring, confirmed directly before fixing,
    # silently mixing target-boost data into the actual-MAP column.
    "map_kpa": [r"intake manifold pressure(?! desired)"],
    "fuel_rail_kpa": [r"fuel rail pressure"],
    "fuel_rate_lph": [r"fuel consumption rate", r"fuel rate"],
    "baro_kpa": [r"barometric"],
    "ambient_c": [r"ambient air temp"],
    "runtime_s": [r"run ?time"],
    "distance_since_clear_km": [r"distance.*codes cleared"],
    "egr_cmd_pct": [r"commanded egr"],
    "egr_error_pct": [r"egr error"],
    "fuel_level_pct": [r"fuel level"],
    "catalyst_temp_c": [r"catalyst temp"],
    "oil_temp_c": [r"oil temp"],
    # Community-sourced Mode 22 parameters, Mazda-only, see MazdaEnhancedCommands.kt.
    "map_desired_kpa": [r"intake manifold pressure desired"],
    "turbo_a_inlet_kpa": [r"turbocharger a compressor inlet pressure"],
    "turbo_b_inlet_kpa": [r"turbocharger b compressor inlet pressure"],
    "knock_retard_deg": [r"knock retard"],
    "knock_control_pct": [r"knock control system"],
    # Community-sourced Mode 22 parameters, Subaru-only, see SubaruEnhancedCommands.kt.
    # injector_pw_ms is the one worth watching closest: the first non-MAF-derived fuel signal
    # this project has ever captured, see add_derived_columns for why nothing downstream
    # converts it to a fuel-mass number yet.
    "injector_pw_ms": [r"fuel injector pulse width"],
    "learned_ignition_timing_deg": [r"learned ignition timing"],
    "avcs_right_deg": [r"intake vvt advance angle right"],
    "avcs_left_deg": [r"intake vvt advance angle left"],
    "alternator_duty_pct": [r"alternator duty"],
    "battery_current_a": [r"battery current"],
    "battery_temp_c": [r"battery temp"],
    "alternator_mode": [r"alternator control mode"],
    "target_rpm": [r"target engine rpm"],
}

STOICH_AFR_GASOLINE = 14.7  # air:fuel mass ratio at lambda = 1
GASOLINE_DENSITY_G_PER_L = 745.0  # mid-range for pump gasoline; varies ~720-775
LITERS_PER_GALLON = 3.78541
IDLE_SPEED_THRESHOLD_KMH = 2.0
IDLE_RPM_THRESHOLD = 300
LOW_SPEED_MPG_SUPPRESS_KMH = 8.0  # below this, instantaneous MPG is too noisy to trust
ROLLING_MPG_WINDOW_S = 15  # see compute_rolling_mpg: sums distance/fuel over this window, not a
# point ratio, avoids the near-zero-fuel-flow spike problem instantaneous MPG has
ROLLING_MPG_MIN_FUEL_GAL = 0.0005  # suppress the ratio where too little fuel accumulated to trust it
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
    """Loads an export bundle. locations.csv is optional: the PC logger has
    no GPS of its own (see load_gpslogger_csv), so a bundle may legitimately
    have none. elapsed_s is NOT computed here; see finalize_timing, which
    needs to see every loaded source before it can pick a shared t0.
    """
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
    locations_path = folder / "locations.csv"
    locations = pd.read_csv(locations_path) if locations_path.exists() else pd.DataFrame()
    events_path = folder / "events.csv"
    events = pd.read_csv(events_path) if events_path.exists() else pd.DataFrame()

    return DriveData(metadata=metadata, samples=samples, locations=locations, events=events)


# GPSLogger's real CSV columns, confirmed from its CSVFileLogger.java source
# (not guessed): time, lat, lon, elevation, accuracy, bearing, speed,
# satellites, provider, hdop, vdop, pdop, geoidheight, ageofdgpsdata, dgpsid,
# activity, battery, annotation, timestamp_ms, time_offset, distance,
# starttimestamp_ms, profile_name, battery_charging. speed is in m/s
# (Android Location.getSpeed()), matching this project's internal schema.
def load_gpslogger_csv(path: Path) -> pd.DataFrame:
    """Loads a raw GPSLogger export (phone-side GPS, separate device/clock
    from the PC OBD logger) and maps it onto this project's locations schema."""
    raw = pd.read_csv(path)
    return pd.DataFrame(
        {
            "wall_time_utc_ms": raw["timestamp_ms"],
            "latitude": raw["lat"],
            "longitude": raw["lon"],
            "altitude_m": raw.get("elevation"),
            "speed_mps": raw.get("speed"),
            "bearing_deg": raw.get("bearing"),
            "horizontal_accuracy_m": raw.get("accuracy"),
            "provider": raw.get("provider"),
        }
    )


def finalize_timing(data: DriveData, external_locations: pd.DataFrame | None = None) -> DriveData:
    """Assigns elapsed_s to every source, anchored to a shared wall-clock t0
    (the earliest wall_time_utc_ms across whatever's loaded). This works for
    both the single-device Android export (where elapsed_ns would also have
    worked) and the cross-device PC+phone case (where it's the only option,
    since the two devices don't share a monotonic clock)."""
    locations = external_locations if external_locations is not None else data.locations

    timestamps = [data.samples["wall_time_utc_ms"]]
    if not locations.empty:
        timestamps.append(locations["wall_time_utc_ms"])
    t0_ms = min(s.min() for s in timestamps if not s.empty)

    data.samples["elapsed_s"] = (data.samples["wall_time_utc_ms"] - t0_ms) / 1000
    if not locations.empty:
        locations = locations.copy()
        locations["elapsed_s"] = (locations["wall_time_utc_ms"] - t0_ms) / 1000
    if not data.events.empty:
        data.events["elapsed_s"] = (data.events["wall_time_utc_ms"] - t0_ms) / 1000

    return DriveData(metadata=data.metadata, samples=data.samples, locations=locations, events=data.events)


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
    # Bank 2: only present on a real multi-bank vehicle (e.g. the Subaru's boxer engine, see
    # SubaruPidCatalog.kt), absent entirely for the single-bank Mazda. The gap between bank 1
    # and bank 2 combined trim is itself a diagnostic signal on a multi-bank engine, unequal
    # exhaust runner lengths are a known cause of legitimate (not faulty) bank-to-bank asymmetry.
    if "stft2_pct" in snap and "ltft2_pct" in snap:
        snap["combined_trim_bank2_pct"] = snap["stft2_pct"] + snap["ltft2_pct"]
        if "combined_trim_pct" in snap:
            snap["bank_trim_asymmetry_pct"] = snap["combined_trim_pct"] - snap["combined_trim_bank2_pct"]

    # Turbo boost: MAP reads absolute intake manifold pressure, Barometric reads ambient
    # atmospheric pressure, the difference is actual boost above atmospheric (negative = normal
    # off-boost manifold vacuum, positive = real boost). Both PIDs were already being collected
    # for other reasons, never combined before. Barometric is a slow Tier C PID (see
    # age_s_baro_kpa for staleness), acceptable here since barometric pressure itself moves on a
    # weather timescale, not a driving one, unlike everything else derived in this function.
    if "map_kpa" in snap and "baro_kpa" in snap:
        snap["boost_kpa"] = snap["map_kpa"] - snap["baro_kpa"]

    # Commanded/target boost, Mazda-only community-sourced enhanced PID (see
    # MazdaEnhancedCommands.kt): the one thing standard OBD-II has no PID for at all. Same
    # actual-minus-atmospheric logic as boost_kpa above, just against the ECU's target instead
    # of its measured value. A persistent gap between boost_kpa and boost_desired_kpa (actual
    # running well below target) is itself a real turbo-health signal: wastegate stuck open,
    # a boost leak, or a failing/lazy turbo all show up this way.
    if "map_desired_kpa" in snap and "baro_kpa" in snap:
        snap["boost_desired_kpa"] = snap["map_desired_kpa"] - snap["baro_kpa"]

    # Intake air temp above ambient: a bigger-than-expected gap during/after boosted driving
    # points at a heat-soaked or failing intercooler, or oil in the intake tract from a failing
    # turbo seal, beyond just normal compression heating under boost.
    if "iat_c" in snap and "ambient_c" in snap:
        snap["iat_above_ambient_c"] = snap["iat_c"] - snap["ambient_c"]

    # MAF-estimated fuel consumption. Commanded Equivalence Ratio (PID 44) is fuel/air relative
    # to stoich (SAE J1979): actual AFR = stoich AFR / ce_ratio, so
    # fuel_g_s = maf_gs / actual_AFR = maf_gs * ce_ratio / stoich AFR. This formula is valid
    # across ce_ratio's whole range, that's the entire reason to read ce_ratio at all rather than
    # just assuming stoichiometric combustion everywhere.
    #
    # CONFIRMED REAL BUG, fixed here: this used to additionally mask the result to samples where
    # ce_ratio sat within 0.9-1.1 ("near stoich"), on the reasoning that the estimate was "only
    # trustworthy" there, treating everything outside that band as zero fuel burned rather than
    # unknown. That's backwards: ce_ratio exists precisely to correct this formula for
    # non-stoichiometric operation (WOT enrichment, cold-start enrichment, deceleration lean-out),
    # so masking exactly there throws away the correction at the one moment it does real work,
    # and defaults those seconds to zero instead. Worse, those seconds are not idle or
    # low-load: confirmed on a real 66-minute Subaru drive that the masked samples' mean MAF
    # (38.6 g/s) was HIGHER than the kept samples' mean (22.4 g/s), i.e. this was silently
    # zeroing out the drive's highest-consumption moments (acceleration, merges, passes), not
    # noise. That drive's overall_mpg came out 38.7 against a driver-reported ADR of ~21;
    # removing the mask alone (naive stoich fill for the same samples, not even using their real
    # ce_ratio) already pulled the estimate down to ~29.3, and using their real ce_ratio (this
    # fix) pulls it further, since WOT enrichment means ce_ratio > 1.1 there, i.e. more fuel, not
    # the stoich-assumed amount.
    #
    # Why the two short Mazda drives that first surfaced this formula (see KNOWN_ISSUES.md, "MPG
    # estimate runs well above the vehicle's own trip computer") didn't expose the masking bug:
    # only 0.5-3.7% of their samples fell outside the 0.9-1.1 band, not enough volume to move
    # their MPG by more than a fraction. This Subaru drive had a much longer cold-start warmup
    # (23.8 min) and more highway acceleration, putting 15.5% of samples outside the band, and
    # concentrated in the highest-MAF moments, which is what made the same masking bug this
    # visible. Both vehicles get the same fix; the ranges upstream that pre-plausibility-clamp
    # ce_ratio itself (PidScheduler.kt's PLAUSIBLE_RANGES: 0.0-3.0) are what actually guards
    # against a garbage sensor read, no separate "trust window" is needed here on top of that.
    if "maf_gs" in snap:
        if "ce_ratio" in snap:
            fuel_g_s = (snap["maf_gs"] * snap["ce_ratio"]) / STOICH_AFR_GASOLINE
        else:
            fuel_g_s = snap["maf_gs"] / STOICH_AFR_GASOLINE
        fuel_gal_hr = (fuel_g_s * 3600) / (GASOLINE_DENSITY_G_PER_L * LITERS_PER_GALLON)
        snap["est_fuel_gal_hr"] = fuel_gal_hr

        speed_kmh = snap.get("speed_kmh", snap.get("gps_speed_kmh"))
        if speed_kmh is not None:
            speed_mph = speed_kmh * 0.621371
            with np.errstate(divide="ignore", invalid="ignore"):
                mpg = speed_mph / snap["est_fuel_gal_hr"]
            snap["est_instant_mpg"] = mpg.where(
                (speed_kmh >= LOW_SPEED_MPG_SUPPRESS_KMH) & (snap["est_fuel_gal_hr"] > 0)
            )

    return snap


def compute_rolling_mpg(snap: pd.DataFrame) -> pd.Series:
    """A well-behaved alternative to est_instant_mpg. Confirmed directly on a real drive that
    est_instant_mpg (speed / fuel_rate, a point-in-time ratio) is badly misleading to look at:
    during coasting or deceleration fuel-cut, fuel rate drops toward zero while speed is still
    real, so the ratio spikes toward infinity (max 248 MPG observed) even though that moment
    contributes almost nothing to the trip's actual fuel total either way. The true moving-only
    MPG for that drive (sum distance / sum fuel, the same method compute_overall_mpg uses) was
    31.2; est_instant_mpg's median while moving was 42.6 with a 75th percentile of 99.7, wildly
    unrepresentative because you can't average a ratio and expect it to match the ratio of sums.

    This sums distance and fuel separately over a rolling window and divides once, the same
    correct method, just windowed instead of whole-trip. Confirmed this produces a much more
    representative signal on the same drive: median 33.7, max 143 (vs. est_instant_mpg's 42.6
    median / 248 max) for the same actual driving."""
    if "speed_kmh" not in snap or "est_fuel_gal_hr" not in snap:
        return pd.Series(dtype=float)

    dist_mi_per_s = (snap["speed_kmh"].fillna(0) / 3600) * 0.621371
    fuel_gal_per_s = snap["est_fuel_gal_hr"].fillna(0) / 3600
    window = int(ROLLING_MPG_WINDOW_S / GRID_INTERVAL_S)

    roll_dist = dist_mi_per_s.rolling(window, min_periods=window // 2, center=True).sum()
    roll_fuel = fuel_gal_per_s.rolling(window, min_periods=window // 2, center=True).sum()
    with np.errstate(divide="ignore", invalid="ignore"):
        rolling_mpg = roll_dist / roll_fuel
    return rolling_mpg.where(roll_fuel > ROLLING_MPG_MIN_FUEL_GAL)


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


def compute_overall_mpg(snap: pd.DataFrame, distance_km: float | None) -> float | None:
    """Trip-average MPG: total distance / total fuel burned, not an average of the
    per-second (speed-suppressed) instantaneous estimates. Correctly comes out near 0 for a
    session where the car never moved but the engine burned fuel at idle."""
    if "est_fuel_gal_hr" not in snap or distance_km is None:
        return None
    total_gallons = float(np.nansum(snap["est_fuel_gal_hr"].fillna(0)) * GRID_INTERVAL_S / 3600)
    if total_gallons <= 0:
        return None
    miles = distance_km * 0.621371
    return miles / total_gallons


# ---------------------------------------------------------------------------
# Driving-behavior phase classification and braking-waste coaching
# ---------------------------------------------------------------------------

# km/h per second. Coasting (foot off gas, drag/engine braking only) is much gentler than
# actually pressing the brake pedal, a moderate brake application easily runs 3-5x steeper.
# These are representative vehicle-dynamics values, not measured from this specific car; if a
# real drive's events look misclassified (e.g. normal engine braking flagged as "braking"),
# tune these rather than trusting them as ground truth.
#
# Confirmed directly against real data: raw Vehicle Speed (PID 0D) is integer-quantized to 1
# km/h (standard resolution for this PID), so a naive 1-second frame-to-frame diff has a noise
# floor of roughly +-2 km/h/s from quantization alone (measured std ~2.25 on a real drive). The
# original thresholds here (+-1.5 to +-2.0) sat almost on top of that noise floor and produced
# 246 phase changes on one 16-minute drive, nearly all of them noise flicker between cruising/
# accelerating/coasting, not real driving. Fixed two ways: thresholds widened well clear of the
# confirmed noise floor, and cruising/accelerating/coasting/decelerating are classified from a
# smoothed acceleration signal. Braking still uses the raw (unsmoothed) signal deliberately,
# real hard-braking events are brief and get diluted below threshold by smoothing, which was
# actually observed while tuning this (braking briefly vanished from the classification
# entirely at 5s smoothing before this two-signal split was added).
PHASE_SMOOTH_WINDOW_S = 5
ACCEL_THRESHOLD_KMH_S = 3.0
COAST_DECEL_THRESHOLD_KMH_S = -3.0
BRAKE_DECEL_THRESHOLD_KMH_S = -6.0
COAST_THROTTLE_MAX_PCT = 5.0  # "foot off the gas"
COAST_LOOKBACK_S = 10  # how far back to check for a coast phase before a braking event
# Only merges short runs of these two "background" phases into their surroundings, residual
# noise-driven flicker at the cruise/accelerate boundary even after smoothing. Deliberately
# does NOT touch idle/braking/coasting/decelerating: those represent real, meaningful driver
# actions/events even when brief (a 2-second coast right before braking is exactly the case
# find_braking_waste_events's coasted_first check needs to see, merging it away would silently
# defeat that check). Confirmed directly: an earlier version merged indiscriminately and made
# short coast/decel runs disappear entirely, plus a separate bug where merging let 'braking'
# spread into its neighbors instead of the reverse, both fixed by this narrower scope.
PHASE_MERGE_BACKGROUND = frozenset({"cruising", "accelerating"})
MIN_PHASE_DWELL_S = 2
# Confirmed directly on a real drive: Throttle Position at Tier B's nominal ~3-5s cadence was
# actually 7-10s stale during real lift-off moments, exactly what this classification needs
# fresh. Promoted to Tier A in the app (see PidCatalog.kt) going forward; this guard is
# defense-in-depth for older sessions or any future degraded rotation. Age above this: don't
# claim "coasting" without fresh evidence, fall back to the throttle-agnostic "decelerating".
MAX_THROTTLE_AGE_S = 3.0

PHASE_IDLE = "idle"
PHASE_ACCELERATING = "accelerating"
PHASE_CRUISING = "cruising"
PHASE_COASTING = "coasting"
PHASE_DECELERATING = "decelerating"  # off-throttle but not steep enough to call "braking"
PHASE_BRAKING = "braking"

# Converts kinetic energy dissipated as brake heat into an equivalent fuel cost. There's no
# generic OBD-II PID for brake pedal position, so "braking" below is inferred purely from
# deceleration rate, not measured, and these two constants are representative assumptions, not
# measured for this vehicle. Treat resulting numbers as illustrative and most trustworthy when
# compared *within* the same drive (relative), not as certified absolute fuel costs.
ASSUMED_VEHICLE_MASS_KG = 1650.0  # ~2020 Mazda 6 2.5T curb weight + a typical occupant/cargo load
ASSUMED_ENGINE_EFFICIENCY = 0.28  # representative part-throttle gasoline engine thermal efficiency
GASOLINE_ENERGY_J_PER_GALLON = 1.2132e8  # 33.7 kWh/gal


def _phase_runs(phase: pd.Series) -> list[tuple[int, int, str]]:
    """(start, end, label) positional-index triples, inclusive, for each contiguous run."""
    values = phase.tolist() + [None]
    runs: list[tuple[int, int, str]] = []
    current, start = None, 0
    for i, v in enumerate(values):
        if v != current:
            if current is not None:
                runs.append((start, i - 1, current))
            current, start = v, i
    return runs


def _merge_short_background_runs(phase: pd.Series) -> pd.Series:
    """Absorbs short runs of PHASE_MERGE_BACKGROUND labels into whichever neighbor precedes
    them (or follows, for a run at the very start). See PHASE_MERGE_BACKGROUND's comment for
    why only these two labels are eligible, everything else is a meaningful event even when
    brief and must never be merged away or used as a merge target."""
    merged = phase.copy()
    changed = True
    while changed:
        changed = False
        runs = _phase_runs(merged)
        for idx, (start, end, label) in enumerate(runs):
            if label in PHASE_MERGE_BACKGROUND and (end - start + 1) < MIN_PHASE_DWELL_S:
                target = runs[idx - 1][2] if idx > 0 else (runs[idx + 1][2] if idx + 1 < len(runs) else label)
                if target != label:
                    merged.iloc[start : end + 1] = target
                    changed = True
                    break
    return merged


def classify_phases(snap: pd.DataFrame) -> pd.Series:
    """One phase label per 1-second row. Braking is inferred purely from deceleration rate
    (no generic OBD-II PID exposes brake pedal position), so this can't distinguish "pressed
    the brake" from e.g. a hard manual downshift; treat it as a proxy, not a direct reading."""
    if "speed_kmh" not in snap or snap.empty:
        return pd.Series(dtype=object)

    speed = snap["speed_kmh"]
    # Two different signals deliberately: braking needs to see a brief, sharp spike (smoothing
    # would dilute it below threshold), the gentler bands need the noise averaged out first.
    # See the constants block above for why this split exists.
    accel_raw = speed.diff() / GRID_INTERVAL_S
    accel_smooth = speed.rolling(PHASE_SMOOTH_WINDOW_S, center=True, min_periods=1).mean().diff() / GRID_INTERVAL_S
    throttle = snap["throttle_pct"] if "throttle_pct" in snap else pd.Series(np.nan, index=snap.index)
    throttle_age = snap["age_s_throttle_pct"] if "age_s_throttle_pct" in snap else pd.Series(np.nan, index=snap.index)
    throttle_fresh = throttle_age.notna() & (throttle_age <= MAX_THROTTLE_AGE_S)

    phase = pd.Series(PHASE_CRUISING, index=snap.index, dtype=object)
    moving = speed >= IDLE_SPEED_THRESHOLD_KMH
    phase[~moving] = PHASE_IDLE
    phase[moving & (accel_raw <= BRAKE_DECEL_THRESHOLD_KMH_S)] = PHASE_BRAKING
    not_braking = phase != PHASE_BRAKING
    decel_band = moving & not_braking & (accel_smooth <= COAST_DECEL_THRESHOLD_KMH_S)
    phase[decel_band & throttle_fresh & (throttle <= COAST_THROTTLE_MAX_PCT)] = PHASE_COASTING
    phase[decel_band & ~(throttle_fresh & (throttle <= COAST_THROTTLE_MAX_PCT))] = PHASE_DECELERATING
    phase[moving & not_braking & (accel_smooth >= ACCEL_THRESHOLD_KMH_S)] = PHASE_ACCELERATING
    return _merge_short_background_runs(phase)


def _contiguous_true_runs(mask: pd.Series) -> list[tuple[int, int]]:
    """(start, end) positional-index pairs, inclusive, for each contiguous run of True."""
    runs = []
    in_run = False
    start = 0
    for i, v in enumerate(mask.to_numpy()):
        if v and not in_run:
            start, in_run = i, True
        elif not v and in_run:
            runs.append((start, i - 1))
            in_run = False
    if in_run:
        runs.append((start, len(mask) - 1))
    return runs


def find_braking_waste_events(snap: pd.DataFrame, phase: pd.Series) -> pd.DataFrame:
    """For each contiguous braking segment: the kinetic energy dissipated as brake heat, its
    fuel-equivalent cost (see ASSUMED_VEHICLE_MASS_KG / ASSUMED_ENGINE_EFFICIENCY above for the
    caveats on that number), and whether a coast phase preceded it, the actionable signal, did
    the driver lift off the gas before braking, or carry power/speed right up to the brakes."""
    if "speed_kmh" not in snap or phase.empty:
        return pd.DataFrame()

    rows = []
    for start, end in _contiguous_true_runs(phase == PHASE_BRAKING):
        v_start_kmh = snap["speed_kmh"].iloc[start]
        v_end_kmh = snap["speed_kmh"].iloc[end]
        if pd.isna(v_start_kmh) or pd.isna(v_end_kmh) or v_start_kmh <= v_end_kmh:
            continue
        v_start_ms, v_end_ms = v_start_kmh / 3.6, v_end_kmh / 3.6
        ke_joules = 0.5 * ASSUMED_VEHICLE_MASS_KG * (v_start_ms**2 - v_end_ms**2)
        fuel_equiv_gal = ke_joules / (ASSUMED_ENGINE_EFFICIENCY * GASOLINE_ENERGY_J_PER_GALLON)

        lookback_start = max(0, start - COAST_LOOKBACK_S)
        coasted_first = bool((phase.iloc[lookback_start:start] == PHASE_COASTING).any())

        rows.append(
            {
                "start_s": snap["elapsed_s"].iloc[start],
                "end_s": snap["elapsed_s"].iloc[end],
                "speed_start_kmh": round(float(v_start_kmh), 1),
                "speed_end_kmh": round(float(v_end_kmh), 1),
                "fuel_equiv_ml": round(fuel_equiv_gal * LITERS_PER_GALLON * 1000, 1),
                "coasted_first": coasted_first,
                "latitude": snap["latitude"].iloc[start] if "latitude" in snap else None,
                "longitude": snap["longitude"].iloc[start] if "longitude" in snap else None,
            }
        )

    return pd.DataFrame(rows)


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

PLAUSIBLE_RPM_FLOOR = 100.0
MIN_PLAUSIBLE_VIN_LENGTH = 11


def vehicle_awake_flags(samples: pd.DataFrame, events: pd.DataFrame) -> list[str]:
    """Retrospective version of the same check the app/pc_logger do live: a response arriving
    isn't proof the vehicle was actually awake, some cheap ELM327 clones fabricate plausible-
    looking placeholder data instead of a clean error when the ECU is asleep."""
    flags: list[str] = []

    if not events.empty:
        # A successful read looks like "VIN=<value>" (event_type ONE_TIME_READ); a failed one
        # looks like "VIN: <exception>" (event_type ONE_TIME_READ_FAILED) - real adapters throw
        # rather than return empty just as often as they return blank, so check for both.
        vin_success = events[events["message"].astype(str).str.startswith("VIN=")]
        vin_value = vin_success.iloc[0]["message"].split("=", 1)[1] if not vin_success.empty else ""
        vin_failed = events[
            (events["event_type"] == "ONE_TIME_READ_FAILED")
            & events["message"].astype(str).str.startswith("VIN:")
        ]
        # The Android app dropped the VIN check entirely (see KNOWN_ISSUES.md, it never once
        # worked on the test vehicle and was pure UI clutter), so newer sessions have neither a
        # success nor a failure event for it at all. Only flag an actual failed/empty read, not
        # "wasn't attempted" - those aren't the same thing and conflating them would flag every
        # future session for a check that no longer exists.
        vin_attempted = not vin_success.empty or not vin_failed.empty
        if vin_attempted and (vin_failed.any().any() or len(vin_value) < MIN_PLAUSIBLE_VIN_LENGTH):
            detail = vin_failed.iloc[0]["message"] if not vin_failed.empty else f"got {vin_value!r}"
            flags.append(
                f"VIN did not come back as a real identifier at session start ({detail}). "
                "Consistent with the vehicle's bus being fully asleep rather than a logging "
                "problem; treat the rest of this session's OBD data with suspicion."
            )

    rpm = samples[samples["canonical_name"] == "Engine RPM"]["value_numeric"].dropna()
    if not rpm.empty and (rpm <= PLAUSIBLE_RPM_FLOOR).all():
        flags.append(
            "RPM never exceeded a plausible idle floor for the entire session. Either the engine "
            "genuinely never ran, or the adapter was returning placeholder zeros instead of real "
            "ECU data; check the VIN flag above and the raw samples before trusting this session's "
            "engine-derived metrics."
        )

    return flags


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


def make_plots(snap: pd.DataFrame, out_dir: Path, overall_mpg: float | None = None) -> None:
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

    if "est_instant_mpg" in snap or "rolling_mpg" in snap:
        fig, ax1 = plt.subplots(figsize=(10, 4))
        if "est_instant_mpg" in snap:
            # Faint reference only: this is a point-in-time ratio that spikes toward infinity
            # whenever fuel rate drops near zero (coasting, decel fuel-cut), see
            # compute_rolling_mpg's docstring. Not representative on its own, kept visible so the
            # difference from the corrected line below is honest, not hidden.
            ax1.plot(t, snap["est_instant_mpg"], color="tab:green", alpha=0.25, linewidth=0.8, label="Raw instantaneous (misleading, see docs)")
        if "rolling_mpg" in snap:
            ax1.plot(t, snap["rolling_mpg"], color="tab:blue", linewidth=1.8, label=f"{ROLLING_MPG_WINDOW_S}s rolling (sum distance / sum fuel)")
        if overall_mpg is not None:
            # Explicit reference rather than something to eyeball: this line includes idle time
            # (0 MPG, real distance-weighted drag on the average), so the rolling line sitting
            # above it most of the time while the car is moving is expected, not a bug, idle
            # time pulls the trip average down more than its time-share alone would suggest.
            ax1.axhline(overall_mpg, color="black", linewidth=1.2, linestyle="--",
                        label=f"Trip overall: {overall_mpg:.1f} MPG (includes idle)")
        ax1.set_ylabel("Estimated MPG")
        ax1.set_xlabel("Minutes")
        ax1.set_title("Estimated MPG (MAF-derived; suppressed at low speed and outside stoichiometric ops)")
        ax1.legend()
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
    overall_mpg: float | None,
    cruise: pd.DataFrame,
    phases: pd.DataFrame,
    flags: list[str],
    braking_events: pd.DataFrame | None = None,
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
        f"- Overall trip MPG (est., distance/fuel-burned): {overall_mpg:.1f}" if overall_mpg is not None else "- Overall trip MPG: n/a (no fuel data or all suppressed)",
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

    lines += ["", "## Efficiency coaching (estimate, see caveats)", ""]
    if braking_events is not None and not braking_events.empty:
        total_ml = braking_events["fuel_equiv_ml"].sum()
        no_coast = (~braking_events["coasted_first"]).sum()
        lines.append(
            f"{len(braking_events)} braking event(s) detected, an estimated {total_ml:.0f} mL "
            f"of fuel-equivalent kinetic energy went to brake heat across this drive. "
            f"{no_coast} of {len(braking_events)} had no coast phase beforehand, speed was "
            "carried right up to the brakes rather than let off earlier."
        )
        lines.append(
            "\n_Braking is inferred from deceleration rate only (no generic OBD-II PID exposes "
            "brake pedal position), and the fuel-equivalent figure assumes a fixed vehicle mass "
            f"({ASSUMED_VEHICLE_MASS_KG:.0f} kg) and engine efficiency "
            f"({ASSUMED_ENGINE_EFFICIENCY:.0%}), both representative approximations, not "
            "measured for this vehicle. Trust the relative comparison between events in this "
            "drive more than the absolute mL figures._\n"
        )
        top = braking_events.sort_values("fuel_equiv_ml", ascending=False).head(10)
        lines.append(top.to_markdown(index=False))
    else:
        lines.append("_no braking events detected this drive_")

    (out_dir / "analysis_report.md").write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=Path, help="Path to the export .zip or an unzipped export folder")
    parser.add_argument(
        "--gps", type=Path, default=None,
        help="Path to a raw GPSLogger CSV, for when OBD (PC logger) and GPS (phone) come from separate devices",
    )
    parser.add_argument("--out", type=Path, default=None, help="Output directory (default: ./output/<session-id>/)")
    args = parser.parse_args()

    data = load_bundle(args.bundle)
    external_locations = load_gpslogger_csv(args.gps) if args.gps else None
    data = finalize_timing(data, external_locations)
    snap, _per_pid = build_snapshot(data)
    snap = add_derived_columns(snap)
    snap["rolling_mpg"] = compute_rolling_mpg(snap)

    idle_fraction = compute_idle_fraction(snap)
    warmup_s = compute_warmup_duration_s(snap)
    distance = compute_trip_distance(snap)
    overall_mpg = compute_overall_mpg(snap, distance.get("gps_km") or distance.get("obd_km"))
    cruise = find_cruise_windows(snap, warmup_s)
    phases = phase_breakdown(snap)
    coverage = pid_coverage_report(data.samples)
    flags = vehicle_awake_flags(data.samples, data.events) + anomaly_flags(snap, cruise, data.events)
    driving_phase = classify_phases(snap)
    braking_events = find_braking_waste_events(snap, driving_phase)

    out_dir = args.out or Path(__file__).resolve().parent.parent / "output" / str(data.metadata.get("sessionId", "session"))
    out_dir.mkdir(parents=True, exist_ok=True)

    snap["phase"] = driving_phase
    snap.to_csv(out_dir / "snapshot_1s.csv", index=False)
    make_plots(snap, out_dir, overall_mpg=overall_mpg)
    write_report(
        out_dir, data, snap, coverage, idle_fraction, warmup_s, distance, overall_mpg, cruise, phases, flags,
        braking_events=braking_events,
    )

    print(f"Wrote analysis to {out_dir}")
    for f in flags:
        print(f"- {f}")


if __name__ == "__main__":
    main()
