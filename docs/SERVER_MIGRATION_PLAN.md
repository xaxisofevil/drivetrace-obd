# DriveTrace ingest server migration plan: laptop to ericpc

## Update: routing through Caddy, not a bare port-forward

ericpc already runs Caddy as a reverse proxy for another app ("Our
Calendar"), with real TLS via Let's Encrypt (DNS-01 challenge through the
`caddy-dns/duckdns` plugin, a custom-built Caddy binary with that module
compiled in). Confirmed directly against that app's own `deploy/Caddyfile`
and `ARCHITECTURE.md` rather than assumed. This changes several specifics
below, superseding the original guesses in Sections 3-6:

- **DriveTrace's uvicorn binds `127.0.0.1:8090`, not `0.0.0.0:8090`.** Only
  Caddy talks to it directly; nothing external reaches port 8090 at all,
  not even via router forward.
- **No router forward for port 8090, ever.** The only new forward needed is
  for DriveTrace's new external port (see below), matching the existing
  pattern where each service on this machine gets its own external port on
  the same hostname, fronted by its own Caddy site block.
- **New external port: 8444** (adjacent to the existing dashboard's 8443,
  confirmed free). **New app URL: `https://ericb.duckdns.org:8444`**, HTTPS
  instead of today's plain HTTP, a real upgrade since the bearer token
  currently goes out in cleartext on every request. Requires an APK
  rebuild either way (compiled constant), so worth doing at the same time.
- **Caddyfile block** (add to ericpc's existing `deploy/Caddyfile` as a new,
  separate site block, not nested inside the existing one):
  ```caddyfile
  ericb.duckdns.org:8444 {
      tls {
          dns duckdns {env.DUCKDNS_API_TOKEN}
      }

      handle {
          reverse_proxy 127.0.0.1:8090
      }

      encode gzip
  }
  ```
  `DUCKDNS_API_TOKEN` is already set in the environment for the existing
  Caddy process, no new secret needed.
- **Process management, open choice**: either add DriveTrace's uvicorn as a
  third entry in the existing `deploy/ecosystem.config.cjs` (PM2), matching
  how the other app's backend and Caddy are both managed, or keep it on its
  own independent Windows Scheduled Task as planned in Section 3 below,
  fully separate from that repo. Both are consistent with "Caddy fronts
  everything," this is only about which supervisor restarts uvicorn on
  crash/reboot. Eric's call, not resolved here.
- **No special timeout, header-forwarding, or client-IP config needed.**
  Confirmed against the existing Caddyfile's own minimalism: no custom
  timeouts anywhere in it today, `reverse_proxy` passes all headers
  (including `Authorization`) through untouched by default, and nothing in
  the existing setup does anything IP-sensitive that DriveTrace would need
  to account for.
- **DriveTrace-side follow-ups this creates**, not yet done: update
  `local.properties`'s `ingest.baseUrl` to `https://ericb.duckdns.org:8444`,
  and remove (or at least reconsider) `network_security_config.xml`'s
  cleartext-traffic exception for the old hostname, since everything moves
  to real TLS.

Sections 3-6 below still apply for the data migration, repo/venv setup, and
validation steps, just substitute `127.0.0.1:8090` for the bind address and
`8444`/`https://ericb.duckdns.org:8444` everywhere `8090` and the old plain
HTTP URL appear. The port-forward-retarget language in the original cutover
section assumed retargeting an existing 8090 forward; per the earlier
finding in this doc, 8090 was never actually forwarded, so there's nothing
to retarget, only a fresh 8444 forward to add.

A plan, not an implementation. Written before any migration work started, so it
could be checked against live system state rather than assumptions. Update or
supersede this doc once the migration actually happens; don't leave it
describing a move that already occurred as still-pending.

## Verified ground truth (don't re-derive)

- **Server code**: `server/ingest_server.py` (FastAPI + DuckDB) and
  `server/analysis_worker.py`, run via `server/run_server.ps1`. That script
  `Set-Location`s to the repo root, reads the token out of `server\.env`
  (`DRIVETRACE_INGEST_TOKEN=<value>`, single line, gitignored), and launches
  `.venv\Scripts\uvicorn.exe server.ingest_server:app --host 0.0.0.0 --port 8090`.
