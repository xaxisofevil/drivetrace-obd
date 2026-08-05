"""
Serial transport to a paired ELM327 over its Windows virtual COM port.

Windows exposes a Bluetooth Classic SPP device as a plain COM port once
paired in Bluetooth settings, so this is regular pyserial, no Bluetooth API
needed. Mirrors the read-until-'>'-prompt behavior of kotlin-obd-api's
ObdDeviceConnection (see the Android app's obd/ layer) rather than reading a
fixed byte count.
"""

from __future__ import annotations

import time

import serial
import serial.tools.list_ports


class ObdTimeoutError(Exception):
    pass


def list_com_ports() -> list[tuple[str, str]]:
    """Returns [(device, description), ...] for every visible COM port."""
    return [(p.device, p.description) for p in serial.tools.list_ports.comports()]


class SerialTransport:
    def __init__(self, port: str, baudrate: int = 38400, timeout_s: float = 5.0):
        self.port = port
        self.baudrate = baudrate
        self.timeout_s = timeout_s
        self._conn: serial.Serial | None = None

    def connect(self) -> None:
        self._conn = serial.Serial(self.port, self.baudrate, timeout=self.timeout_s)
        time.sleep(1.0)  # let the adapter settle after the port opens
        self._conn.reset_input_buffer()

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    @property
    def is_connected(self) -> bool:
        return self._conn is not None and self._conn.is_open

    def send_command(self, command: str, timeout_s: float | None = None) -> tuple[str, int]:
        """Sends one AT/PID command, reads until the '>' prompt. Returns
        (cleaned_response_text, latency_ms). One command in flight at a
        time; cheap ELM clones are serial request/response devices."""
        if self._conn is None:
            raise ObdTimeoutError("Not connected")

        deadline = time.monotonic() + (timeout_s or self.timeout_s)
        start = time.monotonic()
        self._conn.reset_input_buffer()
        self._conn.write((command + "\r").encode("ascii"))

        buf = bytearray()
        while time.monotonic() < deadline:
            chunk = self._conn.read(self._conn.in_waiting or 1)
            if chunk:
                buf.extend(chunk)
                if b">" in buf:
                    break
            else:
                time.sleep(0.01)
        else:
            raise ObdTimeoutError(f"Timed out waiting for response to {command!r}")

        latency_ms = int((time.monotonic() - start) * 1000)
        text = buf.decode("ascii", errors="replace")
        text = text.replace(command, "", 1)  # strip echo if the adapter has echo on
        text = text.replace(">", "").replace("\r", " ").replace("\n", " ")
        return text.strip(), latency_ms
