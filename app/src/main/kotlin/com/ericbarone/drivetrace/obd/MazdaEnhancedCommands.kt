package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.command.bytesToInt

/**
 * Manufacturer-specific (Mode 22) parameters, sourced from a community-compiled CSV of extended
 * Mazda Skyactiv PIDs shared on the Mazdas247 forum (Torque custom-PID format), not from any
 * official Mazda documentation. Mode 22 Data Identifiers are entirely manufacturer/platform-
 * defined, unlike generic Mode 01 PIDs, so these belong only in MazdaPidCatalog, never
 * SubaruPidCatalog.
 *
 * Real, unresolved uncertainty carried over from the source file, not hidden:
 * - **The source CSV's header column was corrupted by Excel's own auto-formatting** before it
 *   reached this project: several rows showed a header of "7.00E+00", almost certainly Excel
 *   misreading the literal text "7E0" (a completely standard 11-bit CAN request ID, SAE J1979's
 *   first functional-addressing slot) as scientific notation for the number 7, then redisplaying
 *   its own reinterpretation. This is inferred from the corruption pattern, not confirmed
 *   against an uncorrupted original.
 * - **This project has no mechanism to override the CAN header per command at all** (confirmed
 *   against kotlin-obd-api's ObdCommand base class: mode+pid are the only fields, no header
 *   property; ElmSession/PidScheduler have no per-command AT-injection point either). Some rows
 *   in the source file explicitly said "Auto" (the ELM327's default addressing is enough) while
 *   others specified a real header instead, a deliberate distinction its author made, which is a
 *   real signal that these specific requests may genuinely need an explicit "ATSH 7E0" this
 *   project never sends. If these come back NO DATA on a real drive, check the raw response text
 *   (captured for every attempt now) before assuming the PID itself is wrong.
 * - **These responses are longer than anything this project has successfully parsed before**
 *   (5+ data bytes plus a 3-byte echo prefix = 8+ bytes, more than fits in one CAN frame, so
 *   correct reception depends on the ELM327 firmware reassembling a multi-frame ISO-TP response
 *   transparently). VIN needs the same kind of multi-frame reassembly and has never once worked
 *   on this adapter, for a reason never fully resolved, worth keeping in mind if these fail the
 *   same way.
 * - **Completely untested against a real vehicle.** Everything below is a best-effort
 *   implementation of someone else's reverse-engineered definitions.
 *
 * Byte-offset math: a Mode 22 request with a 2-byte DID echoes back 3 bytes before the actual
 * data ("62 F4 70 <data...>"), one more than a standard Mode 01 request's 2-byte echo ("41 0C
 * <data...>"). Verified directly: bufferedValue index 3 is the response's first true data byte
 * ("A" in the source file's own lettering), so index 3="A", index 4="B", index 5="C", and so on,
 * that off-by-one against every other command in this project is easy to get wrong.
 */

/**
 * Source: "Intake Manifold Absolute Pressure Desired,IMAP DSD,0x22f470,(B*256+C)/32,0,300,kPa"
 * The genuinely new information from this whole CSV for the octane/boost investigation: standard
 * OBD-II has no PID for *commanded/target* boost, only actual (already read via the standard
 * Intake Manifold Pressure PID, 0x0B, in both catalogs). Reported here in the same raw kPa units
 * as that standard PID so analyze_drive.py can derive "desired boost" the same way it already
 * derives actual boost (target IMAP - Barometric), see add_derived_columns.
 */
class IntakeManifoldPressureDesiredCommand : ObdCommand() {
    override val tag = "IMAP_DESIRED"
    override val name = "Intake Manifold Pressure Desired"
    override val mode = "22"
    override val pid = "F470"
    override val defaultUnit = "kPa"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 4, bytesToProcess = 2) / 32f)
    }
}

