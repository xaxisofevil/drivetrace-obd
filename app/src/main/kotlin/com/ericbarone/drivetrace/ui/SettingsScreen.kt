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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ericbarone.drivetrace.service.AutomationReceiver
import com.ericbarone.drivetrace.ui.components.Caption
import com.ericbarone.drivetrace.ui.components.HeaderBar
import com.ericbarone.drivetrace.ui.components.InstrumentPanel
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.SectionLabel
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
        Caption("MacroDroid or Tasker can start and stop a drive with this. docs/AUTOMATION.md has the recipe.")
    }
}

private fun copyAutomationToken(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DriveTrace automation token", token))
    // Android 13 shows its own copy confirmation, and a Toast on top of it is a duplicate.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Automation token copied", Toast.LENGTH_SHORT).show()
    }
}
