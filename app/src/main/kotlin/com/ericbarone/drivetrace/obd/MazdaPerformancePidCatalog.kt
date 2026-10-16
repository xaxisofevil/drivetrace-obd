package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.pressure.BarometricPressureCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand

/**
 * High-frequency catalog for the 2026-10 intercooler/heat-soak investigation, selected as the
 * Mazda's performance logging mode (switchable live, see VehicleProfile.performanceCatalog), not a
 * replacement for MazdaPidCatalog's normal drive-logging list. The whole point is bandwidth: the
 * ELM327 link only has so many request/response round-trips per second, and MazdaPidCatalog's
 * normal 9-command Tier A already divides that budget nine ways. Repeated hard-acceleration
 * pulls need RPM/MAP/IAT/throttle/timing/charge-temp sampled as close to every round-trip as
 * possible, so this catalog deliberately drops everything not needed for that one question
 * (fuel trims, MAF, voltage, etc, all still available by just logging a normal session instead).
 *
 * Tier A: the signals that actually move cycle-to-cycle during a pull, kept to 6 entries instead
 * of MazdaPidCatalog's 9 specifically to raise this tier's real-world refresh rate.
 * Tier B (3s nominal): knock and commanded boost, the other two signals the heat-soak question
 * needs, but nothing that changes meaningfully faster than a few seconds during a sustained pull.
 * Tier C (20s nominal): Barometric Pressure and Ambient Air Temperature, both genuinely
 * slow-changing (see PidScheduler's TIER_C_INTERVAL_MS) and already known from KNOWN_ISSUES.md to
 * be unreliable as anything finer-grained than "roughly what it was a minute ago" on this
 * vehicle; polling them any faster than Tier A/B's real signals would only steal round-trips from
 * the data this catalog exists to capture.
 */
object MazdaPerformancePidCatalog : PidCatalog {
    override fun tierA(): List<() -> ObdCommand> =
        listOf(
            { RPMCommand() },
            { IntakeManifoldPressureCommand() }, // actual boost, see IntakeManifoldPressureDesiredCommand for the commanded side (Tier B)
            { AirIntakeTemperatureCommand() }, // standard PID 0F; location vs. the two Mode-22 charge-temp PIDs below is the open question
            { ThrottlePositionCommand() },
            { TimingAdvanceCommand() },
            { ChargeAirCoolerTemperatureCommand() }, // presumed post-intercooler, see MazdaEnhancedCommands.kt
            { BoostedAirTemperatureCommand() }, // presumed pre-intercooler, same source
        )

    override fun tierB(): List<() -> ObdCommand> =
        listOf(
            { KnockRetardCommand() },
            { KnockControlSystemCommand() },
            { IntakeManifoldPressureDesiredCommand() }, // commanded boost, see tierA's IntakeManifoldPressureCommand for actual
        )

    override fun tierC(): List<() -> ObdCommand> =
        listOf(
            { BarometricPressureCommand() },
            { AmbientAirTemperatureCommand() },
        )

    /** Nothing this profile needs that a normal session's one-time reads don't already cover;
     * run a normal MazdaPidCatalog session alongside this one if DTCs/VIN/etc matter. */
    override fun oneTimeReadOnly(): List<() -> ObdCommand> = emptyList()
}
