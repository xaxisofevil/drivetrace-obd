package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt

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
