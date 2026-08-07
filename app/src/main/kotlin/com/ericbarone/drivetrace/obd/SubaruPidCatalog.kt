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
import com.github.eltonvs.obd.command.control.VINCommand
import com.github.eltonvs.obd.command.fuel.FuelTypeCommand
import com.github.eltonvs.obd.command.control.MILOnCommand

/**
 * 2014 Subaru Outback 2.5i (FB25, naturally aspirated flat-4). Untested as of writing, this is a
 * best-effort starting catalog built from the Mazda 6 catalog plus the one substantive, known
 * difference: unlike the Mazda's single-bank inline-4, this is a genuine two-bank boxer engine,
 * and boxer engines are well known for legitimate bank-to-bank fuel trim asymmetry from unequal
 * exhaust runner lengths, so Bank 2 trim here is a real diagnostic signal, not noise the way a
 * Bank 2 request was on the Mazda (see KNOWN_ISSUES.md's LTFT saga: asking for a PID that's
 * simply wrong and asking for a PID that's correct but for a bank that doesn't exist produce the
 * identical symptom, NO DATA every time, so don't assume this catalog is right just because it
 * runs without errors). No SKYACTIV-style octane/timing-retard story here either, this engine is
 * naturally aspirated and factory-rated for regular 87 octane, Timing Advance is kept anyway
 * since it's a cheap, generically useful knock-related signal on any engine, just without that
 * specific backstory attached.
 *
 * Before trusting anything from this catalog on a real drive: run the one-time
 * SafeAvailablePIDsCommand reads first and check what this ECU actually declares supported,
 * don't assume the Mazda's results (or this catalog's PID choices) transfer. VIN is included
 * here, unlike MazdaPidCatalog: there's no evidence yet that it fails on this vehicle, removing
 * it was a Mazda-specific finding, not a project-wide default.
 */
object SubaruPidCatalog : PidCatalog {
    override fun tierA(): List<() -> ObdCommand> =
        listOf(
            { RPMCommand() },
            { SpeedCommand() },
            { LoadCommand() },
            { SafeMassAirFlowCommand() }, // library's MassAirFlowCommand still has the unbounded-byte bug
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
            { SafeLongTermFuelTrimBank1Command() }, // library's LONG_TERM_BANK_1 points at the wrong PID, see SafeCommands.kt
            // Bank 2, meaningful on this two-bank boxer engine unlike the Mazda's single bank.
            { SafeShortTermFuelTrimBank2Command() }, // library's SHORT_TERM_BANK_2 also points at the wrong PID
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.LONG_TERM_BANK_2) }, // library's PID here (09) is correct
            { CommandedEquivalenceRatioCommand() },
            { SafeModuleVoltageCommand() }, // library's ModuleVoltageCommand still has the unbounded-byte bug
            { ThrottlePositionCommand() }, // see MazdaPidCatalog for why this belongs in Tier A, not B
        )

    override fun tierB(): List<() -> ObdCommand> =
        listOf(
            { EngineCoolantTemperatureCommand() },
            { AirIntakeTemperatureCommand() },
            { IntakeManifoldPressureCommand() },
            { SafeFuelRailPressureCommand() }, // library's FuelRailPressureCommand still has the unbounded-byte bug
            { SafeFuelConsumptionRateCommand() }, // ditto FuelConsumptionRateCommand
            { TimingAdvanceCommand() },
            { SafeAbsoluteLoadCommand() }, // library's AbsoluteLoadCommand has the unbounded-byte bug too
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

    override fun oneTimeReadOnly(): List<() -> ObdCommand> =
        listOf(
            { VINCommand() }, // unlike MazdaPidCatalog, no evidence yet this fails on this vehicle
            { FuelTypeCommand() },
            { MILOnCommand() },
            { TroubleCodesCommand() },
            { PendingTroubleCodesCommand() },
            { PermanentTroubleCodesCommand() },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_01_TO_20) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_21_TO_40) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_41_TO_60) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_61_TO_80) },
            { SafeAvailablePIDsCommand(AvailablePIDsCommand.AvailablePIDsRanges.PIDS_81_TO_A0) },
        )
}
