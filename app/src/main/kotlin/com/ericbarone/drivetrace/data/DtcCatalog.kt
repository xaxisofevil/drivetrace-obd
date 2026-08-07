package com.ericbarone.drivetrace.data

/**
 * Diagnostic trouble code to plain English.
 *
 * **Coverage is deliberately partial: generic SAE J2012 `P0xxx` powertrain codes, the ones every
 * OBD-II vehicle shares.** Full manufacturer-specific coverage (`P1xxx`, plus every OEM's own
 * reuse of the `P3xxx` block) is thousands of codes per manufacturer, licensed rather than
 * published, and would be wrong more often than useful if guessed at. Chassis, body and network
 * codes are out of scope for the same reason and because this app only ever asks the powertrain
 * ECU anything.
 *
 * Anything not in the table still gets a useful answer rather than a blank: [describeDtc] falls
 * back to decoding the code's own structure, which is standardised even when the specific number
 * is not, so an unknown `P1234` still reads as "manufacturer-specific powertrain code, ignition
 * system or misfire" instead of nothing at all.
 */

/** The code, what it means, and whether that meaning came from the table or from the fallback. */
data class DtcDescription(
    val code: String,
    val meaning: String,
    /** False when [meaning] was inferred from the code's structure rather than looked up. */
    val known: Boolean,
)

fun describeDtc(rawCode: String): DtcDescription {
    val code = rawCode.trim().uppercase()
    GENERIC_POWERTRAIN_CODES[code]?.let { return DtcDescription(code, it, known = true) }
    return DtcDescription(code, inferredMeaning(code), known = false)
}

/**
 * Whether a code is one of the three values session 7 read back that are almost certainly the
 * library's response framing leaking into the parse rather than real faults.
 *
 * `C0300`, `C0700` and `C0A00` came back as current, pending and permanent respectively, and
 * those are exactly modes `03`, `07` and `0A`: the Mode-NN response byte being decoded as if it
 * were code data. Three chassis codes, one per request, on a vehicle whose chassis ECU this app
 * never talks to, is not a coincidence. See KNOWN_ISSUES.md's "DTC decoding is unverified".
 *
 * Flagged rather than filtered: this project's rule is to surface suspect data with its caveat,
 * not to silently drop it (same posture as `IMPLAUSIBLE` measurements, which are kept as text).
 * A genuine `C0300` on some other vehicle would be mislabelled by this, which is the honest cost
 * of not being able to verify the decode against a second scan tool yet.
 */
fun isSuspectedFramingArtifact(code: String): Boolean =
    code.trim().uppercase() in FRAMING_ARTIFACT_CODES

private val FRAMING_ARTIFACT_CODES = setOf("C0300", "C0700", "C0A00")

/**
 * Structural decode, per SAE J2012's code layout: first character is the system, second says
 * whether the number is SAE-defined or the manufacturer's own, third is the subsystem. Only the
 * powertrain subsystem list is spelled out, since that is the only ECU this app talks to.
 */
private fun inferredMeaning(code: String): String {
    val unrecognised = "Unrecognised code format. The verbatim adapter response is in this " +
        "session's event log."
    if (code.length != 5) return unrecognised

    val system = when (code[0]) {
        'P' -> "powertrain"
        'C' -> "chassis"
        'B' -> "body"
        'U' -> "network"
        else -> return unrecognised
    }
    // P0/P2 are SAE-defined, P1 is the manufacturer's own, P3 is split between the two roughly at
    // P3400. Approximate on purpose: getting this exactly right per block would not change what a
    // user does about it, which is look the number up against the vehicle's service data either way.
    val origin = when (code[1]) {
        '0', '2' -> "Generic"
        '1' -> "Manufacturer-specific"
        '3' -> "Partly manufacturer-specific"
        else -> return unrecognised
    }
    val subsystem = if (code[0] == 'P') POWERTRAIN_SUBSYSTEMS[code[2]] else null

    return buildString {
        append("$origin $system code")
        if (subsystem != null) append(", $subsystem")
        append(". Not in this app's generic code table; look it up against the vehicle's own ")
        append("service data.")
    }
}

