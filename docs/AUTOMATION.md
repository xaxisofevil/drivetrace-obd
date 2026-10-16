# Automation: start and stop a drive without opening the app

DriveTrace exposes one broadcast receiver that an automation app (MacroDroid,
Tasker, Automate, `adb`, anything that can send an explicit intent) can fire at
to start or stop a logging session. The intended use is the obvious one: the
phone connects to the car's Bluetooth, logging starts; the car's Bluetooth
disconnects, logging stops.

The design is deliberately the same shape as
[AdGuard's Tasker integration](https://adguard.com/kb/adguard-for-android/solving-problems/tasker/):
one action, a `command` extra, and a `token` extra that authenticates the
sender. If a recipe against AdGuard already works on this phone, this is the
same recipe with different strings.

**This exact recipe also lives inside the app.** Settings > Automation > "How
to set this up in MacroDroid" opens a walkthrough matching MacroDroid's own
"Send Intent" dialog field for field, with a Copy button on every value,
including the live token, so nothing has to be retyped or transcribed by eye
from this document. This page is the reference copy; that dialog is the one
meant to be followed live while building the macro.

## The contract

| Field | Value |
|---|---|
| Intent action | `com.ericbarone.drivetrace.action.AUTOMATION` |
| Target | Broadcast receiver (**not** activity, **not** service) |
| Package | `com.ericbarone.drivetrace` |
| Class | `com.ericbarone.drivetrace.service.AutomationReceiver` |
| Extra 1 name | `command` |
| Extra 1 value | `start` or `stop` (string; case and surrounding spaces are ignored) |
| Extra 2 name | `token` |
| Extra 2 value | the token shown on DriveTrace's Settings screen (string) |

Both extras are required. Every other detail of the session, which adapter to
connect to and which vehicle profile to poll, comes from the app's own saved
settings, not from the intent, so changing car or adapter never means editing a
macro.

**The package and class must both be set.** Since Android 8, a manifest-declared
receiver does not receive implicit broadcasts, so an intent carrying only the
action string is delivered to nothing at all and fails silently.

### Car identity and vehicle state (Android Auto callers)

A caller that can see the head unit (Pollux, in this project) sends more than start and
stop. Every extra is a String; all are optional, and anything unrecognised is ignored.

| Command | Extras | Effect |
|---|---|---|
| `start` | `trigger` (`gear`, `link`), `gear`, `car_make`, `car_model`, `car_year`, `car_vehicle_id`, `mode` | Starts a session. The car named by `car_*` overrides the Setup screen's vehicle and is added to the known-cars registry if new. Everything is recorded in a `SESSION_START` event. |
| `stop` | `trigger` (`gear`, `link`, `deadman`), `gear` | Stops the session. Recorded in a `SESSION_STOP` event. |
| `identity` | `car_make`, `car_model`, `car_year`, `car_vehicle_id`, `head_unit_make`, `head_unit_model`, `head_unit_sw`, `ts` | Identity that arrived after the start. Updates the registry; logged as an `IDENTITY` event, as a WARNING if the car resolves to a different profile than the one polling. |
| `vehicle` | `gear`, `parking_brake`, `driving_status`, `ts` | Logged as a `VEHICLE_STATE` event on the running session. Dropped when no session is live. |

Choosing the profile from `car_*`: an existing registry entry (matched on `car_vehicle_id`, else
make/model/year); else a make/model with a dedicated profile (Mazda, or Subaru Outback; the Mazda head unit sends a 32-character hash as the model, so any Mazda matches); else, for the
very first car ever registered, whatever the Setup screen had selected; else the generic profile,
which polls standard Mode 01 PIDs only and flags the session with an `UNASSIGNED_CAR` event.
The registry lives in SharedPreferences, not Room. Settings > Known cars lists every
entry, reassigns a car to another profile (effective from its next start) and removes it.

A session that never connects to the adapter within three minutes ends itself with
completion status `NO_OBD` (and a `NO_OBD` event), so a start fired on a day the adapter is not
in the car does not retry forever.

### Performance mode

Performance logging is a mode of the running session, not a separate car or
profile. It swaps the polled PID set between the car's normal catalog and its
smaller, faster performance catalog (currently the Mazda only; other cars have
none and ignore the commands). The connection and session carry straight
through a switch, and every change is recorded as a `PERFORMANCE_MODE` session
event plus a `CATALOG_SWITCH` event from the scheduler.

| Command | Extras | Effect |
|---|---|---|
| `performance` | `mode` = `on`, `off` or `toggle` | Switch the live session. Ignored (logged) when no session is running. |
| `start` | optional `mode` = `performance` | Start the session already in performance mode. Without it a session always starts in normal mode. |

The same switch is a toggle on the Logging screen. `command` and `mode` are both
plain String extras, like `token`.

## One-time setup, in order

### 1. Start a session from inside the app, once

Open DriveTrace, choose the vehicle and the ELM327 adapter on the Setup screen,
and press **Start logging** once (then stop it). That tap is what persists the
adapter's MAC address, and automation reuses that saved choice rather than
carrying an address of its own.

Skip this and every automated start does nothing, with this in logcat:

```
W DriveTraceAutomation: start ignored: no adapter has ever been selected on this device...
```

### 2. Copy the token

Settings screen (the gear in the Setup screen's header), **AUTOMATION** section.
Press **Copy**; the token goes to the
clipboard ready to paste into the macro. It is generated on this device the
first time that screen is opened and does not change afterwards. Clearing the
app's data regenerates it, at which point every macro holding the old value
stops working and has to be re-pasted.

### 3. Let DriveTrace start a foreground service from the background

Since Android 12, an app in the background may not start a foreground service
unless it is exempt, and drive logging is a foreground service. Grant the
exemption:

**Settings > Apps > DriveTrace > Battery > Unrestricted** (wording varies by
manufacturer: "Don't optimise", "Allow background activity", "Unrestricted").

Without this, an automated start is refused by the system, and logcat says so:

```
E DriveTraceAutomation: start failed: the system refused to start the logging service from the background...
```

Stopping is unaffected, since by then the service is already in the foreground.

### 4. Let it record GPS

**Settings > Apps > DriveTrace > Permissions > Location > Allow all the time.**

A foreground service that starts while the app is in the background has its
"while in use" permissions withheld, and location is one of them. OBD data logs
normally either way, but without this the drive is recorded with an empty GPS
track, which the analysis pipeline needs. The receiver warns when it starts a
session without this permission:

```
W DriveTraceAutomation: Starting without ACCESS_BACKGROUND_LOCATION...
```

## MacroDroid recipe

Two macros: one to start, one to stop. They are identical apart from the trigger
and the `command` value.

### Macro 1: start logging on car Bluetooth connect

**Trigger:** Connectivity > Bluetooth > *Device Connected*, and pick the car's
Bluetooth from the list.

**Action:** Applications > **Send Intent**:

| MacroDroid field | What to enter |
|---|---|
| Target | `Broadcast` |
| Action | `com.ericbarone.drivetrace.action.AUTOMATION` |
| Data (URI) | leave empty |
| Mime Type | leave empty |
| Package | `com.ericbarone.drivetrace` |
| Class | `com.ericbarone.drivetrace.service.AutomationReceiver` |
| Extra 1 name | `command` |
| Extra 1 value | `start` |
| Extra 2 name | `token` |
| Extra 2 value | paste the token from the Settings screen |

Leave the extras as plain strings; do not mark them as integers or booleans.

### Macro 2: stop logging on car Bluetooth disconnect

**Trigger:** Connectivity > Bluetooth > *Device Disconnected*, same device.

**Action:** the identical Send Intent action, with **Extra 1 value** set to
`stop` instead of `start`. Everything else, including the token, is the same.

A worked example of the two, end to end:

```
Macro "DriveTrace start"
  Trigger: Bluetooth Device Connected  ->  Mazda6
  Action:  Send Intent
             Broadcast
             com.ericbarone.drivetrace.action.AUTOMATION
             com.ericbarone.drivetrace
             com.ericbarone.drivetrace.service.AutomationReceiver
             command = start
             token   = 4f9c2ab7013e5d86a1b2      (yours will differ)

Macro "DriveTrace stop"
  Trigger: Bluetooth Device Disconnected  ->  Mazda6
  Action:  Send Intent
             Broadcast
             com.ericbarone.drivetrace.action.AUTOMATION
             com.ericbarone.drivetrace
             com.ericbarone.drivetrace.service.AutomationReceiver
             command = stop
             token   = 4f9c2ab7013e5d86a1b2
```

## Tasker equivalent

Task > Add > System > **Send Intent**:

| Tasker field | What to enter |
|---|---|
| Action | `com.ericbarone.drivetrace.action.AUTOMATION` |
| Cat | `None` |
| Extra | `command:start` (or `command:stop`) |
| Extra | `token:<your token>` |
| Package | `com.ericbarone.drivetrace` |
| Class | `com.ericbarone.drivetrace.service.AutomationReceiver` |
| Target | `Broadcast Receiver` |

## Testing it from a PC

With the phone on USB debugging, this is the same broadcast a macro sends:

```
adb shell am broadcast \
  -a com.ericbarone.drivetrace.action.AUTOMATION \
  -n com.ericbarone.drivetrace/.service.AutomationReceiver \
  --es command start \
  --es token <your token>
```

`--es command stop` for the other direction. A delivered broadcast prints
`Broadcast completed: result=0`; that means the receiver ran, not that it acted,
so watch what it decided:

```
adb logcat -s DriveTraceAutomation
```

Note that a broadcast sent from `adb` arrives while the shell is in the
foreground, which can mask the background-start restriction step 3 covers. The
honest test is the real one: lock the phone, connect the car's Bluetooth.

## What the log lines mean

Everything the receiver does is logged under the tag `DriveTraceAutomation`, and
nothing else in the app uses that tag.

| Line | What happened |
|---|---|
| `start accepted, dispatched to DriveLoggingService.` | Working as intended. |
| `Rejected "start": no 'token' extra.` | The macro is missing the second extra. |
| `Rejected "start": 'token' does not match...` | Wrong or stale token. Re-copy it from the Settings screen. Neither the sent nor the expected token is ever logged. |
| `Authenticated but ignored: unknown command "..."` | The `command` extra is something other than `start`, `stop`, `performance`, `vehicle` or `identity`. |
| `performance ignored: 'mode' must be on, off or toggle` | The `mode` extra is missing or misspelled. |
| `performance "on" ignored: no session is running.` | Nothing is logging yet, so there is nothing to switch. Use `start` with `mode=performance` instead. |
| `start ignored: no adapter has ever been selected...` | Step 1 above was skipped. |
| `start ignored: a session is already running` | A drive is already being logged; the second start is a no-op, not an error. |
| `stop ignored: no session is running.` | The stop macro fired when nothing was logging, e.g. the car disconnected on a day the drive was never started. Deliberate: nothing is sent to the service in this case. |
| `start failed: the system refused to start the logging service...` | Step 3 above. |
| `Starting without ACCESS_BACKGROUND_LOCATION` | Step 4 above. The session runs; GPS may be empty. |

If nothing appears under that tag at all, the broadcast never reached the app.
Check the package and class fields in the macro, and that Target is set to
broadcast rather than activity or service.

## Security notes

The receiver is exported, which is what makes it reachable from another app and
is also why the token exists. Any app on the phone can send this broadcast; only
one that knows the token can start or stop logging with it. There is no way to
restrict delivery to MacroDroid specifically through the manifest, since a
signature-level permission would exclude every third-party app including the one
that needs in.

What an attacker with the token gets is the ability to start or stop drive
logging on this phone. The token is not a credential for the ingest server or
for anything off-device, and the intent carries nothing about the vehicle or the
adapter.

## Alternative worth knowing about

Android exempts an app from the background-start restriction when it receives a
Bluetooth broadcast that requires `BLUETOOTH_CONNECT`, so DriveTrace could
listen for the car's `ACL_CONNECTED` itself and skip both the automation app and
step 3 entirely. That is a different feature from this one, which exists so any
trigger a general automation app can express, not just Bluetooth, can drive a
session. Not built.
