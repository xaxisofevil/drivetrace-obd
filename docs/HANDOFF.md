# Handoff log

Eric alternates which AI assistant he's working with (Claude Code, ChatGPT,
whichever isn't rate-limited that week). This file is how the next one picks
up cleanly instead of re-deriving context or, worse, redoing work.

**Rules for whoever is reading this before starting work:**

1. Read this whole file before touching code. It's short on purpose.
2. Check `git log --oneline -15` against the "Last commit" line below. If
   they don't match, something landed since this was last updated, read
   those commits before assuming this file is current.
3. When you finish a session (or hand off mid-task), **append** a new
   entry to the Session log at the bottom. Don't rewrite or delete older
   entries; they're the history. Update the "Current state" section itself
   in place, it's a snapshot, not a log.
4. Keep entries factual: what you did, what you verified vs. assumed, what's
   still open, any new gotcha you hit. Skip narration of the conversation
   itself.
5. This file is not a substitute for `docs/KNOWN_ISSUES.md` (root-caused
   bugs and their fixes) or `docs/DESIGN_SYSTEM.md` (why the UI looks the
   way it does). Point to those rather than duplicating them. This file is
   for "where things stand right now" and environment friction that isn't
   really a project doc's job to carry.

## Current state

**Last commit:** `7f540ea` "Add GPS logging toggle to Settings, off by
default" (2026-08-27/28)

**On the phone right now:** a debug build of `7f540ea`, installed and
confirmed working (`adb install -r` succeeded, correctly signed - see the
keystore gotcha below before assuming any future build will install cleanly
by default).

**Server has moved: it now runs on ericpc, not this laptop.** This
laptop's own copy of the server (`server/run_server.ps1`, the
`DriveTraceIngestServer` Scheduled Task) is stopped and the task disabled
(not deleted - trivial to re-enable if ericpc ever needs to be rolled
back), kept only as an inert fallback. Live URL is now
`https://ericb.duckdns.org:8444` (real TLS via Caddy, replacing the old
plain-HTTP `:8090`). Full history: `docs/SERVER_MIGRATION_PLAN.md`.
**The Android build also moved to ericpc** (this laptop's SDK/Gradle
cache/bundled JDK/AVD were removed to reclaim ~10.8 GB disk; only
`platform-tools`/`adb` remains here for installing pulled APKs). See the
`ericpc-ssh` skill (`~/.claude/skills/ericpc-ssh/`) for how to connect,
what's running there, and every gotcha hit setting this up - don't
re-derive any of it.

**PM2 on ericpc was NOT actually persistent until 2026-08-27** despite
looking like it (see the `ericpc-ssh` skill for the full story) - it went
down completely (both this app and the unrelated "Our Calendar" app it
shares the machine with) on a routine reboot, and wasn't even surviving
between separate SSH commands before that. Fixed with a SYSTEM-run,
boot-time Scheduled Task. **After any ericpc reboot, verify rather than
assume**: `ssh 192.168.0.129 "pm2 list"` should show all three apps
`online` with a real uptime, not `0s`.

**Check `curl --resolve ericb.duckdns.org:8444:192.168.0.129
https://ericb.duckdns.org:8444/health` before assuming the server is up and
running the code you think it's running** - same discipline as before, new
URL.

**Recently built and verified working:** live gauge cluster, PDF trip-report
export, drive-to-drive comparison, premium display skins, Settings screen
split, MacroDroid/Tasker automation (with an in-app setup walkthrough),
tapping a Logbook card to open that drive's full historical trip report,
system back button now closes the current screen instead of the whole app,
Delete Trip (wipes a drive from local Room + server), server-side self-heal
for a missing `sessions` row (was permanently breaking Retry Analysis for
any drive whose one-shot `/start` call missed), and a real fix to the
MAF-based fuel-rate estimate that was silently zeroing out a drive's
highest-consumption seconds (see KNOWN_ISSUES.md for both).

