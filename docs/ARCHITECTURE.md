# Architecture

## Components

```
app/                    Android app (Kotlin, Jetpack Compose)
  obd/                    Bluetooth transport, ELM327 init, tiered PID scheduler,
                          PID catalog, bug-workaround "safe" commands
  service/                Foreground service (survives screen-off), process-wide
                          UI status bus (LoggingStatus)
  streaming/              Best-effort live stream + guaranteed backfill + analysis
                          request/poll client
  export/                 CSV bundle export, on-device rough trip-MPG estimate
  ui/                     Setup screen (pick adapter), Logging screen (status +
                          results)
  data/                   Room database (local, authoritative)

pc_logger/              Python equivalent for a Windows laptop instead of a phone.
                        Same PID tiers, same cooldown/backoff design, same CSV
                        schema. No GPS of its own (see pc_logger/README.md).

server/                 Home PC ingest server: FastAPI + DuckDB.
  ingest_server.py         HTTP endpoints (see below)
  analysis_worker.py       Runs analyze_drive.py's pipeline directly against
                          DuckDB, triggered by the app, polled by the app

scripts/
  analyze_drive.py        The one analysis pipeline. Used three ways:
                          (1) manually against an exported CSV bundle,
                          (2) manually against a PC-logger session directory,
                          (3) automatically by analysis_worker.py against DuckDB.
                          Same functions every time, not three implementations.

blueprint/              The original spec this project was built from.
```

## Data flow for one drive (Android app path)

1. **Setup screen**: pick the bonded ELM327 adapter (bonded-devices-only,
   never scans, see blueprint's Bluetooth permission rules). Defaults to
   whichever bonded device's name contains "OBD".
2. **Start Logging** → `DriveLoggingService` starts as a foreground service
   (`connectedDevice|location` type), connects, runs the ELM327 AT init
   sequence, reads DTCs/fuel type/supported-PIDs once, then polls the
   tiered PID list continuously.
3. Every measurement/location/event:
   - writes to **local Room** first, synchronously — this is the
     authoritative copy, always complete regardless of network
   - a **best-effort, fire-and-forget** HTTP POST also streams it live to
     the home server, with a circuit breaker that backs off after 5
     consecutive failures (see `StreamingClient.postFireAndForget`)
4. **Stop Logging** →
   - local session marked `COMPLETED`
   - `POST /sessions/{id}/end`
   - **backfill**: the complete local Room dataset gets bulk-uploaded to
     three `/bulk` endpoints, which **delete-then-insert** (full replace,
     not merge/dedup) so the server's copy is guaranteed to match local
     exactly, closing whatever gaps the live stream missed
   - only if backfill succeeds: `POST /sessions/{id}/analyze` triggers
     server-side analysis in a background thread; the app polls
     `GET /sessions/{id}/analysis` every 3s for up to ~60s
   - results (MPG, idle fraction, warm-up, anomaly flags) display directly
     on the Session Complete screen, alongside a rougher on-device estimate
     computed independently from local Room (works even if the server is
     unreachable)

## Key design decisions and why

**Local storage is always authoritative, streaming is always optional.**
Every write path goes to Room first; nothing about streaming, backfill, or
analysis can ever cause data loss, only reduce how automatically the
results reach you. This is the blueprint's core reliability rule (section
9) applied to the network layer too.

**Backfill replaces, doesn't merge.** Delete-then-insert per session,
in one transaction, rather than tracking which rows already streamed and
deduplicating. Provably correct (verified: calling twice with different
payloads leaves exactly the second payload) and only possible because
backfill runs once, after logging has already stopped, no concurrent
writes to race against.

**In-app polling instead of push notifications.** A real push (Firebase
Cloud Messaging) would work even if the app were closed, but needs a
Firebase project, credentials, and a new dependency. Polling immediately
after upload achieves the same practical outcome, since the phone is
already open on the Session Complete screen right after a drive, for a
fraction of the setup cost. Revisit this if the workflow changes (e.g., you
want results after closing the app, or a passenger's phone doing the
logging while you're not watching it).

**PIDs get a cooldown, not a permanent ban.** Confirmed directly on a real
drive: a PID (long-term fuel trim) failed twice 17 seconds after connecting
(adapter still settling) and a permanent-drop policy then blacklisted it for
the rest of the drive, despite the vehicle genuinely supporting it. Now a
failing PID pauses for 30s and automatically retries, so a truly
unsupported PID just cycles through cooldown at near-zero cost, while a
transiently-failing supported one recovers.

**Plausibility clamps stay even after root-causing the bug that needed
them.** See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for the kotlin-obd-api bug
that caused RPM/voltage/etc. to read back as astronomically large numbers.
The clamp that catches "physically impossible value" is kept as
defense-in-depth regardless, cheap insurance against whatever the next
undiscovered bug turns out to be.

**PIDs polled are a fixed catalog, not dynamically discovered from the
ECU.** Worth stating explicitly since it's a real architectural
difference from tools like AndrOBD, checked against AndrOBD's actual
source rather than assumed: AndrOBD queries the ECU's own supported-PID
bitmask (Mode 01 PID 00/20/40/60/80) at connect time and only polls PIDs
the ECU declares support for (`getNextSupportedPid()` in `ObdProt.java`).
This project instead polls a fixed, hand-chosen catalog
(`PidCatalog.kt`'s Tier A/B/C lists) every session regardless of what the
ECU claims to support. The one place this project does query that same
supported-PID bitmask (`SafeAvailablePIDsCommand`, see KNOWN_ISSUES.md) is
a one-time diagnostic read logged as an event, it doesn't feed back to
gate what the live scheduler polls. Fine for one known vehicle where the
catalog was chosen by hand and confirmed working; would need to become
AndrOBD's approach (discover, then poll only what's declared supported)
before this could reasonably claim to work well across arbitrary
vehicles, see COMMERCIAL_READINESS.md's testing-coverage caveat.

**"A response arrived" is not "the vehicle is awake."** Some cheap ELM327
clones fabricate plausible-looking placeholder frames (all zeros, 0xFFFF
sentinels) instead of a clean error when the ECU is asleep. Catches this
live by checking whether RPM has ever shown a value above a sane idle
floor, surfaced directly in the UI so you know within seconds of starting
a session whether it's capturing real data, rather than discovering it
after a whole drive. Originally cross-checked against a VIN read too;
dropped (see KNOWN_ISSUES.md) since VIN never once worked on the test
vehicle, an always-"no" signal added no information, just UI clutter.
