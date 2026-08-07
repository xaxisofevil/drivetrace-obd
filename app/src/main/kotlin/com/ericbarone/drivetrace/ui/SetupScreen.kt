package com.ericbarone.drivetrace.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ericbarone.drivetrace.obd.BluetoothTransport
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.ui.components.ActionBar
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.EmptyState
import com.ericbarone.drivetrace.ui.components.Glyph
import com.ericbarone.drivetrace.ui.components.GlyphMark
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.PrimaryAction
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
import com.ericbarone.drivetrace.ui.components.StatusBand
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.Ash
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.DriveTraceShapes
import com.ericbarone.drivetrace.ui.theme.Hairline
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.PanelActive
import com.ericbarone.drivetrace.ui.theme.Slate
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
 * Pre-flight. Two decisions (which car, which adapter) and one action, so the screen is laid out
 * as two labelled config sections over a pinned action bar rather than as a scrolling column of
 * controls. See docs/DESIGN_SYSTEM.md.
 */
@Composable
fun SetupScreen(onStartLogging: (String, VehicleProfile) -> Unit, onShowHistory: () -> Unit) {
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
    val highContrast by DisplaySettings.highContrast.collectAsState()

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
            trailing = {
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
            SectionLabel("Display")

            // One row, and it belongs here rather than behind a settings screen: the choice is
            // "is it sunny right now", which is answered while sitting in the car about to press
            // Start, not once during onboarding.
            ToggleRow(
                checked = highContrast,
                onToggle = { DisplaySettings.setHighContrast(context, it) },
                title = "Daylight readout boost",
                detail = "Brighter hero numerals for direct sun. Background stays dark.",
            )

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

/**
 * One choice in a config section. Replaces the stock RadioButton row: the whole panel is the
 * target (not a 20dp circle), selection is carried by three signals at once (accent bar, border,
 * fill) so it survives a glance, and `role = Role.RadioButton` keeps the single-choice semantics
 * TalkBack needs now that the RadioButton widget itself is gone.
 */
@Composable
private fun SelectableRow(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    detailIsMachine: Boolean = false,
    flagged: Boolean = false,
) {
    val type = LocalReadoutType.current
    InstrumentPanel(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        accent = if (selected) AccentMixture else null,
        fill = if (selected) PanelActive else com.ericbarone.drivetrace.ui.theme.Panel,
        border = if (selected) AccentMixture.copy(alpha = 0.45f) else Hairline,
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionMark(selected)
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = type.small,
                    color = if (selected) Chalk else Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = if (detailIsMachine) type.mono else type.unit,
                    color = Slate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (flagged && !selected) {
                Text("LIKELY", style = type.label, color = Ash)
            }
        }
    }
}

/**
 * A binary setting. Same panel-is-the-target treatment as [SelectableRow] and the same three
 * redundant selection signals, with `Role.Switch` instead of `Role.RadioButton` so TalkBack
 * announces it as a toggle. Deliberately not a Material `Switch`: an M3 switch is a 52x32dp pill
 * with a sliding thumb, which is the one shape this design system has ruled out everywhere else.
 */
@Composable
private fun ToggleRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val type = LocalReadoutType.current
    InstrumentPanel(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
        accent = if (checked) AccentMixture else null,
        fill = if (checked) PanelActive else com.ericbarone.drivetrace.ui.theme.Panel,
        border = if (checked) AccentMixture.copy(alpha = 0.45f) else Hairline,
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckMark(checked)
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = type.small,
                    color = if (checked) Chalk else Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail, style = type.unit, color = Slate, maxLines = 2)
            }
        }
    }
}

/** On/off indicator. Square with the chip radius, so it can never be confused with the round
 *  single-choice [SelectionMark] two rows above it. */
@Composable
private fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(DriveTraceShapes.chip)
            .background(if (checked) AccentMixture else Color.Transparent)
            .border(1.5.dp, if (checked) AccentMixture else Hairline, DriveTraceShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) GlyphMark(Glyph.TICK, Ink, sizeDp = 12)
    }
}

/** Selection indicator, drawn rather than borrowed from Material, so its ring weight matches the
 *  1dp hairline language the panels use. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val ring: Color = if (selected) AccentMixture else Hairline
    val core: Color = if (selected) AccentMixture else Color.Transparent
    Box(modifier = modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ring, radius = size.minDimension / 2f - 1f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            if (core != Color.Transparent) {
                drawCircle(core, radius = size.minDimension * 0.22f, center = c)
            }
        }
    }
}
