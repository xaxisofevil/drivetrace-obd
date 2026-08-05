"""
ELM327 initialization and response parsing. Mirrors the Android app's
ElmSession.kt / the blueprint's section 5 init sequence, translated to raw AT
commands since there's no kotlin-obd-api equivalent on this side.
"""

from __future__ import annotations

import re

from .pids import PidDef, decode_dtcs, decode_vin
from .transport import ObdTimeoutError, SerialTransport

_HEX_PAIR = re.compile(r"[0-9A-Fa-f]{2}")
_SEARCHING_PATTERN = re.compile(r"SEARCHING\.*", re.IGNORECASE)

# Typed outcomes rather than crashes, per the blueprint's error-handling rules.
# NOTE: "SEARCHING..." is deliberately not in this list. On slow-init protocols
# the adapter prints it and then still returns real data afterward, so it gets
# stripped as noise (matching kotlin-obd-api's SEARCHING_PATTERN handling)
# rather than treated as a failure.
_ERROR_MARKERS = ("NO DATA", "STOPPED", "UNABLE TO CONNECT", "CAN ERROR", "BUS INIT", "?")


class NoDataError(Exception):
    pass


class AdapterError(Exception):
    pass


def initialize(transport: SerialTransport) -> str:
    """Runs the AT setup sequence. Individual AT failures are tolerated
    (cheap clones vary); only returns whatever protocol string comes back."""
    for cmd in ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT1"]:
        try:
            transport.send_command(cmd, timeout_s=3.0)
        except ObdTimeoutError:
            pass

    try:
        transport.send_command("0100", timeout_s=5.0)  # forces ECU comms, per blueprint section 5
    except ObdTimeoutError:
        pass

    try:
        protocol, _ = transport.send_command("ATDP", timeout_s=3.0)
        return protocol.strip() or "UNKNOWN"
    except ObdTimeoutError:
        return "UNKNOWN"


def _extract_data_bytes(response: str, mode_reply: str, pid: str) -> list[int]:
    """response is the cleaned text from SerialTransport.send_command. Strips
    the mode-echo + PID prefix (e.g. '41 0C') and returns the remaining bytes
    as ints. Raises NoDataError/AdapterError on typed failure responses."""
    cleaned = _SEARCHING_PATTERN.sub("", response)
    upper = cleaned.upper()
    for marker in _ERROR_MARKERS:
        if marker in upper:
            raise NoDataError(f"{marker}: {response!r}")

    hex_bytes = [int(h, 16) for h in _HEX_PAIR.findall(cleaned)]
    prefix = [int(mode_reply, 16), int(pid, 16)]
    # Find the prefix anywhere in the byte stream (headers/multi-ECU replies
    # can prepend extra bytes even with ATH0); take the first match.
    for i in range(len(hex_bytes) - 1):
        if hex_bytes[i : i + 2] == prefix:
            return hex_bytes[i + 2 :]
    raise AdapterError(f"Could not find {mode_reply}{pid} in response: {response!r}")


def query_pid(transport: SerialTransport, pid_def: PidDef) -> tuple[float, int]:
    """Returns (value, latency_ms). Raises NoDataError/AdapterError/ObdTimeoutError."""
    mode_reply = f"{int(pid_def.mode, 16) + 0x40:02X}"
    response, latency_ms = transport.send_command(f"{pid_def.mode}{pid_def.pid}")
    data = _extract_data_bytes(response, mode_reply, pid_def.pid)
    if len(data) < pid_def.num_bytes:
        raise AdapterError(f"Expected {pid_def.num_bytes} bytes, got {data} for PID {pid_def.pid}")
    return pid_def.parse(data[: pid_def.num_bytes]), latency_ms


def read_vin(transport: SerialTransport) -> str | None:
    try:
        response, _ = transport.send_command("0902", timeout_s=5.0)
        hex_bytes = [int(h, 16) for h in _HEX_PAIR.findall(response)]
        return decode_vin(hex_bytes) or None
    except (ObdTimeoutError, NoDataError, AdapterError):
        return None


def read_dtcs(transport: SerialTransport, mode: str) -> list[str]:
    """mode: '03' current, '07' pending, '0A' permanent."""
    try:
        response, _ = transport.send_command(mode, timeout_s=5.0)
        cleaned = _SEARCHING_PATTERN.sub("", response)
        if any(m in cleaned.upper() for m in _ERROR_MARKERS):
            return []
        hex_bytes = [int(h, 16) for h in _HEX_PAIR.findall(cleaned)]
        # Drop the mode-reply echo byte (43/47/4A) if present at the front.
        expected_echo = int(mode, 16) + 0x40
        if hex_bytes and hex_bytes[0] == expected_echo:
            hex_bytes = hex_bytes[1:]
        return decode_dtcs(hex_bytes)
    except ObdTimeoutError:
        return []