/** Third character of a P-code, per SAE J2012. */
private val POWERTRAIN_SUBSYSTEMS: Map<Char, String> = mapOf(
    '0' to "fuel and air metering, or auxiliary emission controls",
    '1' to "fuel and air metering",
    '2' to "fuel and air metering, injector circuit",
    '3' to "ignition system or misfire",
    '4' to "auxiliary emission controls",
    '5' to "vehicle speed control, idle control, auxiliary inputs",
    '6' to "computer output circuit",
    '7' to "transmission",
    '8' to "transmission",
    '9' to "transmission or gearbox control",
    'A' to "hybrid propulsion",
    'B' to "hybrid propulsion",
    'C' to "hybrid propulsion",
)

/**
 * Generic SAE J2012 powertrain codes. Phrasing is shortened from the standard's own wording to
 * fit a phone line, not reworded into a diagnosis: "System too lean (bank 1)" is what the ECU is
 * reporting, and what causes it is a separate question this table deliberately does not answer.
 */
private val GENERIC_POWERTRAIN_CODES: Map<String, String> = mapOf(
    // Variable valve timing / crank-cam correlation
    "P0010" to "Camshaft position actuator A circuit (bank 1)",
    "P0011" to "Camshaft position A timing over-advanced, or system performance (bank 1)",
    "P0012" to "Camshaft position A timing over-retarded (bank 1)",
    "P0014" to "Camshaft position B timing over-advanced, or system performance (bank 1)",
    "P0016" to "Crankshaft and camshaft position correlation (bank 1, sensor A)",
    "P0017" to "Crankshaft and camshaft position correlation (bank 1, sensor B)",

    // Fuel system pressure
    "P0087" to "Fuel rail or system pressure too low",
    "P0088" to "Fuel rail or system pressure too high",
    "P0089" to "Fuel pressure regulator 1 performance",
    "P0090" to "Fuel pressure regulator 1 control circuit",

    // Air metering and temperature sensors
    "P0096" to "Intake air temperature sensor 2 circuit range or performance",
    "P0101" to "Mass air flow circuit range or performance",
    "P0102" to "Mass air flow circuit low input",
    "P0103" to "Mass air flow circuit high input",
    "P0106" to "Manifold or barometric pressure circuit range or performance",
    "P0107" to "Manifold or barometric pressure circuit low input",
    "P0108" to "Manifold or barometric pressure circuit high input",
    "P0111" to "Intake air temperature circuit range or performance",
    "P0112" to "Intake air temperature circuit low input",
    "P0113" to "Intake air temperature circuit high input",
    "P0116" to "Engine coolant temperature circuit range or performance",
    "P0117" to "Engine coolant temperature circuit low input",
    "P0118" to "Engine coolant temperature circuit high input",
    "P0121" to "Throttle or pedal position sensor A circuit range or performance",
    "P0122" to "Throttle or pedal position sensor A circuit low input",
    "P0123" to "Throttle or pedal position sensor A circuit high input",
    "P0125" to "Coolant temperature too low for closed-loop fuel control",
    "P0128" to "Coolant temperature below thermostat regulating temperature",

    // Oxygen sensors
    "P0130" to "O2 sensor circuit (bank 1, sensor 1)",
    "P0131" to "O2 sensor circuit low voltage (bank 1, sensor 1)",
    "P0132" to "O2 sensor circuit high voltage (bank 1, sensor 1)",
    "P0133" to "O2 sensor circuit slow response (bank 1, sensor 1)",
    "P0134" to "O2 sensor circuit, no activity detected (bank 1, sensor 1)",
    "P0135" to "O2 sensor heater circuit (bank 1, sensor 1)",
    "P0136" to "O2 sensor circuit (bank 1, sensor 2)",
    "P0137" to "O2 sensor circuit low voltage (bank 1, sensor 2)",
    "P0138" to "O2 sensor circuit high voltage (bank 1, sensor 2)",
    "P0139" to "O2 sensor circuit slow response (bank 1, sensor 2)",
    "P0140" to "O2 sensor circuit, no activity detected (bank 1, sensor 2)",
    "P0141" to "O2 sensor heater circuit (bank 1, sensor 2)",
    "P0150" to "O2 sensor circuit (bank 2, sensor 1)",
    "P0151" to "O2 sensor circuit low voltage (bank 2, sensor 1)",
    "P0152" to "O2 sensor circuit high voltage (bank 2, sensor 1)",
    "P0153" to "O2 sensor circuit slow response (bank 2, sensor 1)",
    "P0155" to "O2 sensor heater circuit (bank 2, sensor 1)",

    // Fuel trim
    "P0170" to "Fuel trim malfunction (bank 1)",
    "P0171" to "System too lean (bank 1)",
    "P0172" to "System too rich (bank 1)",
    "P0173" to "Fuel trim malfunction (bank 2)",
    "P0174" to "System too lean (bank 2)",
    "P0175" to "System too rich (bank 2)",
    "P0190" to "Fuel rail pressure sensor circuit",
    "P0191" to "Fuel rail pressure sensor circuit range or performance",

    // Injectors
    "P0201" to "Injector circuit open, cylinder 1",
    "P0202" to "Injector circuit open, cylinder 2",
    "P0203" to "Injector circuit open, cylinder 3",
    "P0204" to "Injector circuit open, cylinder 4",

    // Boost
    "P0234" to "Turbocharger or supercharger A overboost condition",
    "P0243" to "Turbocharger wastegate solenoid A",
    "P0245" to "Turbocharger wastegate solenoid A low",
    "P0246" to "Turbocharger wastegate solenoid A high",
    "P0299" to "Turbocharger or supercharger A underboost condition",

    // Misfire
    "P0300" to "Random or multiple cylinder misfire detected",
    "P0301" to "Cylinder 1 misfire detected",
    "P0302" to "Cylinder 2 misfire detected",
    "P0303" to "Cylinder 3 misfire detected",
    "P0304" to "Cylinder 4 misfire detected",
    "P0305" to "Cylinder 5 misfire detected",
    "P0306" to "Cylinder 6 misfire detected",
    "P0313" to "Misfire detected with low fuel level",
    "P0316" to "Misfire detected on startup, first 1000 revolutions",

    // Ignition, knock, position sensors
    "P0320" to "Ignition or distributor engine speed input circuit",
    "P0325" to "Knock sensor 1 circuit (bank 1)",
    "P0326" to "Knock sensor 1 circuit range or performance (bank 1)",
    "P0327" to "Knock sensor 1 circuit low (bank 1)",
    "P0328" to "Knock sensor 1 circuit high (bank 1)",
    "P0335" to "Crankshaft position sensor A circuit",
    "P0336" to "Crankshaft position sensor A circuit range or performance",
    "P0340" to "Camshaft position sensor A circuit (bank 1)",
    "P0341" to "Camshaft position sensor A circuit range or performance (bank 1)",

    // EGR, secondary air, catalyst
    "P0401" to "Exhaust gas recirculation flow insufficient",
    "P0402" to "Exhaust gas recirculation flow excessive",
    "P0403" to "Exhaust gas recirculation control circuit",
    "P0404" to "Exhaust gas recirculation control circuit range or performance",
    "P0411" to "Secondary air injection system, incorrect flow detected",
    "P0420" to "Catalyst system efficiency below threshold (bank 1)",
    "P0430" to "Catalyst system efficiency below threshold (bank 2)",

    // Evaporative emissions
    "P0440" to "Evaporative emission system",
    "P0441" to "Evaporative emission system, incorrect purge flow",
    "P0442" to "Evaporative emission system leak detected, small leak",
    "P0443" to "Evaporative emission purge control valve circuit",
    "P0446" to "Evaporative emission vent control circuit",
    "P0455" to "Evaporative emission system leak detected, gross leak",
    "P0456" to "Evaporative emission system leak detected, very small leak",
    "P0457" to "Evaporative emission system leak detected, fuel cap loose or off",

    // Speed, idle, oil pressure
    "P0500" to "Vehicle speed sensor A",
    "P0501" to "Vehicle speed sensor A range or performance",
    "P0505" to "Idle air control system",
    "P0506" to "Idle control system RPM lower than expected",
    "P0507" to "Idle control system RPM higher than expected",
    "P0520" to "Engine oil pressure sensor or switch circuit",
    "P0521" to "Engine oil pressure sensor or switch range or performance",
    "P0524" to "Engine oil pressure too low",

    // Electrical and control module
    "P0562" to "System voltage low",
    "P0563" to "System voltage high",
    "P0600" to "Serial communication link",
    "P0601" to "Internal control module memory checksum error",
    "P0606" to "ECM or PCM processor",
    "P0700" to "Transmission control system, malfunction indicator requested",
)
