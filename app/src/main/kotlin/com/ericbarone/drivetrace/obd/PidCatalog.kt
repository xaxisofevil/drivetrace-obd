package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.NonNumericResponseException
import com.github.eltonvs.obd.command.ObdCommand

/**
 * One vehicle's tiered PID list (see the blueprint, section 6): Tier A polled continuously,
 * Tier B every few seconds, Tier C every 10-30s. See [PidScheduler] for the interleaving logic.
 *
 * Split into a per-vehicle implementation (MazdaPidCatalog, SubaruPidCatalog, ...) rather than
 * one shared catalog with vehicle-specific branches inside it: which PIDs are worth polling, and
 * which of this library's PID-specific bugs need working around, genuinely differs per vehicle
 * (e.g. bank-2 fuel trim is meaningless noise on the Mazda 6's single-bank inline-4 but a real,
 * diagnostically meaningful signal on a two-bank boxer engine). Keeping each vehicle's list in
 * its own file keeps that vehicle's reasoning together instead of scattered through a growing
 * `when` block mixing every vehicle's assumptions in one place.
 */
interface PidCatalog {
    /** Sample as frequently as the adapter allows. */
    fun tierA(): List<() -> ObdCommand>

    /** Sample roughly every 2-5 seconds. */
    fun tierB(): List<() -> ObdCommand>

    /** Sample roughly every 10-30 seconds; slow-changing context. */
    fun tierC(): List<() -> ObdCommand>

    /** Read once at session start. Never includes anything that clears/writes to the ECU. */
    fun oneTimeReadOnly(): List<() -> ObdCommand>
}

/** Marker used by [PidScheduler] to decide whether a NonNumericResponseException is expected noise. */
internal typealias ExpectedParseNoise = NonNumericResponseException