- **Current scheduled task**, confirmed live via
  `schtasks /Query /TN "DriveTraceIngestServer" /V`: Task To Run =
  `powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File
  C:\Users\ericm\projects\drivetrace-obd\server\run_server.ps1`, Run As User
  `ericm`, Schedule Type **One Time Only** (`/Run`-triggered, no
  `ONLOGON`/`ONSTART` trigger, since creating one was Access Denied in the
  sandbox that session ran in, so this doesn't survive a reboot today).
- **Known reliability gotcha to carry over**: `schtasks /End` does **not**
  reliably kill the process tree (uvicorn.exe -> python.exe -> a second
  anaconda python.exe survive as orphans still bound to the port). Documented
  kill command:
  ```
  powershell -Command "Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%uvicorn%'\" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
  ```
  This matters directly for data migration: "the task looks stopped" is not
  proof the DuckDB file is unlocked.
- **DB path**: `server/ingest_server.py` line 32:
  `DB_PATH = Path(os.environ.get("DRIVETRACE_DB_PATH", Path(__file__).resolve().parent / "drivetrace.duckdb"))`,
  overridable via env var, defaults to `server/drivetrace.duckdb` next to the
  script. No `.wal` file present as of this writing (clean, checkpointed
  state), worth preserving going into the migration.
- **`/health` endpoint** returns `{"status": "ok", "sessions": <count>,
  "db_path": <str>}`. Ready-made verification/reachability probe for both "is
  DuckDB intact" (session count) and "is the process actually up" (local and
  external).
- **Secrets**: `server/.env` is gitignored. It will not travel via
  `git clone`/`git pull`, has to be copied out-of-band.
- **Android config, confirmed in `app/build.gradle.kts` lines 27-28**: both
  `ingest.token` and `ingest.baseUrl` from `local.properties` are baked into
  `BuildConfig` at build time, consumed directly in `StreamingClient`,
  `DriveLoggingService`, `BackfillRetryWorker`, `TripReportScreen`,
  `DriveNote.kt`. **Both require a rebuild and reinstall to change, not just
  the token.**
- **Current `local.properties`**: `ingest.baseUrl=http://ericb.duckdns.org:8090`.
  `app/src/main/res/xml/network_security_config.xml` whitelists cleartext
  traffic for exactly `ericb.duckdns.org`. If the hostname the app talks to
  ever changes, that file needs a matching edit too, or cleartext HTTP to the
  new host is blocked outright.
- **The reachability bug this migration is partly fixing**: the router
  forwards 8123 (Home Assistant) but has never forwarded 8090. A captured
  on-device error showed a 10-second timeout trying to reach the server's
  public hostname on port 8090, exactly the signature of a port that's simply
  never forwarded, not a server-side rejection. The server was only ever
  proven reachable from localhost/LAN, never from outside. That exact class
  of "looked fine locally, silently broken from outside" failure is why
  external validation (see below) is non-negotiable this time.
- **Repo has a real GitHub remote**: `https://github.com/xaxisofevil/drivetrace-obd.git`,
  branch `master`, clean. `git pull` is a legitimate, already-available
  deploy mechanism.
- **Python environment**: `requirements.txt` (pandas, numpy, matplotlib,
  tabulate, pyserial, pytest, fastapi, `uvicorn[standard]`, duckdb), local
  `.venv` running Python 3.12.7.
- **No existing DB-migration tooling** anywhere in the repo. This has to be a
  direct file operation, verified by query, not something to build a script
  for.
