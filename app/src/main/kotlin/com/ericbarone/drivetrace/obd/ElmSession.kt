package com.ericbarone.drivetrace.obd

import com.github.eltonvs.obd.command.AdaptiveTimingMode
import com.github.eltonvs.obd.command.ObdProtocols
import com.github.eltonvs.obd.command.Switcher
import com.github.eltonvs.obd.command.at.DescribeProtocolCommand
import com.github.eltonvs.obd.command.at.ResetAdapterCommand
import com.github.eltonvs.obd.command.at.SelectProtocolCommand
import com.github.eltonvs.obd.command.at.SetAdaptiveTimingCommand
import com.github.eltonvs.obd.command.at.SetEchoCommand
import com.github.eltonvs.obd.command.at.SetHeadersCommand
import com.github.eltonvs.obd.command.at.SetLineFeedCommand
import com.github.eltonvs.obd.command.at.SetSpacesCommand
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import java.io.InputStream
import java.io.OutputStream

data class ElmInitResult(val protocol: String)

/**
 * Runs the ELM327 initialization sequence from the blueprint (section 5): reset, quiet the
 * adapter down to raw data only, auto-detect protocol, enable adaptive timing. Individual AT
 * command failures are tolerated (cheap clones vary), only a hard IO failure should bubble up.
 */
class ElmSession(inputStream: InputStream, outputStream: OutputStream) {
    val connection = ObdDeviceConnection(inputStream, outputStream)

    suspend fun initialize(): ElmInitResult {
        runQuiet { connection.run(ResetAdapterCommand()) }
        runQuiet { connection.run(SetEchoCommand(Switcher.OFF)) }
        runQuiet { connection.run(SetLineFeedCommand(Switcher.OFF)) }
        runQuiet { connection.run(SetSpacesCommand(Switcher.OFF)) }
        runQuiet { connection.run(SetHeadersCommand(Switcher.OFF)) }
        runQuiet { connection.run(SelectProtocolCommand(ObdProtocols.AUTO)) }
        runQuiet { connection.run(SetAdaptiveTimingCommand(AdaptiveTimingMode.AUTO_1)) }

        val protocol = try {
            connection.run(DescribeProtocolCommand()).value
        } catch (e: Exception) {
            "UNKNOWN"
        }
        return ElmInitResult(protocol = protocol)
    }

    /** AT setup commands are best-effort; a clone that doesn't understand one shouldn't abort init. */
    private suspend fun runQuiet(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // ignored - logged at a higher level if it matters
        }
    }
}
