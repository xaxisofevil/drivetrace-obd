package com.ericbarone.drivetrace.obd

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
import com.github.eltonvs.obd.command.fuel.FuelTypeCommand
import com.github.eltonvs.obd.command.control.MILOnCommand

/**
 * 2020 Mazda 6 2.5T (SKYACTIV-G Turbo). Single-bank inline-4: no Bank 2 PIDs here, they don't
 * apply to this engine (see KNOWN_ISSUES.md's LTFT saga, that's exactly the confusion a
 * nonexistent-Bank-2 request causes, it looks identical to asking the wrong PID).
 */
object MazdaPidCatalog : PidCatalog {
    override fun tierA(): List<() -> ObdCommand> =
        listOf(
            { RPMCommand() }, // fixed upstream at the pinned commit (see build.gradle.kts)
            { SpeedCommand() },
            { LoadCommand() },
            { SafeMassAirFlowCommand() }, // library's MassAirFlowCommand still has the unbounded-byte bug
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
            // NOT FuelTrimCommand(...LONG_TERM_BANK_1): the library's enum has that entry
            // pointing at PID 08, which is really Short Term Fuel Trim Bank 2 per the real SAE
            // standard, a PID that doesn't exist on this single-bank engine. See SafeCommands.kt.
            { SafeLongTermFuelTrimBank1Command() },
            { CommandedEquivalenceRatioCommand() },
            { SafeModuleVoltageCommand() }, // library's ModuleVoltageCommand still has the unbounded-byte bug
            // Promoted from Tier B: confirmed directly (KNOWN_ISSUES.md) that Tier B's ~3-5s
            // nominal cadence stretched to 7-10s stale in real driving, exactly during the
            // lift-off moments a coast/brake phase classifier needs to catch. That's fast enough
            // to fool a driver-behavior feature into concluding "never coasts" when the real
            // issue was stale sensor data, not the driving itself.
            { ThrottlePositionCommand() },
        )

    override fun tierB(): List<() -> ObdCommand> =
        listOf(
            { EngineCoolantTemperatureCommand() },
            { AirIntakeTemperatureCommand() },
            { IntakeManifoldPressureCommand() },
            { SafeFuelRailPressureCommand() }, // library's FuelRailPressureCommand still has the unbounded-byte bug
            { SafeFuelConsumptionRateCommand() }, // ditto FuelConsumptionRateCommand
            { TimingAdvanceCommand() }, // not in the library; added for octane/knock-retard checks
            { SafeAbsoluteLoadCommand() }, // library's AbsoluteLoadCommand has the unbounded-byte bug too
            // Manufacturer-specific (Mode 22), community-sourced, untested: see
            // MazdaEnhancedCommands.kt for the real, substantial uncertainty behind all five.
            { KnockRetardCommand() },
            { KnockControlSystemCommand() },
            { IntakeManifoldPressureDesiredCommand() },
            { TurbochargerACompressorInletPressureCommand() },
            { TurbochargerBCompressorInletPressureCommand() },
        )

    override fun tierC(): List<() -> ObdCommand> =
        listOf(
            { BarometricPressureCommand() },
            { AmbientAirTemperatureCommand() },
            { SafeEngineRuntimeCommand() }, // library's RuntimeCommand queries the wrong PID (0F, not 1F)
            { SafeDistanceSinceCodesClearedCommand() }, // library's version still has the unbounded-byte bug
            { CommandedEgrCommand() },
            { EgrErrorCommand() },
            { FuelLevelCommand() },
            { CatalystTemperatureBank1Sensor1Command() }, // not in the library; checks for exhaust restriction
            { OilTemperatureCommand() }, // not in the library; complements coolant temp for the warm-up hypothesis
        )

    /** No VINCommand: it has never once worked on this specific vehicle (see KNOWN_ISSUES.md),
     * an always-failing read every session was pure overhead with no signal. This is a per-
     * vehicle finding, not assumed to hold for any other catalog. */
    override fun oneTimeReadOnly(): List<() -> ObdCommand> =
        listOf(
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
            // Uses SafeAvailablePIDsCommand, not the library's version: confirmed on two real
            // sessions that this adapter sometimes doubles a response, which overflowed the
            // library's naive parsing and silently failed PIDS_01_TO_20 (the range with PID 08)
            // every time it was tried, see SafeCommands.kt.
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_01_TO_20) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_21_TO_40) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_41_TO_60) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_61_TO_80) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_81_TO_A0) },
        )
}