**Known, deliberately open gap:** the 34-minute-hang fix (watchdog coroutine
that force-closes the Bluetooth socket, see KNOWN_ISSUES.md's "Four bugs
found on a live drive when the server died") only covers the **setup**
phase: connect, AT init, one-time reads. A hang **mid-drive**, inside the
ongoing PID-poll loop, is not guarded by anything yet. Real gap, not a
theoretical one, see that doc's "still a real, deliberately unclosed gap"
note.

**Other genuinely open items**, not yet started as of `daeef77`:
- Subaru Outback PID catalog is wired in at the architecture level
  (`VehicleProfile`/`PidCatalog`) but **untested against the actual car**.
  Don't treat it as validated the way the Mazda's catalog is.
- Five Mazda-specific enhanced PIDs (catalyst temp, oil temp, turbo-health
  signals) are substantially untested against a real vehicle, see
  KNOWN_ISSUES.md's "Five manufacturer-specific enhanced PIDs" section.
- Full survey of everything else not-yet-built: `docs/DESIGN_SYSTEM.md`'s
  numbered idea list, anything not marked `**Built.**` is still open, most
  recently idea #11 (cross-vehicle YMM comparison) and #12 (auto drive-end
  detection, designed but not built).

## Environment gotchas (accumulated across sessions, not just one)

- **The build now happens on ericpc, not this laptop** (see Current state
  above and the `ericpc-ssh` skill) - this laptop has no Android SDK/Gradle
  cache/JDK anymore, only `adb`. Don't try to `gradlew assembleDebug`
  locally here; SSH to ericpc instead.
