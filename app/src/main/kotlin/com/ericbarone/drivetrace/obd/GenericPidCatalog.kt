package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.fuel.FuelTrimCommand
import com.github.eltonvs.obd.command.pressure.BarometricPressureCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand

/**
 * Standard Mode 01 PIDs only, for a car DriveTrace has no profile for. A car announcing itself over
 * automation that matches no known profile lands here rather than on a catalog tuned for a
 * different engine: the Mazda catalog asks Mode 22 questions only a Mazda ECU answers, and every
 * unanswered one costs a round trip. Everything below is a PID the SAE standard says any OBD-II
 * car may answer, and unsupported ones drop out on their own (PidScheduler's cooldown).
 *
 * Deliberately short. Fuel trims and MAF are what the analysis scripts want first; the rest is
 * context. Promote an unassigned car to its own profile once its supported-PID list is known.
 */
object GenericPidCatalog : PidCatalog {
    override fun tierA(): List<() -> ObdCommand> =
        listOf(
            { RPMCommand() },
            { SpeedCommand() },
            { LoadCommand() },
            { SafeMassAirFlowCommand() },
            { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
            { ThrottlePositionCommand() },
        )

    override fun tierB(): List<() -> ObdCommand> =
        listOf(
            { EngineCoolantTemperatureCommand() },
            { AirIntakeTemperatureCommand() },
            { IntakeManifoldPressureCommand() },
            { SafeLongTermFuelTrimBank1Command() },
            { SafeModuleVoltageCommand() },
            { TimingAdvanceCommand() },
        )

    override fun tierC(): List<() -> ObdCommand> =
        listOf(
            { BarometricPressureCommand() },
            { AmbientAirTemperatureCommand() },
            { FuelLevelCommand() },
        )

    override fun oneTimeReadOnly(): List<() -> ObdCommand> = emptyList()
}
