package com.ericbarone.drivetrace.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericbarone.drivetrace.service.AutomationReceiver
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
import com.ericbarone.drivetrace.ui.theme.AccentMixture
import com.ericbarone.drivetrace.ui.theme.Chalk
import com.ericbarone.drivetrace.ui.theme.Hairline
import com.ericbarone.drivetrace.ui.theme.Ink
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Mist
import com.ericbarone.drivetrace.ui.theme.SkinId
import com.ericbarone.drivetrace.ui.theme.Slate
import com.ericbarone.drivetrace.ui.theme.Space

/**
 * Standing configuration: the settings that get chosen once and then left alone. Everything here
 * used to sit on SetupScreen, which was scoped as two pre-flight decisions and a Start button and
 * had quietly become two decisions, two display settings, a copyable secret and a Start button.
 * See idea #13 in docs/DESIGN_SYSTEM.md for the split and section 7 for what each screen is now.
 *
 * No pinned `ActionBar`, unlike every other screen in the app: nothing here is an action to
 * commit. Each control takes effect on the tap, both display settings write through to
 * SharedPreferences immediately, and the way out is the header's back affordance, the same one
 * HistoryScreen uses and reached the same way (a `rememberSaveable` flag in MainActivity, not a
 * navigation library).
 *
 * The whole column scrolls rather than any one section owning `weight(1f)`. Setup's adapter list
 * needs that weight because a Start button below it must never be pushed off screen; there is
 * nothing below this content, and a fourth section (idea #12's auto-stop toggle is the obvious
 * next one) should lengthen the scroll rather than squeeze a list.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val skinId by DisplaySettings.skin.collectAsState()
    val highContrast by DisplaySettings.highContrast.collectAsState()
    // First read is also what generates it, so the token exists from the first time anyone could
    // want to copy it and never before.
    val automationToken = remember { AutomationReceiver.token(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
    ) {
        HeaderBar(
            title = "Settings",
            subtitle = "Display and automation",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.gutter),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Spacer(Modifier.height(Space.md))
            SectionLabel("Display")

            // A plain loop over SkinId emitting the same SelectableRow the vehicle picker uses, so
            // a third skin is one entry in SkinId and nothing on this screen.
            //
            // Above the daylight toggle because it is the outer of the two settings: the skin
            // decides what the palette is, the toggle decides how much luminance the hero spends
            // out of it.
            for (skin in SkinId.entries) {
                SelectableRow(
                    selected = skin == skinId,
                    onSelect = { DisplaySettings.setSkin(context, skin) },
                    title = skin.displayName,
                    detail = skin.description,
                )
            }

            ToggleRow(
                checked = highContrast,
                onToggle = { DisplaySettings.setHighContrast(context, it) },
                title = "Daylight readout boost",
                detail = "Brighter hero numerals for direct sun. Background stays dark.",
            )

            Spacer(Modifier.height(Space.xs))
            SectionLabel("Automation")

            AutomationTokenRow(token = automationToken)

            Spacer(Modifier.height(Space.section))
        }
    }
}

/**
 * The shared secret an automation app has to send back (see `service/AutomationReceiver.kt` and
 * docs/AUTOMATION.md), with a copy action, since the value exists to be pasted into a MacroDroid
 * "Send Intent" action on this same phone.
 *
 * Shown in full rather than masked. It is an identifier to compare character by character against
 * what landed in the macro, which is exactly what the `mono` style is for, and masking would only
 * be theatre: anyone holding the unlocked phone can press Copy regardless.
 *
 * No new container. An [InstrumentPanel] with a value and a [SecondaryAction], the same shape the
 * rest of this screen is built from.
 */