- **`ericpc` reachability and OS: now confirmed directly, resolved since the
  first draft of this plan.** LAN IP is `192.168.0.129` (`ericpc` itself
  doesn't resolve via the router's DNS or mDNS from the laptop; use the IP,
  or set up a hosts-file entry or local DNS record if the name is wanted).
  ICMP ping to that IP fails, but that's Windows Firewall's default ICMP
  block, not a real reachability problem, confirmed by `Test-NetConnection
  -Port 22` succeeding and a full SSH session completing. OS confirmed via a
  live SSH session: **Windows 11 Pro, build 22631**. SSH key-based auth
  (ed25519, no passphrase) from the laptop's `ericm` account to ericpc's
  `ericm` account is working as of this update, useful for Section 3's
  remote setup steps and for any future AI-assisted work on ericpc directly.
  Getting SSH working took real troubleshooting, documented here since it's
  a real gotcha for anyone repeating this on another machine: OpenSSH on
  Windows requires admin accounts' keys in the single shared
  `C:\ProgramData\ssh\administrators_authorized_keys` (not the per-user
  `~/.ssh/authorized_keys` Linux/Mac use), that file needs its ACL locked to
  only `SYSTEM` and `Administrators` (`icacls ... /inheritance:r /grant
  "Administrators:F" /grant "SYSTEM:F"`) or sshd silently rejects every key
  in it, and writing that file with PowerShell's `Add-Content` can leave a
  trailing CRLF that also causes a silent, generic-looking rejection versus
  a bare `\n`. Neither failure mode produces a clear client-side error,
  both look identical to "wrong key," `LogLevel VERBOSE` in `sshd_config`
  plus the `OpenSSH/Operational` Windows Event Log channel is what actually
  surfaces the real reason.

## Open design question (Eric decides, plan supports either)

**Does all server-side work move to ericpc permanently, or does dev/test
stay on the laptop with a deploy step to ericpc?**

### Option A: full migration, laptop never runs the server again

Laptop keeps only the git-tracked source; ericpc is the sole place `server/`
ever executes, for development and production both. Any server-side change
would need to happen either by working directly on ericpc, or by editing on
the laptop and pushing/copying to ericpc before every single test, even
mid-debugging.

- Pro: one source of truth for what's actually running, zero risk of laptop
  and ericpc code drifting apart, no deploy step to forget.
- Con: breaks the workflow this whole project already runs on. Server-side
  debugging has consistently meant editing and restarting in the same
  session, often several times an hour while root-causing something live.
  Moving that loop onto a second physical machine means either running Claude
  Code sessions directly on ericpc (not set up today, feasibility unconfirmed
  without knowing its OS/tooling) or copying every single-line edit over by
  hand before it can be tested.

### Option B: server relocates, development stays on the laptop with a deploy step (recommended)

Laptop remains the dev environment, where sessions already edit `server/*.py`
and iterate. ericpc runs only the always-on production instance. Deploy means
`git pull` on ericpc plus a scheduled-task restart, a deliberate, cheap,
few-command act.

- Pro: no change to the pattern that's produced every fix so far. The laptop
  can keep a local `.venv` and a disposable test DB (or `DRIVETRACE_DB_PATH`
  pointed at a throwaway file) for iterating without touching production data
  at all, arguably an improvement over today, where debugging happens
  directly against the real production DB with real driving data.
