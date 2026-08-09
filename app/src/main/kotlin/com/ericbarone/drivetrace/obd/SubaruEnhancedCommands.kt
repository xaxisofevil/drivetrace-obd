package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt

/**
 * Manufacturer-specific (Mode 22) parameters for Subaru's FB-generation boxer engines, sourced
 * from ParsePID (github.com/giotec/ParsePID)'s `Subaru_mode22_def.csv`, cross-checked against
 * that same repository's `2014 Forester FB25 non-Turbo Responses.txt`, a real capture of a 2014
 * FB25 NA/CVT ECU's own "supported DIDs" bitmap declaring exactly which of these it actually
 * answers. That capture is the strongest evidence behind this file: it isn't a spreadsheet of
 * plausible-looking definitions, it's confirmation from a real ECU of the same engine code and
 * model year as this project's 2014 Outback that these specific DIDs exist on it. It is a
 * Forester, not an Outback, both share the FB25/CVT drivetrain but are not the same vehicle;
 * treat "this engine platform answers these" as strong, not certain, for this specific car.
 *
 * Every formula below was hand-verified against its own stated byte-math extremes before being
 * trusted (see the commit this file shipped in), the same discipline MazdaEnhancedCommands.kt
 * used, and for the same reason: a community source getting the arithmetic internally
 * inconsistent (see that file's Knock Control System catch) is a real, previously-confirmed
 * failure mode, not a hypothetical one.
 *
 * Fuel Injector Pulse Width (INJ_PW, 0x2210A3) is the one genuinely new capability here: this
 * project's fuel-burned estimate has been entirely MAF-derived (airflow x commanded equivalence
 * ratio) because the standard Engine Fuel Rate PID (Mode 01, 0x5E) returns NO DATA on every
 * session logged so far, Mazda and Subaru alike (confirmed directly against the server's own
 * measurement history, not assumed). Pulse width is not itself a fuel-mass number, turning it
 * into one needs this specific injector's flow rate and injection-event frequency, neither of
 * which this project has yet, so nothing downstream (analyze_drive.py) currently does that
 * conversion. Capturing the raw PID now is the first step: a second, differently-derived signal
 * to eventually cross-check the MAF-based estimate against, not a working second estimate yet.
 *
 * Learned Ignition Timing (LIT, 0x2210A5) is deliberately not named or treated as a knock-event
 * counter. Per its own source definition ("advance or retard amount when knocking has occurred")
 * and corroborating discussion on NASIOC, this is closer to a learned/adaptive correction than an
 * instantaneous knock signal, distinct from Timing Advance (the existing standard PID) and from
 * RomRaider's Feedback/Fine Learning Knock Correction, for which no Mode 22 UDS mapping was found
 * at all for this ECU, only its proprietary SSM logging.
 *
 * All nine DIDs below are confirmed present in that Forester capture's supported-DID bitmap.
 * Deliberately excluded despite appearing in the wider ParsePID definition file: EGR Target/
 * Actual Valve Opening Angle (0x22111B/0x22111C), both marked diesel-only in the source and not
 * present in that petrol capture's supported set.
 */

/**
 * Source: "Fuel Injector #1 Pulse Width,INJ_PW,0x2210A3,A*0.256,0,65.28,ms". Includes injector
 * latency per the source's own note, not a pure commanded-open-time number. This ECU also defines
 * 0x2210A4 (Injector #2), NOT present in the Forester's supported-DID bitmap alongside 10A3, so
 * this is very likely Subaru's one general injector-PW monitor rather than true per-cylinder data;
 * only 10A3 is implemented here for that reason.
 */
class FuelInjectorPulseWidthCommand : ObdCommand() {
    override val tag = "INJ_PW"
    override val name = "Fuel Injector Pulse Width"
    override val mode = "22"
    override val pid = "10A3"
    override val defaultUnit = "ms"
    override val handler = { response: ObdRawResponse ->
        "%.3f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) * 0.256f)
    }
}

/**
 * Source: "Learned Ignition Timing,LIT,0x2210A5,(A-128)/2,-64,63.5,deg". See this file's header
 * for why this is not treated as an instantaneous knock-event signal.
 */
class LearnedIgnitionTimingCommand : ObdCommand() {
    override val tag = "LIT"
    override val name = "Learned Ignition Timing"
    override val mode = "22"
    override val pid = "10A5"
    override val defaultUnit = "deg"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format((bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) - 128) / 2f)
    }
}

/**
 * Source: "Intake VVT Advance Angle Right,AVCS_R,0x2210B4,A-50". The source file supplies no
 * display min/max for this one; -50..205 would be the raw byte-math extremes, not a physically
 * plausible cam angle, so PidScheduler.kt's plausibility clamp uses a much tighter range chosen
 * from what a real AVCS system can do, not from this formula's own bounds. Confirm/widen once a
 * real drive shows what this ECU's actual operating range looks like.
 */
