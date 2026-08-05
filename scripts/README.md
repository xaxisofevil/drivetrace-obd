# Analysis scripts

Isolated `.venv` (project convention, see root CLAUDE.md), not the shared
Anaconda base env, which currently has a broken numpy/pandas ABI mismatch.

## Setup

```powershell
cd projects\drivetrace-obd
python -m venv .venv
.venv\Scripts\pip install -r scripts\requirements.txt
```

## Run

```powershell
.venv\Scripts\python scripts\analyze_drive.py path\to\drivetrace_..._session-N.zip
```

Output (plots + analysis_report.md + snapshot_1s.csv) goes to
`output\<session-id>\` unless `--out` is given.

## Testing without a real drive yet

```powershell
.venv\Scripts\python scripts\_generate_test_fixture.py
.venv\Scripts\python scripts\analyze_drive.py data\raw\synthetic_test_bundle.zip --out output\synthetic_test
```

`_generate_test_fixture.py` produces a synthetic 45-minute drive (idle ->
city -> highway -> backroad -> city) matching the app's exact export schema,
used to validate `analyze_drive.py` before any real captured data exists.
Delete `data/raw/synthetic_test_bundle*` once real drive data is available;
it's just a test fixture, not real vehicle data.

## Notes on the PID matching

`analyze_drive.py` matches `canonical_name` strings from kotlin-obd-api by
keyword (see `PID_KEYWORDS`), not exact string, since those exact strings
haven't been confirmed against a real captured drive yet. If a real export's
column shows up empty in the report, check `snapshot_1s.csv` and adjust the
matching regex for that key.
