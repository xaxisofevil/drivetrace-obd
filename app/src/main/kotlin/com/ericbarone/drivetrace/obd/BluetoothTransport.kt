package com.ericbarone.drivetrace.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Standard Bluetooth Classic Serial Port Profile UUID used by every ELM327 clone. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Wraps an RFCOMM socket to a previously bonded ELM327 adapter. We never scan for devices,
 * only connect to ones already paired in Android's Bluetooth settings.
 */
class BluetoothTransport(private val context: Context) {
    private var socket: BluetoothSocket? = null

    val inputStream: InputStream? get() = socket?.inputStream
    val outputStream: OutputStream? get() = socket?.outputStream
    val isConnected: Boolean get() = socket?.isConnected == true

    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission") // caller must have already checked BLUETOOTH_CONNECT
    suspend fun connect(deviceAddress: String) {
        withContext(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: error("No Bluetooth adapter on this device")
            val device = adapter.getRemoteDevice(deviceAddress)
            // Deliberately no cancelDiscovery() here: we never scan (bonded-devices-only
            // per the blueprint), and cancelDiscovery() itself requires BLUETOOTH_SCAN,
            // a permission this app never requests since it has no use for it otherwise.
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            newSocket.connect()
            socket = newSocket
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // best effort
        }
        socket = null
    }
}
