# Launches the DriveTrace ingest server. Invoked by the "DriveTraceIngestServer" Windows
# Scheduled Task on the dev laptop (see docs/HANDOFF.md) or by PM2 on ericpc (see
# docs/SERVER_MIGRATION_PLAN.md), same script either way - not meant to be run by hand
# except for debugging.
#
# $PSScriptRoot-relative rather than a hardcoded path, so this one file works unmodified on
# both machines regardless of where each one's clone lives.
Set-Location "$PSScriptRoot\.."
# Same load as server/README.md's manual instructions: token lives in the gitignored
# server\.env, never in this tracked script.
$env:DRIVETRACE_INGEST_TOKEN = (Get-Content server\.env | Select-String INGEST_TOKEN).ToString().Split('=')[1]
# Defaults to 0.0.0.0, the laptop's existing behavior, reachable on the LAN and by whatever
# the router forwards. ericpc's PM2 entry sets DRIVETRACE_HOST=127.0.0.1 instead, since there
# Caddy is the only thing that should ever reach this process directly - see
# SERVER_MIGRATION_PLAN.md's Caddy-integration section for why. Not a behavior change for the
# laptop unless that variable is explicitly set, which nothing there does today.
$bindHost = if ($env:DRIVETRACE_HOST) { $env:DRIVETRACE_HOST } else { "0.0.0.0" }
& "$PSScriptRoot\..\.venv\Scripts\uvicorn.exe" server.ingest_server:app --host $bindHost --port 8090
