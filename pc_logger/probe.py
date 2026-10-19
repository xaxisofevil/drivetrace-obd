"""
Read-only diagnostic probe for an ELM327 on a car's OBD-II port.

Finds which modules answer on the 11-bit CAN bus, tries known candidate requests (TPMS
first), and optionally sweeps one module's Mode 22 data identifiers. Every request and the
verbatim reply go to a JSONL file; nothing is decoded beyond classifying the reply as
positive, negative (with the NRC) or silent. Meaning has to be worked out from the bytes.

Read-only is enforced in `_send_uds`: only the services in READ_ONLY_SERVICES can leave this
module, so a typo cannot become a write, a routine control or a session change.

Run with the car parked and the ignition on. Disconnect the phone from the adapter first; a
classic-Bluetooth ELM327 takes one client.

    python -m pc_logger.probe list-ports
    python -m pc_logger.probe discover   --port COM5
    python -m pc_logger.probe candidates --port COM5 [--discovery probe_out/discover-....jsonl]
    python -m pc_logger.probe sweep      --port COM5 --module 720 --start F000 --end F1FF
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

from .elm import initialize
from .transport import ObdTimeoutError, SerialTransport, list_com_ports

# Mode 22 read, 3E tester present, 19 read DTC, 01/03/07/09/0A the standard OBD reads.
READ_ONLY_SERVICES = {0x22, 0x3E, 0x19, 0x01, 0x03, 0x07, 0x09, 0x0A}

NRC_NAMES = {
    0x10: "generalReject",
    0x11: "serviceNotSupported",
    0x12: "subFunctionNotSupported",
    0x13: "incorrectLength",
    0x22: "conditionsNotCorrect",
    0x31: "requestOutOfRange",
    0x33: "securityAccessDenied",
    0x35: "invalidKey",
    0x78: "responsePending",
}

# Candidate TPMS requests collected from forum threads for older Mazdas. None is confirmed for
# the 2020 Mazda 6; the point of the probe is to find out.
TPMS_CANDIDATES = [
    ("720", ["2A05", "2A06", "2A07", "2A08", "2A0A", "2A0B", "2A0C", "2A0D"]),
    ("726", ["280F", "2810", "2811", "2812"]),
    ("751", ["C901"]),
]

# Standard UDS identification DIDs, useful for naming each module that answers.
IDENT_DIDS = ["F187", "F188", "F18A", "F190", "F191", "F193", "F194", "F195"]

_HEX_TOKEN = re.compile(r"\b[0-9A-F]{2}\b")


class Probe:
    def __init__(self, transport: SerialTransport, out_path: Path):
        self.t = transport
        self.out = out_path.open("a", encoding="utf-8")
        self.count = 0

    def close(self) -> None:
        self.out.close()

    def setup(self) -> str:
        protocol = initialize(self.t)
        # Headers on so the responding module is visible; adaptive timing off and a fixed
        # 256 ms wait so a slow module is not mistaken for an absent one.
        for cmd in ("ATH1", "ATAT0", "ATST40", "ATCAF1"):
            self.t.send_command(cmd, timeout_s=3.0)
        return protocol

    def _log(self, record: dict) -> None:
        self.out.write(json.dumps(record) + "\n")
        self.out.flush()
        self.count += 1

    def _send_uds(self, header: str, request_hex: str) -> dict:
        service = int(request_hex[:2], 16)
        if service not in READ_ONLY_SERVICES:
            raise ValueError(f"service {service:02X} is not on the read-only list")
        self.t.send_command(f"ATSH{header}", timeout_s=3.0)
        try:
            raw, latency_ms = self.t.send_command(request_hex, timeout_s=6.0)
        except ObdTimeoutError:
            raw, latency_ms = "", -1
        record = {
            "ts": time.time(),
            "header": header,
            "request": request_hex,
            "raw": raw,
            "latency_ms": latency_ms,
            **classify(raw, request_hex),
        }
        self._log(record)
        return record

    def discover(self, first: int = 0x700, last: int = 0x7F7) -> list[str]:
        """Sends a tester-present to every physical request ID. Any reply, even a negative
        one, shows that a module listens there. Returns the request IDs that answered."""
        found: list[str] = []
        for tx in range(first, last + 1):
            header = f"{tx:03X}"
            rec = self._send_uds(header, "3E00")
            if rec["status"] == "silent":
                rec = self._send_uds(header, "22F190")
            if rec["status"] != "silent":
                found.append(header)
                print(f"  {header}: {rec['status']} {rec.get('detail', '')} {rec['raw'][:60]}")
            elif tx % 32 == 0:
                print(f"  ...{header}")
        return found

    def identify(self, headers: list[str]) -> None:
        for header in headers:
            for did in IDENT_DIDS:
                self._send_uds(header, f"22{did}")

    def candidates(self, extra_headers: list[str]) -> None:
        for header, dids in TPMS_CANDIDATES:
            for did in dids:
                self._send_uds(header, f"22{did}")
        # The same DIDs on every module that answered discovery, in case the module address
        # differs on this car.
        seen = {h for h, _ in TPMS_CANDIDATES}
        wanted = ["2A05", "2A06", "2A07", "2A08", "280F", "2810", "2811", "2812"]
        for header in extra_headers:
            if header in seen:
                continue
            for did in wanted:
                self._send_uds(header, f"22{did}")

    def sweep(self, header: str, start: int, end: int) -> None:
        positives = 0
        for did in range(start, end + 1):
            rec = self._send_uds(header, f"22{did:04X}")
            if rec["status"] == "positive":
                positives += 1
                print(f"  {did:04X}: {rec['raw'][:80]}")
        print(f"sweep done: {positives} positive replies")


def classify(raw: str, request_hex: str) -> dict:
    upper = raw.upper()
    if not raw or "NO DATA" in upper or "UNABLE TO CONNECT" in upper:
        return {"status": "silent"}
    if "CAN ERROR" in upper or "BUS" in upper or "?" in upper:
        return {"status": "adapter_error"}
    tokens = _HEX_TOKEN.findall(upper)
    service = request_hex[:2].upper()
    # Negative reply: 7F <service> <nrc>.
    for i in range(len(tokens) - 2):
        if tokens[i] == "7F" and tokens[i + 1] == service:
            nrc = int(tokens[i + 2], 16)
            return {"status": "negative", "nrc": f"{nrc:02X}", "detail": NRC_NAMES.get(nrc, "")}
    positive_service = f"{int(service, 16) + 0x40:02X}"
    if positive_service in tokens:
        return {"status": "positive"}
    return {"status": "other"}


def _out_path(kind: str) -> Path:
    out_dir = Path("probe_out")
    out_dir.mkdir(exist_ok=True)
    return out_dir / f"{kind}-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"


def _load_discovered(path: str | None) -> list[str]:
    if not path:
        return []
    answered: list[str] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        rec = json.loads(line)
        if rec["status"] != "silent" and rec["header"] not in answered:
            answered.append(rec["header"])
    return answered


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Read-only ELM327 diagnostic probe")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list-ports")
    for name in ("discover", "candidates", "sweep"):
        s = sub.add_parser(name)
        s.add_argument("--port", required=True)
        if name == "candidates":
            s.add_argument("--discovery", help="JSONL from a previous discover run")
        if name == "sweep":
            s.add_argument("--module", required=True, help="request ID, e.g. 720")
            s.add_argument("--start", required=True, help="first DID, hex")
            s.add_argument("--end", required=True, help="last DID, hex")
    args = p.parse_args(argv)

    if args.cmd == "list-ports":
        for device, desc in list_com_ports():
            print(device, desc)
        return 0

    transport = SerialTransport(args.port)
    transport.connect()
    out_path = _out_path(args.cmd)
    probe = Probe(transport, out_path)
    try:
        print("protocol:", probe.setup())
        if args.cmd == "discover":
            found = probe.discover()
            print("answering request IDs:", ", ".join(found) or "none")
            probe.identify(found)
        elif args.cmd == "candidates":
            probe.candidates(_load_discovered(args.discovery))
        else:
            probe.sweep(args.module.upper(), int(args.start, 16), int(args.end, 16))
        print(f"{probe.count} requests logged to {out_path}")
    finally:
        probe.close()
        transport.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