class IntakeVvtAdvanceAngleRightCommand : ObdCommand() {
    override val tag = "AVCS_R"
    override val name = "Intake VVT Advance Angle Right"
    override val mode = "22"
    override val pid = "10B4"
    override val defaultUnit = "deg"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) - 50f)
    }
}

/** Left-bank counterpart to [IntakeVvtAdvanceAngleRightCommand], same source row, same caveats. */
class IntakeVvtAdvanceAngleLeftCommand : ObdCommand() {
    override val tag = "AVCS_L"
    override val name = "Intake VVT Advance Angle Left"
    override val mode = "22"
    override val pid = "10B5"
    override val defaultUnit = "deg"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) - 50f)
    }
}

/** Source: "Alternator Duty,ALT_DUTY,0x2210B2,A,0,100,%". Electrical load ultimately shows up as
 * mechanical load on the engine, worth having alongside the fuel-economy investigation. */
class AlternatorDutyCommand : ObdCommand() {
    override val tag = "ALT_DUTY"
    override val name = "Alternator Duty"
    override val mode = "22"
    override val pid = "10B2"
    override val defaultUnit = "%"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1).toFloat())
    }
}

/** Source: "Battery Current,BATT_A,0x221135,A-128,-100,100,A". Stated range is a physical clamp
 * from the source, not this byte formula's own extremes (-128..127); kept as-is. */
class BatteryCurrentCommand : ObdCommand() {
    override val tag = "BATT_A"
    override val name = "Battery Current"
    override val mode = "22"
    override val pid = "1135"
    override val defaultUnit = "A"
    override val handler = { response: ObdRawResponse ->
        "%.0f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) - 128f)
    }
}

/** Source: "Battery Temperature,BATT_TEMP,0x221136,A-40,-20,60,C". Same note as Battery Current:
 * stated range is a physical clamp, not this formula's raw byte extremes (-40..215). */
class BatteryTemperatureCommand : ObdCommand() {
    override val tag = "BATT_TEMP"
    override val name = "Battery Temperature"
    override val mode = "22"
    override val pid = "1136"
    override val defaultUnit = "C"
    override val handler = { response: ObdRawResponse ->
        "%.0f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1) - 40f)
    }
}

/**
 * Source: "Alternator Control Mode,ALT_MODE,0x221137,A,0,5,enum" - 0=High, 1=ExHigh, 2=Low,
 * 3=Mid, values 4-5 unlabeled in the source. Stored as a plain number like every other PID
 * rather than a special enum type: this project has no categorical-value machinery, and a raw
 * 0-5 integer is legible enough for what this signal is worth here (a coarse "is the alternator
 * loading the engine harder right now" flag alongside AlternatorDutyCommand's finer-grained %).
 */
class AlternatorControlModeCommand : ObdCommand() {
    override val tag = "ALT_MODE"
    override val name = "Alternator Control Mode"
    override val mode = "22"
    override val pid = "1137"
    override val defaultUnit = "mode"
    override val handler = { response: ObdRawResponse ->
        "%.0f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 1).toFloat())
    }
}

/**
 * Source: "Target Engine Speed,RPM_TARGET,0x221121,((A*256)+B)/4,0,10000,rpm". Named "Target
 * Engine RPM" here, not "Target Engine Speed": the standard PID this project already reads is
 * canonical-named "Engine RPM", and analyze_drive.py's keyword matching for both that PID and
 * vehicle speed uses bare `rpm`/`speed` word patterns; "Target Engine Speed" would have silently
 * matched the existing speed_kmh keyword pattern (confirmed by testing the regex directly before
 * writing this), the same class of collision MazdaEnhancedCommands.kt's Intake Manifold Pressure
 * Desired caused and fixed. Renaming sidesteps it entirely for the speed pattern; the still-real
 * collision against the bare `rpm` pattern is fixed on the analyze_drive.py side instead (see
 * PID_KEYWORDS's rpm entry), since this name still legitimately contains the word "RPM".
 * Same 2-byte, /4 scaling as the standard Engine RPM PID (0C), a real internal-consistency
 * signal for this formula, not just a coincidence.
 */
class TargetEngineRpmCommand : ObdCommand() {
    override val tag = "RPM_TARGET"
    override val name = "Target Engine RPM"
    override val mode = "22"
    override val pid = "1121"
    override val defaultUnit = "rpm"
    override val handler = { response: ObdRawResponse ->
        "%.0f".format(bytesToInt(response.bufferedValue, start = 3, bytesToProcess = 2) / 4f)
    }
}