- Con, named honestly: code on ericpc can silently lag behind the laptop's
  working tree if a deploy step is forgotten after a fix. Same shape as the
  already-documented "server running stale code" gotcha, needs a deliberate
  habit (check `git log` on ericpc before trusting it's current), not a
  fire-and-forget assumption.

**Recommendation: Option B.** The laptop is where the actual work happens
today; Option A requires either giving up that workflow or standing up
remote dev access to ericpc that doesn't exist yet and whose feasibility is
unconfirmed. Option B's real cost, deploy-drift risk, is small and
manageable. This is Eric's call, not a default to silently apply, but
Sections 3 and 9 below assume it unless told otherwise.

## 1. Pre-migration checklist

Confirm all of these before touching data or the scheduled task:

- [x] **ericpc's OS.** Confirmed via a live SSH session: Windows 11 Pro,
  build 22631. PowerShell and Task Scheduler are available as on the laptop;
  Python still needs confirming/installing (see below).
- [x] **ericpc's actual reachability from the laptop's LAN.** Confirmed at
  `192.168.0.129`. The name `ericpc` itself does not resolve via the
  router's DNS or mDNS from the laptop, use the IP directly, or set up a
  hosts-file entry or local DNS record first if the name is wanted for
  `local.properties`/documentation purposes. `Test-NetConnection
  <ip> -Port 8090` still needs checking once something is actually listening
  there (nothing is yet, this only confirms port 22/SSH so far).
- [ ] **SSH access from the laptop, for driving the remaining setup steps
  remotely.** Working as of this update (ed25519 key, no passphrase, `ericm`
  account both sides). Leave this checkbox open as a reminder that the setup
  steps below (Section 3) can now genuinely be run remotely via `ssh
  192.168.0.129 "<command>"` rather than requiring Eric to type everything
  at the machine directly, worth using.
- [ ] **Git and Python tooling on ericpc**: `git`, a Python 3.12-ish
  interpreter, ability to create a venv and `pip install -r requirements.txt`.
- [ ] **Enough free disk space on ericpc** for the repo, a venv, and the
  DuckDB file (currently ~11 MB, growing, trivial today).
- [ ] **The router-level port-forward retarget is Eric's own step**, not
  planned here. It slots in after Section 3 (server confirmed healthy locally
  on ericpc) and before Section 6 (external validation), see Section 5.
- [ ] **Decide Option A vs. B** before finalizing Sections 3 and 9.
- [ ] **A quiet window on the laptop's server** to do the data copy, no
  active drive should be uploading while the DB file is copied, since DuckDB
  is single-writer and a copy taken mid-write risks an inconsistent snapshot.

## 2. Data migration: `server/drivetrace.duckdb`

This is the step where real, irreplaceable driving data can be lost.
Precision matters more than speed.

1. **Capture a pre-migration fingerprint** while the laptop's copy is still
   the only copy:
   ```powershell
   .venv\Scripts\python -c "import duckdb; c = duckdb.connect('server/drivetrace.duckdb', read_only=True); print('sessions', c.execute('SELECT COUNT(*) FROM sessions').fetchone()); print('measurements', c.execute('SELECT COUNT(*) FROM measurements').fetchone()); print('locations', c.execute('SELECT COUNT(*) FROM locations').fetchone()); print('events', c.execute('SELECT COUNT(*) FROM events').fetchone())"
   ```
   Record all four counts, and also
   `SELECT session_id, COUNT(*), MIN(elapsed_ns), MAX(elapsed_ns) FROM measurements GROUP BY session_id`,
   a per-session count-plus-span check, meaningfully stronger than a bare
   total row count, and cheap given the data volume involved.
2. **Stop the laptop's server completely**, using the confirmed-necessary
   kill sequence, not just `schtasks /End`:
   ```
   schtasks /End /TN "DriveTraceIngestServer"
   powershell -Command "Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%uvicorn%'\" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
   ```
   Verify with the bare `Get-CimInstance` line alone, no processes listed
   means actually stopped.
3. **Confirm no `.wal` file exists** (`server/drivetrace.duckdb.wal`) after
   the stop. DuckDB checkpoints its WAL into the main file on a clean
   shutdown; if one exists after stopping, that implies uncommitted state,
   worth investigating before proceeding rather than copying past it.
4. **Copy, don't move, and hash both sides.** Copy `server/drivetrace.duckdb`
   to ericpc's equivalent path (`server/drivetrace.duckdb` in its clone,
   matching the code's default `DB_PATH`), a plain file copy over the LAN is
   fine, no need for anything fancier. Immediately after, hash both files:
   ```powershell
   Get-FileHash server\drivetrace.duckdb -Algorithm SHA256
   ```
   A byte-identical hash is a stronger guarantee than any query-based check
   and costs nothing extra given the file size.
5. **Re-run the exact same count/span queries against the copy on ericpc**
   and diff against the fingerprint from step 1. All four table counts and
   every session's `(count, min, max)` triple must match exactly. Any
   mismatch, even one row, stop and investigate before doing anything else.
6. **Do not delete or overwrite the laptop's copy yet.** Leave it untouched
   until Section 6's real-drive end-to-end validation has passed. It's the
   rollback (Section 8) and, depending on the Section 7 decision, may stay
   deliberately in place as a fallback copy rather than being deleted at all.

## 3. Standing up the server process on ericpc

Assumes Windows, since that's the only environment actually verified in this
repo. If ericpc turns out not to be Windows, redo this section's mechanics
(not its structure) for the real OS before executing anything.

1. **Get the code onto ericpc.** `git clone
   https://github.com/xaxisofevil/drivetrace-obd.git`, the repo's real,
   already-existing remote, no new hosting setup needed. This gets the
   server code but not `server/.env` or `server/drivetrace.duckdb`, both
   gitignored, handled by step 2 and Section 2 respectively, never by git.
2. **Copy `server/.env` from the laptop, verbatim, do not regenerate it.**
   The token is baked into every existing app install's
   `BuildConfig.INGEST_TOKEN`; a freshly-generated token would silently break
   every device's ability to authenticate until each one is rebuilt and
   reinstalled. Copy the actual file, LAN transfer, USB drive, or `scp` if
   available, so the byte content matches exactly.
3. **Set up the Python environment**: create a venv (`python -m venv .venv`)
   and `pip install -r requirements.txt`, matching the laptop's Python
   3.12-ish interpreter as closely as available.
4. **Confirm `run_server.ps1` works unmodified.** It only does a relative
   `Set-Location` to the repo root and reads `server\.env`, no laptop-specific
   absolute paths beyond the repo's own clone location, so it should work
   as-is once the clone path and venv exist.
5. **Manually run once** and confirm `curl http://localhost:8090/health` on
   ericpc itself returns the expected session count matching Section 2's
   verified figure, proving the copied DB and token are both correctly wired
   before wrapping it in a scheduled task.
6. **Create the equivalent Scheduled Task**:
   ```
   schtasks /Create /TN "DriveTraceIngestServer" /TR "powershell.exe -WindowStyle Hidden -ExecutionPolicy Bypass -File <ericpc-repo-path>\server\run_server.ps1" /SC ONSTART /RU <ericpc-account>
   ```
   One deliberate improvement over the laptop's setup, worth doing here
   rather than carrying the gap forward: the laptop's task has no
   `ONLOGON`/`ONSTART` trigger (creation was blocked in whatever sandboxed
   session created it), so it doesn't survive a reboot. ericpc is meant to be
   always-on, so it should get a real `ONSTART` (or `ONLOGON`, if ericpc
   auto-logs-in a user on boot) trigger from the start. If that also hits a
   permissions wall, surface it back to Eric rather than silently falling
   back to "one time only" the way the laptop did.
7. **Stop the manual run, start it via the task, and re-check `/health`**,
   confirming the task itself, not just a manual shell invocation, launches
   correctly.
8. **Carry forward the documented restart gotcha.** Whoever operates ericpc
   going forward needs the same "`schtasks /End` doesn't reliably kill the
   orphan uvicorn/python chain" awareness already documented for the laptop;
   verify this is or isn't also true on ericpc before assuming a restart
   worked.

## 4. Android app config: what changes, what doesn't

- **If the router's port-forward is simply retargeted** (same public
  hostname `ericb.duckdns.org`, same external port 8090, only the
  destination LAN IP changes to ericpc's): no app change and no rebuild
  needed at all. `local.properties`, `BuildConfig.INGEST_BASE_URL`, and
  `network_security_config.xml`'s cleartext whitelist all stay byte-identical.
  This is the whole reason to prefer this option among the router-side
  choices: it's the only path that avoids touching the app at all.
- **If Eric instead changes the hostname or port the app should hit**: both
  a `local.properties` edit and a full rebuild-and-reinstall on every device
  are required, exactly like the token would be, since `INGEST_BASE_URL` is
  compiled into `BuildConfig`. `network_security_config.xml`'s domain entry
  needs a matching edit too in that case, or cleartext HTTP to the new host
  is blocked outright.
- **The token itself is unaffected either way** as long as Section 3 step 2
  copies the exact `server/.env` content rather than regenerating it.
- **Net recommendation**: keep the public hostname and port exactly as-is
  (`ericb.duckdns.org:8090`) and let the router-level retarget be the only
  thing that changes, so this whole section is a non-event for the app.

## 5. Cutover sequence

Goal: minimize the window where neither machine is serving requests, and use
the already-built `BackfillRetryWorker` correctly rather than fighting it.

1. Complete Sections 1-3: ericpc has the code, the copied `.env`, the
   verified copy of the DB, and a working scheduled task, confirmed healthy
   via `/health` on ericpc itself (LAN-local check, not yet external).
2. **Confirm LAN reachability from a third point**, ideally the phone on
   home Wi-Fi hitting ericpc's LAN IP directly, before any router change.
   This isolates "does ericpc actually serve requests to another device on
   the LAN" from "is the router forwarding correctly," so a failure at the
   external-check stage can be attributed correctly.
3. **Stop the laptop's server** (Section 2 step 2's kill sequence). From this
   point until step 5, nothing is listening on port 8090 anywhere reachable,
   by design; keep this window as short as practical.
