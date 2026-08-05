package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt
import com.github.eltonvs.obd.command.calculatePercentage

/**
 * Replacements for kotlin-obd-api command classes confirmed buggy: their handlers call
 * bytesToInt(response.bufferedValue) without passing bytesToProcess, which defaults to -1 and
 * skips the .take() bound entirely, folding every byte in the whole cleaned response into one
 * number instead of just the PID's real data bytes. Confirmed directly: RPM read back as 3.8
 * trillion, module voltage as tens of billions, distance-since-clear as hundreds of trillions,
 * on a real drive. RPMCommand itself is already fixed upstream (unreleased, commit
 * 30014eb6e8cd35334ba8f7ea627500f6b1942ff5, see build.gradle.kts), these five are not.
 *
 * Same tag/name/mode/pid/unit as the library originals so CSV schema and analysis-script
 * keyword matching don't need to change; only the arithmetic is fixed.
 */

class SafeModuleVoltageCommand : ObdCommand() {
    override val tag = "CONTROL_MODULE_VOLTAGE"
    override val name = "Control Module Power Supply"
    override val mode = "01"
    override val pid = "42"
    override val defaultUnit = "V"
    override val handler = { response: ObdRawResponse ->
        "%.2f".format(bytesToInt(response.bufferedValue, bytesToProcess = 2) / 1000f)
    }
}

class SafeMassAirFlowCommand : ObdCommand() {
    override val tag = "MAF"
    override val name = "Mass Air Flow"
    override val mode = "01"
    override val pid = "10"
    override val defaultUnit = "g/s"
    override val handler = { response: ObdRawResponse ->
        "%.2f".format(bytesToInt(response.bufferedValue, bytesToProcess = 2) / 100f)
    }
}

class SafeFuelConsumptionRateCommand : ObdCommand() {
    override val tag = "FUEL_CONSUMPTION_RATE"
    override val name = "Fuel Consumption Rate"
    override val mode = "01"
    override val pid = "5E"
    override val defaultUnit = "L/h"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 2) * 0.05)
    }
}

class SafeFuelRailPressureCommand : ObdCommand() {
    override val tag = "FUEL_RAIL_PRESSURE"
    override val name = "Fuel Rail Pressure"
    override val mode = "01"
    override val pid = "22"
    override val defaultUnit = "kPa"
    override val handler = { response: ObdRawResponse ->
        "%.3f".format(bytesToInt(response.bufferedValue, bytesToProcess = 2) * 0.079)
    }
}

class SafeDistanceSinceCodesClearedCommand : ObdCommand() {
    override val tag = "DISTANCE_TRAVELED_AFTER_CODES_CLEARED"
    override val name = "Distance traveled since codes cleared"
    override val mode = "01"
    override val pid = "31"
    override val defaultUnit = "Km"
    override val handler = { response: ObdRawResponse ->
        bytesToInt(response.bufferedValue, bytesToProcess = 2).toString()
    }
}

/**
 * A different bug from the byte-overflow one above: the library's RuntimeCommand queries PID
 * "0F", which is actually Intake Air Temperature per SAE J1979, not Engine Runtime (that's PID
 * "1F"). Confirmed directly against the library's real source (Engine.kt): RuntimeCommand sends
 * "010F" and formats whatever comes back as a fake "HH:MM:SS" string, which explains the
 * existing "Engine Runtime always parses to null" note in KNOWN_ISSUES.md, the ECU is really
 * answering an intake-air-temperature request, and the resulting string never parses as a
 * number. This replacement queries the correct PID and returns a plain numeric seconds value.
 */
class SafeEngineRuntimeCommand : ObdCommand() {
    override val tag = "ENGINE_RUNTIME"
    override val name = "Engine Runtime"
    override val mode = "01"
    override val pid = "1F"
    override val defaultUnit = "s"
    override val handler = { response: ObdRawResponse ->
        bytesToInt(response.bufferedValue, bytesToProcess = 2).toString()
    }
}

/**
 * Same unbounded-bytesToProcess bug as bytesToInt, confirmed against the library's real source
 * (ParserFunctions.kt): calculatePercentage(bufferedValue) defaults bytesToProcess to -1, taking
 * every remaining byte instead of just PID 43's real 2 data bytes. AbsoluteLoadCommand never
 * passes bytesToProcess, so it hits this every time.
 */
class SafeAbsoluteLoadCommand : ObdCommand() {
    override val tag = "ENGINE_ABSOLUTE_LOAD"
    override val name = "Engine Absolute Load"
    override val mode = "01"
    override val pid = "43"
    override val defaultUnit = "%"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(calculatePercentage(response.bufferedValue, bytesToProcess = 2))
    }
}

/**
 * Not a library bug, just a PID this project didn't log before: Timing Advance (PID 0E), added
 * specifically to check for ignition-timing retard, the ECU's actual knock-mitigation response
 * on lower-octane fuel on this engine (SKYACTIV-G Turbo, factory-rated 250hp on 93 octane vs.
 * ~225hp on 87, via dynamic timing/boost adjustment from the knock sensor, not a fixed detune).
 * Retarded timing under load is real, ECU-reported evidence of what the engine is doing,
 * independent of what octane anyone remembers buying.
 */
class TimingAdvanceCommand : ObdCommand() {
    override val tag = "TIMING_ADVANCE"
    override val name = "Timing Advance"
    override val mode = "01"
    override val pid = "0E"
    override val defaultUnit = "°"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 1) / 2f - 64f)
    }
}
