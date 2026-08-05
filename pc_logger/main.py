"""
CLI entry point for the PC drive logger.

Usage:
    python -m pc_logger.main --list-ports
    python -m pc_logger.main --port COM5

Connects to a paired ELM327 over its Windows virtual COM port, runs the same
tiered PID polling as the Android app, and writes samples_long.csv /
events.csv / metadata.json to ./data/raw/pc_session_<timestamp>/. Press
Enter to stop; Ctrl+C also works.

GPS is not handled here; run GPSLogger on your phone during the drive and
pass both files to analyze_drive.py afterward (see scripts/README.md).
"""

from __future__ import annotations

import argparse
import sys
import threading
import time
from pathlib import Path

from . import elm
from .csv_writer import SessionWriter
from .scheduler import PidScheduler, SchedulerEvent
from .transport import SerialTransport, list_com_ports

INITIAL_BACKOFF_S = 1.0
MAX_BACKOFF_S = 15.0
VEHICLE_PROFILE = "2020 Mazda 6 2.5T"

# Some cheap ELM327 clones fabricate plausible-looking zero data instead of a clean error when
# the ECU is asleep, so "a response arrived" isn't proof the vehicle is actually awake. See the
# matching check in the Android app's DriveLoggingService.kt.
PLAUSIBLE_RPM_FLOOR = 100.0
RPM_SAMPLES_BEFORE_CONCLUDING_ENGINE_OFF = 5
MIN_PLAUSIBLE_VIN_LENGTH = 11


def _wait_for_enter(stop_event: threading.Event) -> None:
    try:
        input()
    except EOFError:
        pass
    stop_event.set()


def run_session(port: str, out_dir: Path) -> None:
    writer = SessionWriter(out_dir, VEHICLE_PROFILE, port)
    stop_event = threading.Event()
    input_thread = threading.Thread(target=_wait_for_enter, args=(stop_event,), daemon=True)
    input_thread.start()

    print(f"Logging to {out_dir}")
    print("Press Enter to stop.\n")

    transport = SerialTransport(port)
    backoff_s = INITIAL_BACKOFF_S
    start_monotonic_ns = time.monotonic_ns()
    sequence = 0
    reconnects = 0

    try:
        while not stop_event.is_set():
            try:
                transport.connect()
                print("Connected. Initializing ELM327...")
                protocol = elm.initialize(transport)
                writer.protocol = protocol
                print(f"Protocol: {protocol}")

                rpm_state = {"samples_seen": 0, "plausible_seen": False}

                def on_measurement(m):
                    writer.write_measurement(m)
                    if m.canonical_name == "Engine RPM":
                        rpm_state["samples_seen"] += 1
                        if (m.value_numeric or 0.0) > PLAUSIBLE_RPM_FLOOR:
                            rpm_state["plausible_seen"] = True

                scheduler = PidScheduler(
                    transport=transport,
                    start_monotonic_ns=start_monotonic_ns,
                    on_measurement=on_measurement,
                    on_event=writer.write_event,
                    sequence_start=sequence,
                )

                print("Reading VIN / DTCs...")
                one_time = scheduler.run_one_time_reads()
                writer.write_one_time_reads(one_time)
                for k, result in one_time.items():
                    print(f"  {k}: {result.value}")
                vin = one_time["VIN"].value if "VIN" in one_time else ""
                if len(vin) < MIN_PLAUSIBLE_VIN_LENGTH:
                    print(
                        "  WARNING: VIN did not come back as a real identifier. The vehicle's "
                        "bus may be fully asleep; check the ignition before trusting this session."
                    )

                backoff_s = INITIAL_BACKOFF_S  # reset after a successful (re)connect
                last_status_print = time.monotonic()

                def should_continue() -> bool:
                    nonlocal last_status_print
                    if time.monotonic() - last_status_print > 5:
                        last_status_print = time.monotonic()
                        writer.flush()
                        engine_status = (
                            "yes" if rpm_state["plausible_seen"]
                            else "NO - check ignition" if rpm_state["samples_seen"] >= RPM_SAMPLES_BEFORE_CONCLUDING_ENGINE_OFF
                            else "checking..."
                        )
                        print(
                            f"\r  measurements={writer.measurement_count}  reconnects={reconnects}  "
                            f"engine_detected={engine_status}   ",
                            end="",
                        )
                        sys.stdout.flush()
                    return not stop_event.is_set()

                scheduler.run(should_continue)
                sequence = scheduler.sequence

            except Exception as e:
                if stop_event.is_set():
                    break
                reconnects += 1
                transport.close()
                writer.write_event(
                    SchedulerEvent(
                        elapsed_ns=time.monotonic_ns() - start_monotonic_ns,
                        event_type="RECONNECT",
                        severity="WARNING",
                        message=f"{e.__class__.__name__}: {e}",
                    )
                )
                print(f"\nReconnecting after error: {e!r} (attempt {reconnects}, waiting {backoff_s:.0f}s)")
                time.sleep(backoff_s)
                backoff_s = min(backoff_s * 2, MAX_BACKOFF_S)
    finally:
        transport.close()
        writer.close("COMPLETED" if stop_event.is_set() else "INTERRUPTED")
        print(f"\nSession complete. Wrote {writer.measurement_count} measurements to {out_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", help="COM port the ELM327 is paired on, e.g. COM5")
    parser.add_argument("--list-ports", action="store_true", help="List available COM ports and exit")
    parser.add_argument("--out", type=Path, default=None, help="Output directory (default: data/raw/pc_session_<ts>)")
    args = parser.parse_args()

    if args.list_ports or not args.port:
        ports = list_com_ports()
        if not ports:
            print("No COM ports found. Pair the ELM327 in Windows Bluetooth settings first.")
        else:
            print("Available COM ports:")
            for device, desc in ports:
                print(f"  {device}  ({desc})")
        if not args.port:
            print("\nRe-run with --port COMx to start logging.")
            return

    out_dir = args.out or Path(__file__).resolve().parent.parent / "data" / "raw" / f"pc_session_{int(time.time())}"
    run_session(args.port, out_dir)


if __name__ == "__main__":
    main()
