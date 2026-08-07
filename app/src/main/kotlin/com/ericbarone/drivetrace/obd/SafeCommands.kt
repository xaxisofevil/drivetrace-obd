package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt
import com.github.eltonvs.obd.command.calculatePercentage
import com.github.eltonvs.obd.command.control.AvailablePIDsCommand
import com.github.eltonvs.obd.command.getBitAt

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
 * The actual root cause of the entire evening's LTFT investigation, finally found: the library's
 * FuelTrimCommand.FuelTrimBank enum has SHORT_TERM_BANK_2 ("07") and LONG_TERM_BANK_1 ("08")
 * swapped relative to the real SAE J1979 standard. Confirmed against the standard (06=STFT-B1,
 * 07=LTFT-B1, 08=STFT-B2, 09=LTFT-B2, unambiguous and unchanged for decades) and independently
 * cross-checked live on this vehicle with a second scan tool (Car Scanner): its own PID list
 * labels ID=7 "Long term fuel % trim - Bank 1" and reads a real value from it (7.03%), while
 * this project had been requesting PID 08 under that same label the entire time, PID 08 is
 * really Short Term Fuel Trim Bank 2, which does not exist on this single-bank inline-4 engine,
 * hence the literal, consistent "NO DATA" on every single attempt all night. PID 07 is confirmed
 * present in this ECU's own declared supported-PID list (see SafeAvailablePIDsCommand below);
 * PID 08 is confirmed absent, exactly as expected for a real Bank 2 PID on an engine with no
 * Bank 2. Every theory chased earlier tonight (adapter flakiness, ELM327 headers, timing/
 * condition-gating, "Mazda hides this behind an enhanced PID") was chasing a request that was
 * simply asking the ECU a question it correctly had no answer to. Same math as the library's
 * FuelTrimCommand (parsing is not what was wrong, only the PID string), same tag as before
 * ("LONG_TERM_BANK_1") so existing event-log queries and analyze_drive.py's canonical_name
 * keyword matching (`r"long term.*bank 1"`) keep working unchanged, going forward they'll just
 * finally have real data behind them instead of NoDataException every time.
 */
class SafeLongTermFuelTrimBank1Command : ObdCommand() {
    override val tag = "LONG_TERM_BANK_1"
    override val name = "Long Term Fuel Trim Bank 1"
    override val mode = "01"
    override val pid = "07"
    override val defaultUnit = "%"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 1) * (100f / 128f) - 100f)
    }
}

/**
 * The flip side of SafeLongTermFuelTrimBank1Command's fix: the library's
 * FuelTrimBank.SHORT_TERM_BANK_2 entry points at PID "07" (which is really Long Term Fuel Trim
 * Bank 1, see above), when the real Short Term Fuel Trim Bank 2 is PID "08". Not needed on the
 * Mazda 6 (single-bank engine, no Bank 2 at all), added for multi-bank engines (e.g. the Subaru
 * Outback's boxer, see SubaruPidCatalog.kt) where Bank 2 trim is a real, legitimate signal, not
 * noise. Same math as the library's FuelTrimCommand, only the PID was ever wrong.
 */
class SafeShortTermFuelTrimBank2Command : ObdCommand() {
    override val tag = "SHORT_TERM_BANK_2"
    override val name = "Short Term Fuel Trim Bank 2"
    override val mode = "01"
    override val pid = "08"
    override val defaultUnit = "%"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 1) * (100f / 128f) - 100f)
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

/**
 * Not in the library at all, confirmed by checking (no CatalystTemperature command exists
 * anywhere in kotlin-obd-api's source). Added to cover a distinct MPG-loss mechanism nothing
 * else in either vehicle's catalog checks for: a restricted or failing catalytic converter
 * increases exhaust backpressure, which increases pumping losses and measurably hurts fuel
 * economy, independent of anything on the fuel-delivery/combustion side. Standard 2-byte SAE
 * J1979 formula: ((A*256)+B)/10 - 40, in Celsius.
 */
class CatalystTemperatureBank1Sensor1Command : ObdCommand() {
    override val tag = "CATALYST_TEMP_B1S1"
    override val name = "Catalyst Temperature Bank 1 Sensor 1"
    override val mode = "01"
    override val pid = "3C"
    override val defaultUnit = "°C"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 2) / 10f - 40f)
    }
}

/**
 * Not in the library at all, same check as above. Complements Engine Coolant Temperature for
 * the warm-up/thermal hypothesis: oil temperature is a slower, more thermally stable signal, a
 * stuck-partially-open thermostat could plausibly show a normal-looking coolant plateau while
 * oil temp lags, useful context alongside coolant temp, not a replacement for it. Standard
 * single-byte SAE J1979 formula: A - 40, in Celsius, same shape as coolant/intake air temp
 * already used correctly elsewhere in this project.
 */
