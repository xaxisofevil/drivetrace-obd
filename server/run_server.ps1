# Launches the DriveTrace ingest server. Invoked by the "DriveTraceIngestServer" Windows
# Scheduled Task (see docs/HANDOFF.md), not meant to be run by hand except for debugging -
# the scheduled task is what makes this independent of any particular terminal or Claude
# Code session being open.
Set-Location "C:\Users\ericm\projects\drivetrace-obd"
# Same load as server/README.md's manual instructions: token lives in the gitignored
# server\.env, never in this tracked script.
$env:DRIVETRACE_INGEST_TOKEN = (Get-Content server\.env | Select-String INGEST_TOKEN).ToString().Split('=')[1]
& "C:\Users\ericm\projects\drivetrace-obd\.venv\Scripts\uvicorn.exe" server.ingest_server:app --host 0.0.0.0 --port 8090
