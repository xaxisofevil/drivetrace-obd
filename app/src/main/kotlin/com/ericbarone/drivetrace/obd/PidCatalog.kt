package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.NonNumericResponseException
import com.github.eltonvs.obd.command.egr.CommandedEgrCommand
import com.github.eltonvs.obd.command.egr.EgrErrorCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.fuel.FuelTrimCommand
import com.github.eltonvs.obd.command.fuel.CommandedEquivalenceRatioCommand
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.pressure.BarometricPressureCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import com.github.eltonvs.obd.command.control.AvailablePIDsCommand
import com.github.eltonvs.obd.command.control.TroubleCodesCommand
import com.github.eltonvs.obd.command.control.PendingTroubleCodesCommand
import com.github.eltonvs.obd.command.control.PermanentTroubleCodesCommand
import com.github.eltonvs.obd.command.control.VINCommand
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
            { RPMCommand() }, // fixed upstream at the pinned commit (see build.gradle.kts)
            { SpeedCommand() },
            { LoadCommand() },
            { SafeMassAirFlowCommand() }, // library's MassAirFlowCommand still has the unbounded-byte bug
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.LONG_TERM_BANK_1) },
            { CommandedEquivalenceRatioCommand() },
            { SafeModuleVoltageCommand() }, // library's ModuleVoltageCommand still has the unbounded-byte bug
        )

    /** Sample roughly every 2-5 seconds. */
    fun tierB(): List<() -> ObdCommand> =
        listOf(
            { EngineCoolantTemperatureCommand() },
            { AirIntakeTemperatureCommand() },
            { ThrottlePositionCommand() },
            { IntakeManifoldPressureCommand() },
            { SafeFuelRailPressureCommand() }, // library's FuelRailPressureCommand still has the unbounded-byte bug
            { SafeFuelConsumptionRateCommand() }, // ditto FuelConsumptionRateCommand
            { TimingAdvanceCommand() }, // not in the library; added for octane/knock-retard checks
        )

    /** Sample roughly every 10-30 seconds; slow-changing context. */
    fun tierC(): List<() -> ObdCommand> =
        listOf(
            { BarometricPressureCommand() },
            { AmbientAirTemperatureCommand() },
            { SafeEngineRuntimeCommand() }, // library's RuntimeCommand queries the wrong PID (0F, not 1F)
            { SafeDistanceSinceCodesClearedCommand() }, // library's version still has the unbounded-byte bug
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
            // The ECU's own declared list of which Mode 01 PIDs it supports (a real Mode 01
            // PID 00/20/40/60/80 bitmask query, not an inference from trying a PID and seeing
            // what comes back). Added specifically to settle whether LONG_TERM_BANK_1 (PID 08,
            // in the 01-20 range) is genuinely absent from this ECU vs. present-but-not-
            // answering, see KNOWN_ISSUES.md. Covers every PID this app polls, not just that one.
            { AvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_01_TO_20) },
            { AvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_21_TO_40) },
            { AvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_41_TO_60) },
            { AvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_61_TO_80) },
            { AvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_81_TO_A0) },
        )
}

/** Marker used by [PidScheduler] to decide whether a NonNumericResponseException is expected noise. */
internal typealias ExpectedParseNoise = NonNumericResponseException
