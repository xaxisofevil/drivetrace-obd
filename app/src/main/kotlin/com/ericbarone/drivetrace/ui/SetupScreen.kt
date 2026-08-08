package com.ericbarone.drivetrace.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.obd.BluetoothTransport
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.ui.components.ActionBar
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.EmptyState
import com.ericbarone.drivetrace.ui.components.Glyph
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.IconAction
import com.ericbarone.drivetrace.ui.components.PrimaryAction
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
import com.ericbarone.drivetrace.ui.components.StatusBand
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.Space

// PREFS_NAME / PREF_LAST_DEVICE / PREF_VEHICLE_PROFILE / PREF_HIGH_CONTRAST live in
// DisplaySettings.kt now, same package, since the theme layer needs one of them too.

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
 * other cars' kits, etc.). Broader than a plain "OBD" substring: confirmed for real that this
 * user's actual adapter doesn't contain "OBD" in its bonded name at all, cheap ELM327 clones
 * ship under all sorts of generic or vendor names. Still just a heuristic, not a guarantee, if a
 * real adapter shows up matching none of these, the fix is broadening this list, not assuming
 * name-matching alone can ever be fully reliable.
 */
private val LIKELY_OBD_NAME_PATTERNS = listOf("obd", "elm327", "elm ", "vgate", "veepeak", "vlink", "v-link", "icar")

private fun looksLikeObdAdapter(device: BluetoothDevice): Boolean {
    val name = device.name?.lowercase() ?: return false
    return LIKELY_OBD_NAME_PATTERNS.any { name.contains(it) }
}

private fun defaultObdDeviceAddress(devices: List<BluetoothDevice>): String? =
    devices.firstOrNull { looksLikeObdAdapter(it) }?.address

/** Likely OBD adapters first (stable within each group, so ties keep the OS's bonded order)
 * rather than leaving them wherever they happen to fall in the raw bonded-devices list. */
private fun sortWithLikelyObdFirst(devices: List<BluetoothDevice>): List<BluetoothDevice> =
    devices.sortedByDescending { looksLikeObdAdapter(it) }

/**
 * Pre-flight, and nothing else. Two decisions (which car, which adapter) and one action, so the
 * screen is laid out as two labelled config sections over a pinned action bar rather than as a
 * scrolling column of controls.
 *
 * It briefly stopped being that. A Display section and an Automation section accumulated here
 * because this was the only screen with anywhere to put standing configuration, and a user
 * opening the app to start a drive was scrolling past settings to reach the one button they came
 * for. Both moved to [SettingsScreen], reached by the header's gear; see idea #13 and section 7
 * in docs/DESIGN_SYSTEM.md. Anything that is not answered in the car with the engine off, about
 * to press Start, belongs there rather than here.
 */
@Composable
fun SetupScreen(
    onStartLogging: (String, VehicleProfile) -> Unit,
    onShowHistory: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context)) }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedAddress by rememberSaveable { mutableStateOf(prefs.getString(PREF_LAST_DEVICE, null)) }
    var selectedVehicleProfile by rememberSaveable {
        val savedName = prefs.getString(PREF_VEHICLE_PROFILE, null)
        val saved = savedName?.let { name -> VehicleProfile.entries.find { it.name == name } }
        mutableStateOf(saved ?: VehicleProfile.entries.first())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            devices = sortWithLikelyObdFirst(BluetoothTransport(context).bondedDevices())
            if (selectedAddress == null) selectedAddress = defaultObdDeviceAddress(devices)
        }
    }

    if (permissionsGranted && devices.isEmpty()) {
        devices = sortWithLikelyObdFirst(BluetoothTransport(context).bondedDevices())
        if (selectedAddress == null) selectedAddress = defaultObdDeviceAddress(devices)
    }

    // systemBarsPadding() before any other padding: content was drawing straight under the
    // status/nav bars, not just missing a few dp of breathing room.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
    ) {
        HeaderBar(
            title = "DriveTrace",
            subtitle = "OBD-II drive logger",
            modifier = Modifier.padding(horizontal = Space.gutter),
            // Two actions in the one trailing slot, which is a RowScope lambda and always was, so
            // this needed no second header component and no overflow menu. The gear is a bare
            // glyph rather than a second outlined button because two word-buttons side by side in
            // a 44dp-tall header is most of the header, and Settings is the rarer of the two
            // destinations: it earns an icon, Logbook keeps its label.
            trailing = {
                IconAction(Glyph.GEAR, label = "Settings", onClick = onShowSettings, sizeDp = 20)
                Spacer(Modifier.width(Space.xs))
                SecondaryAction(
                    text = "Logbook",
                    onClick = onShowHistory,
                    minHeight = Space.compactTarget,
                )
            },
        )

        if (!permissionsGranted) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.section),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                StatusBand(
                    tone = Tone.CAUTION,
                    title = "Permissions required",
                    body = "Bluetooth, location, and notification permissions are needed to log a drive.",
                )
                Caption(
                    "Bluetooth reaches the adapter, location supplies the GPS track a drive is " +
                        "measured against, and the notification keeps logging alive with the screen off.",
                    color = Ash,
                )
            }
            ActionBar {
                PrimaryAction(
                    text = "Grant permissions",
                    onClick = { permissionLauncher.launch(requiredPermissions()) },
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Spacer(Modifier.height(Space.md))
            SectionLabel("Vehicle")

            // A plain loop, not a LazyColumn: only a couple of vehicle profiles exist, no need
            // for the bonded-devices list's scroll handling below.
            for (profile in VehicleProfile.entries) {
                SelectableRow(
                    selected = profile == selectedVehicleProfile,
                    onSelect = {
                        selectedVehicleProfile = profile
                        prefs.edit().putString(PREF_VEHICLE_PROFILE, profile.name).apply()
                    },
                    title = profile.displayName,
                    detail = "${profile.name.lowercase()} PID catalog",
                )
            }

            Spacer(Modifier.height(Space.xs))
            SectionLabel("Adapter")

            if (devices.isEmpty()) {
                EmptyState(
                    title = "No bonded devices",
                    body = "Pair your ELM327 adapter in Android's Bluetooth settings first.",
                )
            } else {
                // weight(1f), not just fillMaxWidth: without a bounded height, this LazyColumn
                // expands to fit every bonded device and pushes Start Logging off the bottom of
                // the screen, unreachable, since it's outside the list's own internal scroll area
                // (a long bonded-device list, confirmed for real: earbuds, other cars' kits, etc.
                // add up). weight(1f) caps it to the remaining space in the outer Column, so the
                // list scrolls internally and the action bar stays pinned below it, always visible.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                    contentPadding = PaddingValues(bottom = Space.md),
                ) {
                    items(devices) { device ->
                        val address = device.address
                        SelectableRow(
                            selected = address == selectedAddress,
                            onSelect = { selectedAddress = address },
                            title = device.name ?: "(unknown name)",
                            detail = address,
                            detailIsMachine = true,
                            flagged = looksLikeObdAdapter(device),
                        )
                    }
                }
            }
        }

        ActionBar {
            PrimaryAction(
                text = "Start logging",
                onClick = {
                    selectedAddress?.let { address ->
                        prefs.edit().putString(PREF_LAST_DEVICE, address).apply()
                        onStartLogging(address, selectedVehicleProfile)
                    }
                },
                enabled = selectedAddress != null,
            )
        }
    }
}
