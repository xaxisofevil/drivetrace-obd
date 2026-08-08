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

**Last commit:** `daeef77` "Add Delete Trip: wipes a drive from local Room
and the server" (2026-08-08)

**On the phone right now:** a debug build of `daeef77`, installed and
confirmed working (back-button navigation and Delete Trip both verified live
on-device this session).

**Server:** running locally on this machine (`localhost:8090` /
`0.0.0.0:8090`), manually started, DuckDB at `server/drivetrace.duckdb`. It
has died or gotten stuck on stale code repeatedly across sessions (machine
reboots, manual kills for DuckDB single-writer-lock queries, or just code
changes that need a restart to take effect, see Gotchas below). **Check
`curl http://localhost:8090/health` before assuming it's up and running the
code you think it's running.**

**Recently built and verified working:** live gauge cluster, PDF trip-report
export, drive-to-drive comparison, premium display skins, Settings screen
split, MacroDroid/Tasker automation (with an in-app setup walkthrough),
tapping a Logbook card to open that drive's full historical trip report,
system back button now closes the current screen instead of the whole app,
Delete Trip (wipes a drive from local Room + server).

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

## Environment gotchas (learned the hard way this session)

- **`JAVA_HOME`** doesn't persist across separate shell invocations in this
  environment. Set it explicitly every time before Gradle:
  `C:\Users\ericm\AppData\Local\Android\jdk\jdk-21.0.12+8`
- **adb over wireless debugging**: the phone's IP:port
  (`192.168.0.138:37081` as of this session) changes unpredictably. If `adb
  devices` comes up empty, ask Eric to check Settings → Developer options →
  Wireless debugging on the phone for the current value; there's no way to
  discover it from the PC side alone.
- **PowerShell's `>` redirect corrupts binary data.** Confirmed directly:
  pulling the Room SQLite file via `adb ... exec-out ... > file.db` through
  PowerShell injected a UTF-8 BOM and mangled bytes mid-file. Use a
  POSIX-shell redirect instead (Bash tool, not PowerShell) combined with
  `adb exec-out` (not plain `adb shell`) for any binary pull off the phone.
- **The ingest server does not auto-reload.** It's started as plain
  `uvicorn server.ingest_server:app --host 0.0.0.0 --port 8090` (no
  `--reload`), so editing `server/*.py` and expecting the running server to
  pick it up is wrong, it'll keep serving the old code until killed and
  restarted. Needs `DRIVETRACE_INGEST_TOKEN` set in the environment it
  starts in (Eric has the value; it's also baked into the Android build via
  `BuildConfig.INGEST_TOKEN`, check `local.properties` / the app's build
  config rather than asking him to retype it).
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
- **Not started, next up if picked up here:** nothing specific queued.
  Eric may want the Delete Trip flow exercised on a real (throwaway) drive
  rather than just the happy-path check done this session. Otherwise pull
  from "Other genuinely open items" above or `docs/DESIGN_SYSTEM.md`'s
  unbuilt ideas.