4. **Eric flips the router's port-forward** to point 8090 at ericpc's LAN IP
   (his step, not planned here). Safe to do slightly early or slightly late
   relative to step 3: a forward pointing at a machine that isn't listening
   yet just produces a clean "connection refused" instead of today's silent
   timeout, a more diagnosable failure state than what exists today, not a
   worse one.
5. **Confirm the scheduled task on ericpc is running and healthy**
   (`schtasks /Query` plus `/health` locally), the moment external traffic
   starts having somewhere real to land.
6. **What the backfill-retry worker does during this window**: any drive in
   progress or just-stopped during the dead window has its live stream and
   backfill fail, expected and harmless per this project's own reliability
   rule (local Room is always authoritative, streaming/backfill/analysis
   failures never lose data). `BackfillRetryWorker` is already built to retry
   once a network constraint is satisfied, triggered on next app launch, on a
   failed live backfill, or via the History screen's manual retry. It doesn't
   know or care that the server moved to a new machine, it just retries the
   same URL until something answers there again, automatically, the moment
   step 5 completes and the router forward is live. No app-side action is
   needed for this recovery beyond leaving the phone with normal connectivity
   at some point after the cutover.
7. Proceed to Section 6's validation before considering the cutover complete.

## 6. Post-migration validation

The step the current setup skipped. Do not skip it again.

