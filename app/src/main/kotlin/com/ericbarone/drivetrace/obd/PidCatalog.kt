package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.NonNumericResponseException
import com.github.eltonvs.obd.command.egr.CommandedEgrCommand
import com.github.eltonvs.obd.command.egr.EgrErrorCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.RuntimeCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.control.ModuleVoltageCommand
import com.github.eltonvs.obd.command.fuel.FuelConsumptionRateCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.fuel.FuelTrimCommand
import com.github.eltonvs.obd.command.fuel.CommandedEquivalenceRatioCommand
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.pressure.BarometricPressureCommand
import com.github.eltonvs.obd.command.pressure.FuelRailPressureCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import com.github.eltonvs.obd.command.control.TroubleCodesCommand
import com.github.eltonvs.obd.command.control.PendingTroubleCodesCommand
import com.github.eltonvs.obd.command.control.PermanentTroubleCodesCommand
import com.github.eltonvs.obd.command.control.VINCommand
import com.github.eltonvs.obd.command.control.DistanceSinceCodesClearedCommand
import com.github.eltonvs.obd.command.fuel.FuelTypeCommand
import com.github.eltonvs.obd.command.control.MILOnCommand

/**
 * The tiered PID list from the drive-logger blueprint (section 6). A cheap ELM327 clone
 * can't sample everything at once, so Tier A is polled continuously, Tier B every few
 * seconds, and Tier C every 10-30s. See [PidScheduler] for the interleaving logic.
 */
object PidCatalog {
    /** Sample as frequently as the adapter allows. */
    fun tierA(): List<() -> ObdCommand> =
        listOf(
            { RPMCommand() },
            { SpeedCommand() },
            { LoadCommand() },
            { MassAirFlowCommand() },
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.LONG_TERM_BANK_1) },
            { CommandedEquivalenceRatioCommand() },
            { ModuleVoltageCommand() },
        )

    /** Sample roughly every 2-5 seconds. */
    fun tierB(): List<() -> ObdCommand> =
        listOf(
            { EngineCoolantTemperatureCommand() },
            { AirIntakeTemperatureCommand() },
            { ThrottlePositionCommand() },
            { IntakeManifoldPressureCommand() },
            { FuelRailPressureCommand() },
            { FuelConsumptionRateCommand() },
        )

    /** Sample roughly every 10-30 seconds; slow-changing context. */
    fun tierC(): List<() -> ObdCommand> =
        listOf(
            { BarometricPressureCommand() },
            { AmbientAirTemperatureCommand() },
            { RuntimeCommand() },
            { DistanceSinceCodesClearedCommand() },
            { CommandedEgrCommand() },
            { EgrErrorCommand() },
            { FuelLevelCommand() },
        )

    /** Read once at session start. Never includes anything that clears/writes to the ECU. */
    fun oneTimeReadOnly(): List<() -> ObdCommand> =
        listOf(
            { VINCommand() },
            { FuelTypeCommand() },
            { MILOnCommand() },
            { TroubleCodesCommand() },
            { PendingTroubleCodesCommand() },
            { PermanentTroubleCodesCommand() },
        )
}

/** Marker used by [PidScheduler] to decide whether a NonNumericResponseException is expected noise. */
internal typealias ExpectedParseNoise = NonNumericResponseException