class OilTemperatureCommand : ObdCommand() {
    override val tag = "OIL_TEMP"
    override val name = "Engine Oil Temperature"
    override val mode = "01"
    override val pid = "5C"
    override val defaultUnit = "°C"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, bytesToProcess = 1) - 40f)
    }
}

/**
 * Not a byte-math bug like the ones above: confirmed directly against two real sessions that
 * this adapter returns TWO frames for a single Mode 01 supported-PIDs request, concatenated
 * with no separator surviving the library's cleanup pipeline (e.g. "4100981A80134100FE7FA813").
 * The library's handler calls rawValue.toLong(radix=16) on the whole thing; a 24-hex-char string
 * is 96 bits, overflowing a 64-bit Long and throwing NumberFormatException. This failed
 * PIDS_01_TO_20/21_TO_40/41_TO_60 on both real sessions tried, PIDS_01_TO_20 is the one that
 * matters most, it's the range containing PID 08 (LONG_TERM_BANK_1), so this bug had been
 * silently blocking the exact check it was added to answer (see KNOWN_ISSUES.md's LTFT
 * sections).
 *
 * Checked the two frames' actual content before assuming they were harmless duplicates, and
 * they aren't: session 1 saw 981A8013 then FE7FA813; session 2 saw the same two values in the
 * OPPOSITE order. Same two distinct bitmasks, reproducibly, just unstable ordering, ruling out
 * both "duplicate response" and "random corruption" (corruption wouldn't reproduce the exact
 * same two values across separate sessions). The likely explanation: this project's ELM327 init
 * sends ATH0 (headers off), so a broadcast Mode 01 request gets answered by every ECU on the bus
 * that supports it, and with headers off there's no way to tell which frame came from which
 * module, they just concatenate in bus-arbitration order (which varies). Given that, the
 * correct read isn't "pick one frame" (arbitrary, and confirmed unstable which one comes first)
 * but "a PID is supported if ANY responding module reports it", i.e. OR the frames together.
 * Verified directly: OR-ing session 1's two frames reproduces the same result as OR-ing
 * session 2's (order-independent, as it must be for OR), and PID 08 is absent under every
 * reading (frame A alone, frame B alone, or OR'd), so the "is LTFT supported" answer is robust
 * regardless of which theory of the two-frame behavior is correct.
 */
class SafeAvailablePIDsCommand(
    private val range: AvailablePIDsCommand.AvailablePIDsRanges,
) : ObdCommand() {
    // range.pid is internal to the library's module and not accessible here; derived locally
    // from the range instead. Values confirmed against AvailablePIDsRanges' real source.
    private val rangePid: String = when (range) {
        AvailablePIDsCommand.AvailablePIDsRanges.PIDS_01_TO_20 -> "00"
        AvailablePIDsCommand.AvailablePIDsRanges.PIDS_21_TO_40 -> "20"
        AvailablePIDsCommand.AvailablePIDsRanges.PIDS_41_TO_60 -> "40"
        AvailablePIDsCommand.AvailablePIDsRanges.PIDS_61_TO_80 -> "60"
        AvailablePIDsCommand.AvailablePIDsRanges.PIDS_81_TO_A0 -> "80"
    }

    override val tag = "AVAILABLE_COMMANDS_${range.name}"
    override val name = "Available Commands - ${range.displayName}"
    override val mode = "01"
    override val pid = rangePid
    override val defaultUnit = ""
    override val handler = { response: ObdRawResponse ->
        parsePIDs(response.processedValue).joinToString(",") { "%02X".format(it) }
    }

    private fun parsePIDs(rawValue: String): IntArray {
        // Drop a ragged trailing partial frame (a truncated response) rather than let it throw;
        // OR every complete frame together, see the multi-ECU-broadcast explanation above.
        val combined = rawValue
            .chunked(FRAME_HEX_CHARS)
            .filter { it.length == FRAME_HEX_CHARS }
            .fold(0L) { acc, frame -> acc or frame.toLong(radix = 16) }
        val initialPID = rangePid.toInt(radix = 16)
        return (1..PID_RANGE_SIZE).fold(intArrayOf()) { acc, i ->
            if (combined.getBitAt(i) == 1) acc.plus(i + initialPID) else acc
        }
    }

    private companion object {
        const val PID_RANGE_SIZE = 33
        const val FRAME_HEX_CHARS = 12
    }
}