1. **Local, on ericpc**: `curl http://localhost:8090/health` returns the
   expected session count.
2. **LAN, from a second device** (the phone on home Wi-Fi, or the laptop):
   `curl http://<ericpc-lan-ip>:8090/health` succeeds.
3. **External, from outside the home network, the check that was never
   actually done for the current setup.** Use cellular data on the phone
   (Wi-Fi off) to hit `http://ericb.duckdns.org:8090/health` directly. This
   must return a real response, not just "no error yet": a hung, timing-out
   request looks identical to success right up until it doesn't, exactly the
   failure this whole migration exists to fix. Confirm the returned session
   count matches what Section 2 verified, not just that something answered.
4. **A real drive, end to end, against the new location**: start a genuine
   (even short) drive with the app pointed at the unchanged
   `ericb.duckdns.org:8090` URL, confirm the live stream posts succeed during
   the drive, Stop triggers backfill and analysis against ericpc, and the
   trip report shows a server-computed MPG figure, not just the on-device
   fallback (a fallback-only result on Session Complete is the exact "looks
   fine, actually still broken" signature to watch for, since the on-device
   estimate renders either way). Confirm afterward on ericpc that
   session/measurement counts increased by exactly this new drive's data,
   via the same query pattern from Section 2.
5. Only after all four pass, consider the migration validated.

## 7. What happens to the laptop's copy afterward

