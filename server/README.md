# DriveTrace ingest server

Best-effort live stream target for the Android app. **Not the authoritative
data path** — local Room + CSV export on the phone stays authoritative per
the blueprint's reliability rules; this is for live visibility during a
drive and to skip the manual file-transfer step when the network's up.
Cellular in a car drops out; treat anything arriving here as a bonus, not
a guarantee.

## Setup

```powershell
cd projects\drivetrace-obd
python -c "import secrets; print(secrets.token_urlsafe(32))"   # generate a token
```

Put the token in `server\.env` (gitignored, never commit it):

```
DRIVETRACE_INGEST_TOKEN=<paste the generated token>
```

The same token goes into the Android app's `local.properties` (see root
project README) so the app can authenticate its requests.

## Run

```powershell
$env:DRIVETRACE_INGEST_TOKEN = (Get-Content server\.env | Select-String INGEST_TOKEN).ToString().Split('=')[1]
.venv\Scripts\uvicorn server.ingest_server:app --host 0.0.0.0 --port 8090
```

Data lands in `server/drivetrace.duckdb` (gitignored, it's your data, not
source). Query it directly:

```powershell
.venv\Scripts\python -c "import duckdb; print(duckdb.connect('server/drivetrace.duckdb', read_only=True).execute('SELECT * FROM measurements LIMIT 10').fetchall())"
```

## Testing without the phone/router

Before exposing this to the internet at all, test over USB with `adb reverse`:

```powershell
adb reverse tcp:8090 tcp:8090
```

This maps the phone's `localhost:8090` to this PC's `localhost:8090` through
the debug cable. Point the app at `http://localhost:8090` and the whole
pipeline works with zero router or DNS changes, useful for the first parked
test before bothering with port forwarding.

## Going live (once ready to actually drive with it)

1. Forward an external port (e.g. 8090) on your router to this PC's local
   IP, port 8090, TCP.
2. Reuses your existing `ericb.duckdns.org` dynamic DNS, just a new port on
   the same hostname.
3. **This exposes a port to the public internet.** The bearer token is the
   only thing standing between "you" and "anyone who finds the open port."
   It's plain HTTP right now (no TLS), so the token itself travels in the
   clear; fine for a low-stakes personal data stream, but worth knowing.
   If you want TLS, terminate it wherever your Home Assistant reverse proxy
   already handles ericb.duckdns.org and add a route for this port.
4. Point the app's server URL at `https://ericb.duckdns.org:8090` (or
   `http://` if skipping TLS) instead of the adb-reverse localhost address.