- **ericpc's debug-signing keystore is NOT at the standard
  `%USERPROFILE%\.android\debug.keystore` path** - it's at
  `C:\Android\.android\debug.keystore`, a leftover from a pre-existing,
  years-old install on that machine. A build signed with a keystore only
  placed at the "textbook" path installs fine standalone but fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` over an existing install. Full
  detail in the `ericpc-ssh` skill; don't re-diagnose this from scratch.
- **adb over wireless debugging**: the phone's IP:port changes
  unpredictably, and pairing (Settings → Developer options → Wireless
  debugging → "Pair device with pairing code") is separate from the
  connect address on the main screen - both rotate, there's no way to
  discover either from the PC side alone. Once paired, `adb connect` can
  also succeed via mDNS auto-discovery (shows up as
  `adb-<id>._adb-tls-connect._tcp` in `adb devices`) without needing a
  fresh IP:port each time - try that before asking Eric for a new address.
- **PowerShell's `>` redirect corrupts binary data.** Confirmed directly:
  pulling the Room SQLite file via `adb ... exec-out ... > file.db` through
  PowerShell injected a UTF-8 BOM and mangled bytes mid-file. Use a
  POSIX-shell redirect instead (Bash tool, not PowerShell) combined with
  `adb exec-out` (not plain `adb shell`) for any binary pull off the phone.
- **The ingest server does not auto-reload.** It runs via
  `server/run_server.ps1` (no `--reload`), so editing `server/*.py` and
  expecting the running server to pick it up is wrong, it'll keep serving
  the old code until the scheduled task is ended and re-run (see Server,
  above). The token is already in `run_server.ps1`, it's also baked into
  the Android build via `BuildConfig.INGEST_TOKEN`; no need to ask Eric to
  retype it.
- **The server must survive the session that started it, on purpose** -
  Eric switches between AI assistants and closes terminals/sessions between
  them. On ericpc this is PM2 plus the boot-time `PM2Resurrect` Scheduled
  Task (see Current state above); on this laptop, if the disabled fallback
  is ever re-enabled, it's `server/run_server.ps1` via the
  `DriveTraceIngestServer` Scheduled Task. Never "simplify" either back to
  a bare command in an interactive terminal or SSH session - that
  reintroduces the exact problem this setup exists to avoid, and on ericpc
  specifically, a PM2 daemon spawned that way dies the moment the launching
  console closes (see the `ericpc-ssh` skill).
- **Room's schema is on `fallbackToDestructiveMigration(dropAllTables =
  true)`** (see `AppDatabase.kt`'s own comment). Bumping the DB version
  wipes every session on the phone with no migration path. If a real schema
  change is ever needed, that comment explains why this has been an
  acceptable shortcut so far and what would have to change before it stops
  being one.
- **This app deliberately has no navigation library** (single Activity, a
  `when` block in `MainActivity.kt` switches screens). Every screen that
  isn't the root screen wires its own `BackHandler` to the same callback its
  header's back chevron uses. If a new screen is added and back-button
  exits the app instead of the screen, that's almost certainly a missing
  `BackHandler`, not a deeper bug, see `3fdb9fa` for the fix pattern.

## Session log

### 2026-08-08 (Claude Code)

- Merged `historical-report-view` branch to master (fast-forward, no
  conflicts), rebuilt, reinstalled. This is what made the trip-report
  history feature (tap a Logbook card to see that drive's full report) and
  the system back-button fix both actually present on the phone at the same
  time; before this they were on different, unmerged builds, which is why
  earlier back-button testing looked inconsistent.
- Verified on real device with Eric driving the phone and me watching
  logcat: system back button now closes the current screen (Logbook,
  Settings, Compare, Trip report) instead of exiting the app. Confirmed
  fixed, not just "should be fixed."
- Built and shipped **Delete Trip**: `SessionDao.deleteSession` (Room,
  cascades to child tables), new `DELETE /sessions/{id}` on the ingest
  server (`server/ingest_server.py` + `analysis_worker.forget()`), and a
  confirm-gated "Delete trip" control at the bottom of `TripReportScreen`,
  below a "Danger zone" label, separate from the pinned Export/PDF bar.
  Room deletes first and is authoritative; the server call is awaited
  (not fire-and-forget) so it isn't orphaned by the screen unmounting.
  Committed as `daeef77`. Had to manually restart the ingest server (it
  was running pre-Delete-Trip code) before this could be tested end to end.
- Server confirmed healthy and running current code as of end of session.
- Eric asked for the server to run independent of any particular AI
  session, since he closes terminals between switching assistants. Moved
  it off a plain background shell command and onto a Windows Scheduled
  Task (`DriveTraceIngestServer`, action `server/run_server.ps1`),
  confirmed its process tree's parent is Task Scheduler's own host
  process, not this session's. Survives session/terminal close; does NOT
  survive a reboot (ONLOGON/ONSTART trigger creation was Access Denied in
  this sandbox, plain task creation and manual `/Run` were not). See
  "Server" and the scheduled-task gotcha above for the restart command.
- **Not started, next up if picked up here:** nothing specific queued.
  Eric may want the Delete Trip flow exercised on a real (throwaway) drive
  rather than just the happy-path check done this session. Otherwise pull
  from "Other genuinely open items" above or `docs/DESIGN_SYSTEM.md`'s
  unbuilt ideas.

### 2026-08-08, later same day (Claude Code)

- Eric reported Retry Analysis failing on both his Subaru drives.
  Root-caused and fixed, see `docs/KNOWN_ISSUES.md`'s "Retry Analysis
  failed forever" entry for the full account: the phone's one-shot,
  never-retried `/sessions/{id}/start` call silently missed for both (the
  server was cycling repeatedly while this session worked on unrelated
  things), leaving no `sessions` row for either even though their full
  measurement data backfilled and uploaded successfully. Recovered both
  by hand from their own measurement timestamps, then fixed it
  structurally: the bulk backfill endpoints now self-heal a missing
  `sessions` row instead of depending on `/start` ever landing. Verified
  against a synthetic orphan session ID before touching the real ones.
  **Eric still needs to tap Retry Analysis once more on both Subaru
  drives** from the app; the server-side fix doesn't reach back and
  update what Room already has stored as FAILED.
- Found and corrected a bug in my own prior write-up: `schtasks /End`
  does not actually kill the uvicorn process tree it starts (verified by
  watching the child processes survive it). Fixed the restart
  instructions in this file's Gotchas section; the real command now
  force-kills by matching the command line first.
- Server restarted several times this session for debugging; confirmed
  healthy and running current code as of the end of it.
- No Android app changes this round, server-only. Nothing rebuilt or
  reinstalled on the phone.

### 2026-08-08, later still (Claude Code)

- Eric reported a real Subaru drive showing 38 MPG in the Logbook when
  the ADR/on-device estimate at Stop time said ~21, certain 38 was
  wrong. Root-caused and fixed a real analysis bug, not a display bug:
  `scripts/analyze_drive.py`'s fuel-rate estimate was masking non-
  near-stoichiometric samples to zero fuel burned instead of unknown,
  and confirmed those masked samples were the drive's *highest*-MAF
  moments (acceleration/enrichment), not noise. See KNOWN_ISSUES.md's
  "MPG estimate runs well above the vehicle's own trip computer" update
  for the full account and the before/after numbers on three real
  sessions. Fixed by removing the mask; committed as `a3e4c22`.
- The disputed drive now analyzes at 30.4 MPG (down from 38.7), still
  above Eric's ~21 sense of it and above the vehicle's own trip
  computer by roughly the same still-open, still-unexplained margin
  documented in that same KNOWN_ISSUES.md section (MAF under-reporting
  vs. a more direct injector-pulse-width reference is the leading
  unproven theory). **This fix corrected a real bug but did not fully
  close the gap to the dash reading**; don't treat 30.4 as validated
  accurate, just less wrong than 38.7 was.
- Re-ran analysis server-side for the fixed sessions (the disputed
  drive plus the two Subaru drives recovered earlier tonight) so the
  server holds the corrected numbers. **Eric still needs to tap Retry
  Analysis in the app on all three** for the phone's local Room copy
  to catch up; same category of manual step as the previous entry's
  note, this fix doesn't reach back and update what's already stored
  on the phone.
- No Android app changes, server-only (`scripts/analyze_drive.py`,
  shared by the server's live analysis path and any manual
  `python analyze_drive.py` run against an exported CSV bundle or the
  PC logger). Nothing rebuilt or reinstalled on the phone.

### 2026-08-09 (Claude Code)

- Eric asked ChatGPT to find Subaru-equivalent manufacturer-specific
  (Mode 22) enhanced PIDs, matching the Mazda catalog's depth, and
  specifically hoping for something closer to direct fuel data than
  the MAF reconstruction. ChatGPT found a genuinely strong source: a
  real capture of a 2014 FB25 Forester ECU's own supported-DID bitmap
  (same engine code, same year as the Outback, not the same vehicle).
  Verified the math and checked for collisions before implementing,
  same discipline as the Mazda enhanced-PID work; caught and fixed two
  real bugs of my own along the way (bare `Int` arithmetic feeding a
  `%f` format specifier, which throws at runtime; a naming collision
  where "Target Engine Speed" would have silently matched the existing
  vehicle-speed keyword pattern). Full account in KNOWN_ISSUES.md's
  "Nine manufacturer-specific enhanced PIDs" entry.
- New file `SubaruEnhancedCommands.kt`, wired into `SubaruPidCatalog.kt`
  (four in Tier B: injector pulse width, learned ignition timing, AVCS
  cam angle both banks; five in Tier C: alternator/battery telemetry,
  target RPM), plausibility clamps added to `PidScheduler.kt`, and
  `PID_KEYWORDS` entries added to `analyze_drive.py` including a fix
  to the existing `rpm` pattern to stop it from also matching the new
  Target Engine RPM PID. Build is clean (`assembleDebug` succeeded).
  Server restarted with the updated `analyze_drive.py` and
  regression-checked against the drive fixed earlier tonight (still
  30.4 MPG, no change), so the new PID_KEYWORDS entries didn't disturb
  anything already working.
- **Fuel Injector Pulse Width (0x2210A3) is captured but not yet used
  for anything.** It's a pulse-width number, not a fuel-mass number;
  turning it into one needs this specific injector's flow rate and
  injection frequency, which nobody has yet. Don't expect a second MPG
  estimate to exist until that conversion gets built, this only gets
  the raw signal onto the phone and into Room/CSV/exports.
- **Not installed on the phone** (disconnected all session). Whoever
  picks this up needs to `adb devices` to get the current wireless
  address, then install `app/build/outputs/apk/debug/app-debug.apk`
  before any of this can actually be tested on a real drive. Nothing
  about these nine PIDs is confirmed against the real Outback yet,
  only against a different-but-same-engine Forester's declared support.

### 2026-08-10 (Claude Code)

- Eric reported three things at once: MacroDroid's Send Intent macro
  doing nothing, the foreground notification frozen at "Initializing"
  all drive long, and a feature request to stop a session from the
  lock screen.
- **Notification freeze: root-caused and fixed.** `updateNotification()`
  was called exactly once, right after the initial connect, never
  again for the rest of the drive; `LoggingStatus.state` itself was
  updating live the whole time (that's why the in-app UI looked fine),
  nothing was pushing it back into the notification. Fixed with a 3s
  ticker coroutine, cancelled the same way the existing GPS job is.
  Committed as `0871622`.
- **Lock screen Stop: turned out to already exist in code**, just
  probably unreachable. The notification never called `setVisibility()`
  (defaults to PRIVATE, which can redact a locked-screen notification
  down to a content-hidden line with no action buttons at all). Set to
  `VISIBILITY_PUBLIC` in the same commit. Should resolve the feature
  request without it being a separate feature; needs confirming on a
  real locked phone since I can't verify that part from a build alone.
- **Send Intent automation: NOT resolved, actively blocked on phone
  access.** Eric sent a screenshot of the actual MacroDroid dialog;
  checked it directly against what `AutomationReceiver` expects and it
  is configured correctly, Target/Package/Class/Action/both Extras all
  exactly right, ruling out the leading suspect (missing explicit
  package/class, required since Android 8 for manifest receivers).
  `AutomationReceiver.kt` already logs a specific, actionable reason
  for every rejection path under the `DriveTraceAutomation` tag, so a
  live `adb logcat -s DriveTraceAutomation` capture during a real macro
  trigger should identify the real cause in one attempt. **This is the
  next thing to do the moment the phone is reachable over adb again**:
  clear logcat, start a capture, have Eric fire the macro, read what
  (if anything) shows up. Don't re-read code first, the diagnostic
  logging is already there and more thorough than guessing further
  would be.
- Build is clean (`assembleDebug` succeeded) but **nothing from this
  entry has been installed or tested on the real phone**, it was
  disconnected the entire session. All three items need the phone back
  before they can move past "should be fixed" to "confirmed fixed."

### 2026-08-10, later same day (Claude Code)

- Phone reconnected (`192.168.0.182:37149`, changes every time, ask
  Eric for the current one). Installed the build from the entry above
  and root-caused the Send Intent failure live, via logcat, exactly as
  planned: NOT a code bug in the receiver itself (Eric's macro was
  configured perfectly), but two stacked device-level barriers. See
  KNOWN_ISSUES.md's "Update: root-caused live" for the full account.
  Fixed both directly via adb (Doze whitelist, ACCESS_BACKGROUND_LOCATION)
  and added a defensive try/catch in `DriveLoggingService.onStartCommand`
  so a future phone without that permission granted fails with one
  clear log line instead of a hard crash. **Confirmed end to end with
  Eric's real MacroDroid macro**: clean start, GPS actually recording,
  clean stop, no crash. This one is genuinely done, not just "should
  be fixed."
- Eric separately reported tapping Stop in the app gives no visual
  feedback while backfill/analysis run. Root-caused
  (`sessionComplete` only flips once the *entire* chain finishes,
  nothing marked the gap) and fixed with a new `StoppingBody` view,
  shown the instant Stop is confirmed. Build clean, installed.
  **Not yet visually confirmed** — a screenshot-based check was
  attempted but the tap coordinates missed the button and the
  follow-up (`uiautomator dump` for exact bounds) hit a Git-Bash
  path-mangling issue (`/sdcard/...` getting rewritten to
  `C:/Program Files/Git/...`, prefix with `MSYS_NO_PATHCONV=1` next
  time) before it could be retried; user cut the verification loop
  short rather than let it keep fighting tooling. **Next real Stop
  tap should confirm this visually**, or fix directly if it doesn't
  look right.
- Server was healthy and untouched this entry, no server-side changes.
- Committed as `14039fc`.

### 2026-08-26 to 2026-08-28 (Claude Code)

Large scope, several distinct threads. Summary here; full detail lives in
`docs/SERVER_MIGRATION_PLAN.md` and the `ericpc-ssh` skill
(`~/.claude/skills/ericpc-ssh/`) rather than duplicated in this file.

- **Server migration to ericpc, completed and cut over.** Real TLS via
  Caddy at `https://ericb.duckdns.org:8444` replaces the old plain-HTTP
  `:8090` (that port turned out to be genuinely internet-reachable despite
  being documented as never-forwarded - background scanner noise
  confirmed it, no evidence of actual unauthorized access to real
  DriveTrace endpoints, but the bearer token had been going out in
  cleartext to the open internet, which real TLS now fixes). Database
  migrated with a row-level diff, not a blind overwrite, after discovering
  a naive copy would have both lost a smoke-test session on ericpc's side
  and missed a few sessions that had drifted on the laptop's side from a
  backfill retry re-delivering data after the original snapshot. Android
  app rebuilt against the new URL, `network_security_config.xml`'s
  cleartext exception removed. Laptop's own server stopped, its Scheduled
  Task disabled (not deleted).
- **PM2 on ericpc was silently non-persistent** - discovered when both this
  app and "Our Calendar" (a separate app sharing that machine) were found
  completely down after a routine reboot. Root cause and fix in the
  `ericpc-ssh` skill; the short version is PM2's daemon was never
  registered as a real Windows Service, so it wasn't surviving reboots or
  even separate SSH sessions. Fixed with a SYSTEM-run, boot-time Scheduled
  Task. This should be treated as resolved but **worth a skeptical check**
  after any future ericpc reboot rather than assumed.
- **Android build moved to ericpc too**, specifically to reclaim disk space
  on this laptop (~10.8 GB: SDK, Gradle cache, bundled JDK, and an unused
  AVD emulator - confirmed unused by grepping this file's own history for
  "emulator"/"AVD" and finding zero real hits despite extensive real-device
  testing logged here). Real debugging required, not just a config copy -
  three separate issues, all in the `ericpc-ssh` skill: a Gradle
  wrapper-download timeout against `services.gradle.org` specific to
  ericpc's network path, this laptop's Bash tool silently mangling
  Windows backslashes when writing `local.properties` there, and (the real
  one) ericpc's debug-signing keystore resolving to `C:\Android\.android\`
  instead of the standard path, which silently signed builds with the
  wrong key until found and fixed.
- **GPS logging toggle added to Settings, off by default** (`7f540ea`).
  Motivated by battery drain: `LocationCollector.kt`'s
  `PRIORITY_HIGH_ACCURACY` + 1-second interval is one of the largest
  battery draws on Android, and a real-data check (9,000+ matched
  samples, GPS vs OBD's own Vehicle Speed PID) found 0.996 correlation
  and 1.57 km/h mean absolute difference between the two - meaning
  turning GPS off costs route/position data, not speed accuracy. New
  `LocationSettings.kt` mirrors `DisplaySettings.kt`'s
  StateFlow-over-SharedPreferences shape.
  `DriveLoggingService`'s location-collection job is only launched when
  the toggle is on; off means no `FusedLocationProviderClient` request is
  made at all, not merely a discarded result. Built on ericpc, installed
  on the real phone, confirmed installing cleanly (correct signing cert).
  **Not yet used on a real drive** - the toggle itself hasn't been
  exercised end to end, only confirmed to build/install without breaking
  anything.
- **Still open, planned for next session**: a live logcat capture while
  Eric actually connects to the car, to find a MacroDroid trigger more
  reliable than raw Bluetooth Device Connected/Disconnected for
  starting/stopping a session automatically. That trigger bounces through
  connect→drop→reconnect→Android Auto handshake→settle, which is the
  actual source of unreliable start/stop, not anything in
  `AutomationReceiver` itself (it doesn't care what triggers the Send
  Intent). Likely candidates going in: MacroDroid's Notification Listener
  trigger on Android Auto's "Connected to car" notification, or an
  Application Launched trigger on Android Auto's own package - but confirm
  against the real logcat sequence rather than assuming either works
  before actually watching what happens on a real connect.
