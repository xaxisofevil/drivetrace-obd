package com.ericbarone.drivetrace.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.obd.BluetoothTransport

private const val PREFS_NAME = "drivetrace_prefs"
private const val PREF_LAST_DEVICE = "last_device_address"

private fun requiredPermissions(): Array<String> =
    buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

private fun hasAllPermissions(context: Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * There's only ever one real OBD adapter among the bonded devices (the rest are earbuds,
 * other cars' kits, etc.), so default to it by name rather than making every session start
 * with hunting through the full bonded-devices list.
 */
private fun defaultObdDeviceAddress(devices: List<BluetoothDevice>): String? =
    devices.firstOrNull { it.name?.contains("OBD", ignoreCase = true) == true }?.address

@Composable
fun SetupScreen(onStartLogging: (String) -> Unit, onShowHistory: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context)) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedAddress by rememberSaveable { mutableStateOf(prefs.getString(PREF_LAST_DEVICE, null)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            devices = BluetoothTransport(context).bondedDevices()
            if (selectedAddress == null) selectedAddress = defaultObdDeviceAddress(devices)
        }
    }

    if (permissionsGranted && devices.isEmpty()) {
        devices = BluetoothTransport(context).bondedDevices()
        if (selectedAddress == null) selectedAddress = defaultObdDeviceAddress(devices)
    }

    // See LoggingScreen.kt for why systemBarsPadding() comes first: content was drawing
    // straight under the status/nav bars, not just missing a few dp of breathing room.
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DriveTrace", style = MaterialTheme.typography.headlineMedium)
        Text("2020 Mazda 6 2.5T", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onShowHistory) { Text("Trip History") }

        if (!permissionsGranted) {
            Text("Bluetooth, location, and notification permissions are needed to log a drive.")
            Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) {
                Text("Grant permissions")
            }
            return@Column
        }

        Text("Select the paired ELM327 adapter:", style = MaterialTheme.typography.titleMedium)

        if (devices.isEmpty()) {
            Text("No bonded Bluetooth devices found. Pair your ELM327 adapter in Android's Bluetooth settings first.")
        } else {
            // weight(1f), not just fillMaxWidth: without a bounded height, this LazyColumn
            // expands to fit every bonded device and pushes Start Logging off the bottom of the
            // screen, unreachable, since it's outside the list's own internal scroll area (a
            // long bonded-device list, confirmed for real: earbuds, other cars' kits, etc. add
            // up). weight(1f) caps it to the remaining space in the outer Column, so the list
            // scrolls internally and Start Logging stays pinned below it, always visible.
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(devices) { device ->
                    val address = device.address
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = address == selectedAddress,
                                onClick = { selectedAddress = address },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = address == selectedAddress, onClick = { selectedAddress = address })
                        Column {
                            Text(device.name ?: "(unknown name)")
                            Text(address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Button(
            enabled = selectedAddress != null,
            onClick = {
                selectedAddress?.let { address ->
                    prefs.edit().putString(PREF_LAST_DEVICE, address).apply()
                    onStartLogging(address)
                }
            },
        ) {
            Text("Start Logging")
        }
    }
}