/**
 * Source: "Turbocharger A Compressor Inlet Pressure,TCA CINP,0x22f46f,B*8,0,2040,kPa"
 * Pressure, not temperature, despite the name resembling what was originally asked about, the
 * source file has no compressor inlet *temperature* entry at all. Still directly useful for
 * turbo health: this is upstream of the compressor, a restricted air filter or a leak in the
 * pre-turbo intake ducting would show here independent of anything downstream.
 */
class TurbochargerACompressorInletPressureCommand : ObdCommand() {
    override val tag = "TURBO_A_COMPRESSOR_INLET_PRESSURE"
    override val name = "Turbocharger A Compressor Inlet Pressure"
    override val mode = "22"
    override val pid = "F46F"
    override val defaultUnit = "kPa"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 4, bytesToProcess = 1) * 8f)
    }
}

/** Same request as TurbochargerACompressorInletPressureCommand, the next data byte over ("C"
 * instead of "B"): "Turbocharger B Compressor Inlet Pressure,TCB CINP,0x22f46f,C*8,0,2040,kPa".
 * This engine has one turbo, not two; "A"/"B" more likely distinguish two sides of a twin-scroll
 * housing than two separate turbochargers, unconfirmed. */
class TurbochargerBCompressorInletPressureCommand : ObdCommand() {
    override val tag = "TURBO_B_COMPRESSOR_INLET_PRESSURE"
    override val name = "Turbocharger B Compressor Inlet Pressure"
    override val mode = "22"
    override val pid = "F46F"
    override val defaultUnit = "kPa"
    override val handler = { response: ObdRawResponse ->
        "%.1f".format(bytesToInt(response.bufferedValue, start = 5, bytesToProcess = 1) * 8f)
    }
}

/**
 * Source: "Knock Retard,KR,0x2203ec,((signed(A)*256)+B)/-512,0,-5"
 * More directly useful for the octane/knock investigation than Timing Advance alone: Timing
 * Advance is the net commanded timing (base map plus every correction combined), Knock Retard is
 * specifically how much timing is being pulled due to detected knock, isolating the one signal
 * the whole octane hypothesis actually cares about. "signed(A)" reinterprets byte A alone as a
 * signed 8-bit value (-128..127) before combining with the still-unsigned byte B, verified this
 * distinction directly (it is NOT "reinterpret the combined 16-bit value as signed", a different,
 * wrong operation that was easy to reach for instead) with a manual worked example: A=0x05
 * (small, real-looking knock) gives -2.5°, matching the source file's stated 0-to-negative-5
 * range for genuine active knock retard.
 */
class KnockRetardCommand : ObdCommand() {
    override val tag = "KNOCK_RETARD"
    override val name = "Knock Retard"
    override val mode = "22"
    override val pid = "03EC"
    override val defaultUnit = "°"
    override val handler = { response: ObdRawResponse ->
        val bytes = response.bufferedValue
        val byteA = bytes[3]
        val byteB = bytes[4]
        val signedA = if (byteA >= 128) byteA - 256 else byteA
        "%.2f".format((signedA * 256 + byteB) / -512f)
    }
}

/**
 * Source: "Knock Control System,KCS,0x2203e8,(signed(a)*256+b)/16384,-100,100"
 * A second, complementary knock signal alongside Knock Retard, same byte-signing convention
 * (byte A reinterpreted as signed 8-bit, byte B stays unsigned), different scale/divisor. No
 * unit given in the source file; treated as a percentage-like value given the -100..100 range.
 */
class KnockControlSystemCommand : ObdCommand() {
    override val tag = "KNOCK_CONTROL_SYSTEM"
    override val name = "Knock Control System"
    override val mode = "22"
    override val pid = "03E8"
    override val defaultUnit = "%"
    override val handler = { response: ObdRawResponse ->
        val bytes = response.bufferedValue
        val byteA = bytes[3]
        val byteB = bytes[4]
        val signedA = if (byteA >= 128) byteA - 256 else byteA
        "%.2f".format((signedA * 256 + byteB) / 16384f)
    }
}