@Composable
private fun AutomationTokenRow(token: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val type = LocalReadoutType.current
    var showSetup by remember { mutableStateOf(false) }
    InstrumentPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Automation token", style = type.small, color = Mist, maxLines = 1)
                Text(token, style = type.mono, color = Slate, maxLines = 2)
            }
            Spacer(Modifier.width(Space.md))
            SecondaryAction(
                text = "Copy",
                onClick = { copyAutomationToken(context, token) },
                minHeight = Space.compactTarget,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Caption("MacroDroid or Tasker can start and stop a drive with this.")
        Spacer(Modifier.height(Space.sm))
        SecondaryAction(
            text = "How to set this up in MacroDroid",
            onClick = { showSetup = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showSetup) {
        MacroDroidSetupDialog(token = token, onDismiss = { showSetup = false })
    }
}

/**
 * The docs/AUTOMATION.md recipe, in-app, structured to match MacroDroid's own "Send Intent"
 * dialog field for field (Target, Action, Package, Class, Extra 1, Extra 2, in that exact order)
 * rather than prose, so it can be followed with the two screens open side by side. Confirmed
 * against real screenshots of that dialog, not the general docs, since a mismatched field name
 * (MacroDroid's own labels do not match Android's `Intent` terminology one for one) is exactly
 * the kind of gap that makes a first attempt fail silently.
 *
 * Target/Action/Package/Class are identical for both directions; only Extra 1's *value* changes
 * (`start` vs `stop`), so those four render once and the two commands render as two small blocks
 * underneath rather than repeating six fields twice for one real difference.
 */
@Composable
private fun MacroDroidSetupDialog(token: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink)
                .padding(Space.gutter)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MacroDroid setup", style = LocalReadoutType.current.screenTitle, color = Chalk)
                    Text(
                        "One macro to start a drive, one to stop it",
                        style = LocalReadoutType.current.unit,
                        color = Mist,
                    )
                }
                SecondaryAction(text = "Close", onClick = onDismiss, minHeight = Space.compactTarget)
            }
            Spacer(Modifier.height(Space.section))

            SectionLabel("1. Add a trigger")
            Spacer(Modifier.height(Space.sm))
            Caption(
                "In MacroDroid, create a macro and add a Trigger: Connectivity > Bluetooth > " +
                    "Device Connected, and pick the car's Bluetooth. Make a second macro the same " +
                    "way with Device Disconnected for the stop side.",
            )
            Spacer(Modifier.height(Space.section))

            SectionLabel("2. Add the action: Applications → Send Intent")
            Spacer(Modifier.height(Space.sm))
            Caption("These four fields are the same on both macros.")
            Spacer(Modifier.height(Space.md))
            SetupField(label = "Target", value = "Broadcast", copyable = false)
            SetupField(label = "Action", value = AutomationReceiver.ACTION_AUTOMATION)
            SetupField(label = "Package", value = "com.ericbarone.drivetrace")
            SetupField(label = "Class", value = "com.ericbarone.drivetrace.service.AutomationReceiver")
            Spacer(Modifier.height(Space.sm))
            Caption("Leave Data and MIME type blank.")
            Spacer(Modifier.height(Space.section))

            SectionLabel("3. The two Extras")
            Spacer(Modifier.height(Space.sm))
            Caption("Every macro needs both Extra 1 and Extra 2. Only Extra 1's value differs.")
            Spacer(Modifier.height(Space.md))

            CommandBlock(title = "Start-drive macro", command = AutomationReceiver.COMMAND_START, token = token)
            Spacer(Modifier.height(Space.md))
            CommandBlock(title = "Stop-drive macro", command = AutomationReceiver.COMMAND_STOP, token = token)

            Spacer(Modifier.height(Space.section))
            Caption(
                "The token is this phone's shared secret: MacroDroid has to send it back exactly, " +
                    "or DriveTrace ignores the command. Full reference: docs/AUTOMATION.md.",
            )
            Spacer(Modifier.height(Space.section))
        }
    }
}

@Composable
private fun CommandBlock(title: String, command: String, token: String, modifier: Modifier = Modifier) {
    InstrumentPanel(
        modifier = modifier.fillMaxWidth(),
        accent = AccentMixture,
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
    ) {
        Text(title, style = LocalReadoutType.current.small, color = Chalk)
        Spacer(Modifier.height(Space.sm))
        SetupField(label = "Extra 1 name", value = AutomationReceiver.EXTRA_COMMAND)
        SetupField(label = "Extra 1 value", value = command)
        SetupField(label = "Extra 2 name", value = AutomationReceiver.EXTRA_TOKEN)
        SetupField(label = "Extra 2 value", value = token)
    }
}

/** One MacroDroid field, labelled exactly as that dialog labels it, with its own copy button so
 * each value can be pasted in without retyping or, worse, transcribing the token by eye. */
@Composable
private fun SetupField(label: String, value: String, copyable: Boolean = true, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val type = LocalReadoutType.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = type.label, color = Mist)
            Text(value, style = type.mono, color = Chalk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (copyable) {
            Spacer(Modifier.width(Space.sm))
            SecondaryAction(
                text = "Copy",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    }
                },
                minHeight = Space.compactTarget,
            )
        }
    }
    Spacer(Modifier.height(Space.xs))
    androidx.compose.material3.HorizontalDivider(thickness = Space.hairline, color = Hairline)
}

private fun copyAutomationToken(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DriveTrace automation token", token))
    // Android 13 shows its own copy confirmation, and a Toast on top of it is a duplicate.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Automation token copied", Toast.LENGTH_SHORT).show()
    }
}
