"""
Mode 01 PID table: standard SAE J1979 formulas. Tier assignments match the
blueprint (section 6) and the Android app's PidCatalog.kt, so both loggers
poll the same signals at the same relative priority.

Each formula takes the raw data bytes (as ints, mode+PID echo already
stripped) and returns a float.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class PidDef:
    pid: str  # 2-hex-char PID code
    name: str  # canonical_name written to the CSV; matches Android app's naming
    tier: str  # "A", "B", or "C"
    num_bytes: int  # expected data byte count
    parse: Callable[[list[int]], float]
    unit: str
    mode: str = "01"


def _b16(bytes_: list[int]) -> int:
    return (bytes_[0] << 8) + bytes_[1]


TIER_A: list[PidDef] = [
    PidDef("0C", "Engine RPM", "A", 2, lambda b: _b16(b) / 4, "RPM"),
    PidDef("0D", "Vehicle Speed", "A", 1, lambda b: b[0], "km/h"),
    PidDef("04", "Calculated Engine Load", "A", 1, lambda b: b[0] * 100 / 255, "%"),
    PidDef("10", "Mass Air Flow", "A", 2, lambda b: _b16(b) / 100, "g/s"),
    PidDef("06", "Short Term Fuel Trim Bank 1", "A", 1, lambda b: (b[0] - 128) * 100 / 128, "%"),
    PidDef("08", "Long Term Fuel Trim Bank 1", "A", 1, lambda b: (b[0] - 128) * 100 / 128, "%"),
    PidDef("44", "Commanded Equivalence Ratio", "A", 2, lambda b: _b16(b) / 32768, "ratio"),
    PidDef("42", "Control Module Voltage", "A", 2, lambda b: _b16(b) / 1000, "V"),
]

TIER_B: list[PidDef] = [
    PidDef("05", "Engine Coolant Temperature", "B", 1, lambda b: b[0] - 40, "C"),
    PidDef("0F", "Intake Air Temperature", "B", 1, lambda b: b[0] - 40, "C"),
    PidDef("11", "Throttle Position", "B", 1, lambda b: b[0] * 100 / 255, "%"),
    PidDef("0B", "Intake Manifold Pressure", "B", 1, lambda b: b[0], "kPa"),
    PidDef("0A", "Fuel Pressure", "B", 1, lambda b: b[0] * 3, "kPa"),
    PidDef("23", "Fuel Rail Pressure", "B", 2, lambda b: _b16(b) * 10, "kPa"),
    PidDef("5E", "Fuel Consumption Rate", "B", 2, lambda b: _b16(b) / 20, "L/h"),
]

TIER_C: list[PidDef] = [
    PidDef("33", "Barometric Pressure", "C", 1, lambda b: b[0], "kPa"),
    PidDef("46", "Ambient Air Temperature", "C", 1, lambda b: b[0] - 40, "C"),
    PidDef("1F", "Engine Run Time", "C", 2, lambda b: _b16(b), "s"),
    PidDef("31", "Distance Since Codes Cleared", "C", 2, lambda b: _b16(b), "km"),
    PidDef("2C", "Commanded EGR", "C", 1, lambda b: b[0] * 100 / 255, "%"),
    PidDef("2D", "EGR Error", "C", 1, lambda b: (b[0] - 128) * 100 / 128, "%"),
    PidDef("2F", "Fuel Level", "C", 1, lambda b: b[0] * 100 / 255, "%"),
]

ALL_PIDS: list[PidDef] = TIER_A + TIER_B + TIER_C


def decode_dtc_pair(hi: int, lo: int) -> str | None:
    """Decodes one 2-byte DTC per the standard SAE J2012 bit layout. Returns
    None for the (0,0) filler pairs adapters pad responses with."""
    if hi == 0 and lo == 0:
        return None
    system = {0b00: "P", 0b01: "C", 0b10: "B", 0b11: "U"}[(hi >> 6) & 0b11]
    digit1 = (hi >> 4) & 0b11
    digit2 = hi & 0b1111
    digit3 = (lo >> 4) & 0b1111
    digit4 = lo & 0b1111
    return f"{system}{digit1}{digit2:X}{digit3:X}{digit4:X}"


def decode_dtcs(data_bytes: list[int]) -> list[str]:
    codes = []
    for i in range(0, len(data_bytes) - 1, 2):
        code = decode_dtc_pair(data_bytes[i], data_bytes[i + 1])
        if code:
            codes.append(code)
    return codes


def decode_vin(data_bytes: list[int]) -> str:
    """VIN comes back as ASCII bytes, sometimes with a leading index/count
    byte the ELM327 doesn't strip. Keep only printable ASCII and take the
    last 17 characters (VIN length), which is robust to that leading byte."""
    chars = "".join(chr(b) for b in data_bytes if 32 <= b < 127)
    return chars[-17:] if len(chars) >= 17 else chars
