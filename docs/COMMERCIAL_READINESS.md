# Commercial readiness checklist

This project was built for one person's one car. If there's ever intent to
sell or distribute it, here's what stands between "works for me" and
"safe to ship to strangers." Not exhaustive, and none of this is legal
advice, a lawyer should review the licensing and liability items before
any actual sale.

## Licensing

- **kotlin-obd-api (Apache-2.0)**: fine to depend on and redistribute
  compiled, but the current dependency is pinned to an **arbitrary
  unreleased commit** via JitPack (see KNOWN_ISSUES.md), not a stable
  tagged release. Before shipping: either get a proper tagged release
  containing the RPM fix, or fork and vendor the fixed files directly into
  this repo so the build doesn't depend on an upstream commit that could
  become unresolvable.
- **AndrOBD (GPL-3.0)**: the blueprint studied it for architectural ideas
  only, no code was copied. Keep it that way, if any future contributor
  touches the OBD/parsing layer, confirm they haven't pulled in GPL'd code,
  which would force this entire project to be GPL too under a strict
  reading (or at minimum entangle the license in ways worth avoiding for a
  commercial product).
- Audit the rest of the dependency tree before shipping: FastAPI, DuckDB,
  OkHttp, Room, Compose are all permissively licensed (Apache-2.0/MIT/BSD-
  family) as of when they were added, but versions and licenses should be
  re-checked at release time, not assumed to still be what they were when
  first added.

## Multi-tenancy (the biggest architectural gap)

The server is single-user, single-vehicle, single hardcoded bearer token,
single DuckDB file. A commercial product needs either:
- per-customer self-hosting instructions (closer to today's architecture,
  each customer runs their own server, their own token, their own data),
  or
- a real hosted multi-tenant backend (accounts, per-user data isolation,
  a hosting/ops story), which is a substantially different system, not an
  incremental change to what exists today.

Decide which model before doing anything else on this list, it changes the
answer to most of the other items.

## Distribution

- Currently a debug-signed APK, sideloaded via `adb install`. A real
  product needs a release-signed build at minimum.
- Play Store distribution requires the 2026 developer-verification process
  for any Android app installed outside ADB (see the sideloading
  discussion earlier in this project's history), factor that into the
  timeline.
- Whatever channel is chosen, this app requests Bluetooth, location, and
  (for streaming) internet permissions; a real privacy policy describing
  what's collected and where it goes is required for any public
  distribution, app store or not.

## Safety and liability

- Already true architecturally, keep it true: **never write to the ECU,
  never clear DTCs**. This is enforced by simply never calling those
  commands, not by a runtime check, if anyone adds a "clear codes" feature
  later, treat it as a deliberate, carefully-scoped decision, not a casual
  addition.
- The in-app "Session complete" MPG/analysis numbers are estimates from
  MAF-based fuel approximation, not a certified measurement. Any public-
  facing copy should say so plainly, "estimate" not "your car's true fuel
  economy is X."
- Explicit "don't operate the phone while driving" messaging exists in the
  original workflow (blueprint section 4); make sure it survives into any
  onboarding/setup flow for new users who haven't seen that context.
- Anomaly flags are deliberately written as "consistent with X," never "is
  X." Keep that voice in any user-facing surfacing of them, this is
  diagnostic-adjacent, not a mechanic's diagnosis, and language that
  overclaims creates real liability exposure if someone acts on a wrong
  inference.

## Security

- **No TLS today.** Bearer token travels in cleartext over plain HTTP.
  Acceptable for one person's own low-value data; not acceptable the
  moment this handles anyone else's. Add TLS termination (a reverse proxy
  is the simplest path) before any multi-user use.
- Bearer tokens are currently generated once and stored in
  `local.properties`/environment variables by hand. A real product needs
  proper credential issuance and rotation.

## Data durability

Room's schema migrations use `fallbackToDestructiveMigration`, any future
schema bump silently wipes local on-device history rather than migrating
it. Fine today (a handful of dev sessions, all already backfilled to the
server, which is the actual source of truth once a drive completes); not
fine the moment a real customer's only copy of a drive could be sitting
unsynced on their phone when an app update bumps the schema. Write real
`Migration` objects before that's a possibility.

## Testing coverage

Validated against exactly **one vehicle** (2020 Mazda 6 2.5T) and **one
ELM327 clone adapter**. Before any claim of broader compatibility:
- Test against genuine ELM327 chips (not just clones) and at least a few
  other common clone chipsets.
- Test against vehicles from other manufacturers, OBD-II PID support and
  quirks (like the VIN issue in KNOWN_ISSUES.md) vary significantly across
  makes.
- The byte-overflow bug this project found and fixed was specific to this
  adapter/library combination surfacing under real driving conditions
  (91% corruption rate while moving vs. much lower while stationary);
  other hardware combinations may have entirely different failure modes
  that haven't been discovered yet because they haven't been tested.