- **If Option B (recommended)**: the laptop keeps its git clone for ongoing
  dev, but should not keep running the ingest server or a stale production
  DB copy long-term. After Section 6 passes, rename (don't delete outright)
  the laptop's `server/drivetrace.duckdb` to something like
  `drivetrace.duckdb.pre-migration-backup` and leave it in place as a cold
  backup for a reasonable retention window, this data has already proven
  itself worth being careful with. For day-to-day dev going forward, point
  `DRIVETRACE_DB_PATH` at a fresh, disposable local file so local testing
  never touches real driving data. Disable or delete the laptop's scheduled
  task entirely once ericpc is confirmed stable, so there's no ambiguity
  about which machine is authoritative.
- **If Option A**: same backup-then-rename treatment for the DB copy, but the
  laptop's scheduled task and `.venv` can be removed outright once ericpc is
  confirmed stable and dev has actually relocated there.
- **Either way**: never leave two machines both able to write to a copy of
  `drivetrace.duckdb` that both think is authoritative. That's the exact
  structural risk the current single-server design avoids and DuckDB's
  single-writer model doesn't defend against on its own.

## 8. Rollback plan

Built so rollback is cheap at every stage, since nothing destructive happens
until Section 6 passes:

- **If anything fails before Section 5 (cutover)**: nothing on the laptop
  has been touched destructively yet. Just don't proceed, the laptop's
  server restarts exactly as it works today, no undo needed.
- **If the cutover happens and Section 6's validation fails**: revert the
  router's port-forward back to the laptop's LAN IP, restart the laptop's
  scheduled task, confirm `/health` locally and re-run Section 6's external
  check against the laptop as it stood before. Because Section 4 kept
  `local.properties`/`BuildConfig` unchanged throughout, the app needs no
  rebuild to roll back, it starts reaching the laptop again the moment the
  forward points back there, and `BackfillRetryWorker` recovers anything
  that failed during the failed-migration window the same way it recovers
  from any other outage.
- **If data corruption is discovered post-copy** (Section 2's hash/count
  check should have caught this, but as a backstop): the laptop's original
  `drivetrace.duckdb` is untouched until Section 7's rename, so re-copying
  from the laptop and re-verifying is always available as long as Section 7
  hasn't happened yet.
- **Worst case, data actually diverges** (a drive gets backfilled against
  ericpc post-cutover, then a rollback to the laptop happens, stranding that
  drive's data on ericpc only): because backfill is delete-then-insert per
  session, a session backfilled against ericpc after cutover and never
  against the laptop simply doesn't exist on the laptop's copy, it isn't
  corrupted, just absent there. Recovering it means copying that one
  session's rows back from ericpc's copy before decommissioning it, not a
  full re-migration. Keeping both copies until Section 6 is fully green
  avoids ever needing this path.

## 9. Documentation updates needed afterward

All three currently describe a laptop-hosted server:

- **`docs/HANDOFF.md`**: the "Current state" -> "Server" paragraph needs to
  state where it actually runs now (ericpc, with its hostname/IP and how to
  reach it for admin purposes), whether the laptop still runs anything
  server-related, and whether the restart/kill commands still apply as-is on
  ericpc. Should get a new dated Session log entry documenting the migration
  itself, per the file's own convention of appending rather than rewriting.
- **`docs/ARCHITECTURE.md`**: the `server/` line in the Components tree reads
  "Home PC ingest server," accurate in spirit but worth a light pass to make
  sure nothing implies "the same machine you're developing on."
- **`server/README.md`**: the "Going live" section's port-forwarding step is
  currently laptop-centric phrasing ("this PC") that becomes actively wrong
  once "this PC" isn't the laptop. Needs to distinguish the dev/test context
  from the "Going live" context, which describes ericpc specifically. If
  Option B is chosen, this file is also the natural place to document the
  deploy step (`git pull` plus task restart on ericpc) so it isn't tribal
  knowledge.
- **New, worth considering rather than assumed**: if Option B is chosen, a
  short "how to deploy a server change" note, in `server/README.md` or
  `docs/HANDOFF.md`'s gotchas, covering the `git pull`-then-restart sequence
  and the "verify ericpc is running current code" check. Direct mitigation
  for Option B's named risk (deploy drift), doesn't exist anywhere today
  because it's never been needed before.

## Critical files for implementation

- `server/run_server.ps1`
- `server/ingest_server.py`
- `server/.env`
- `server/drivetrace.duckdb`
- `local.properties`
- `docs/HANDOFF.md`
