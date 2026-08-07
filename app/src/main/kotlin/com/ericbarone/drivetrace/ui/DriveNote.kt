package com.ericbarone.drivetrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ericbarone.drivetrace.data.AppDatabase
import com.ericbarone.drivetrace.ui.components.GlyphMark
import com.ericbarone.drivetrace.ui.components.NoteField
import com.ericbarone.drivetrace.ui.components.SecondaryAction
import com.ericbarone.drivetrace.ui.components.Tone
import com.ericbarone.drivetrace.ui.theme.LocalReadoutType
import com.ericbarone.drivetrace.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Add or change a drive's note after the fact.
 *
 * Until now the note was write-once, typed into the Stop dialog and then frozen forever. Stop is
 * still the best single moment to catch one (the drive is in the driver's head and nowhere else),
 * but it is a bad *only* moment: the thing worth writing down is often the thing you work out on
 * the walk back from the car, and a dialog with a Stop button in it is not where anyone composes
 * a careful sentence. So the same field now reappears on both screens that show a drive, seeded
 * with whatever is already stored.
 *
 * Reuses [NoteField] rather than adding an editing variant of it, so the hard 120-character cap
 * and the panel-slot styling stay in one place: a note edited days later has to fit the same
 * two-line logbook card as one typed at Stop.
 *
 * Saving is explicit rather than on-blur or per-keystroke. A silent autosave to a field the user
 * cannot see the state of is exactly the interaction that makes someone re-open a screen to check
 * whether it took, and the Save control doubles as the only place the result is confirmed. It
 * appears only when the draft actually differs from what is stored, so the resting state of a
 * drive whose note is already right is a plain field with nothing shouting next to it.
 *
 * [initialNote] short-circuits the read when the caller already holds the row (HistoryScreen has
 * the whole [com.ericbarone.drivetrace.data.SessionEntity] in hand). Pass null and this reads it
 * itself, which is what the trip report needs: the note written into the Stop dialog was applied
 * to the row by DriveLoggingService after this screen was already composed.
 */
@Composable
fun DriveNoteEditor(
    sessionId: Long,
    modifier: Modifier = Modifier,
    initialNote: String? = null,
    onSaved: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stored by remember(sessionId) { mutableStateOf(initialNote.orEmpty()) }
    var draft by remember(sessionId) { mutableStateOf(initialNote.orEmpty()) }
    var saving by remember(sessionId) { mutableStateOf(false) }

    if (initialNote == null) {
        LaunchedEffect(sessionId) {
            val existing = AppDatabase.getInstance(context)
                .sessionDao()
                .getSession(sessionId)
                ?.notes
                .orEmpty()
            stored = existing
            // Only seed the draft if the user has not started typing into it already, so a slow
            // read can never overwrite something half-typed.
            if (draft.isEmpty()) draft = existing
        }
    }

    val dirty = draft.trim() != stored.trim()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        NoteField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "Cold start, highway, 93 octane",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            if (dirty) {
                SecondaryAction(
                    text = if (saving) "Saving..." else "Save note",
                    enabled = !saving,
                    onClick = {
                        saving = true
                        val text = draft.trim()
                        scope.launch {
                            val dao = AppDatabase.getInstance(context).sessionDao()
                            dao.getSession(sessionId)?.let { session ->
                                // A note cleared to empty stores null rather than "", so the
                                // "has a note" check every reader already does (isNotBlank) keeps
                                // working and the CSV's metadata.json keeps emitting null.
                                dao.updateSession(session.copy(notes = text.takeIf { it.isNotBlank() }))
                            }
                            stored = text
                            saving = false
                            onSaved(text)
                        }
                    },
                    minHeight = Space.compactTarget,
                )
            } else if (stored.isNotBlank()) {
                // Not a StatusBand, not a toast. The confirmation that a note is on the row is
                // Tier C by any reading, so it gets a glyph and a caption-weight line and stops
                // there. It is also achromatic-adjacent by ISA-101's rule: this is the normal
                // state, so it should not be the brightest thing in its section.
                GlyphMark(Tone.LIVE.glyph, Tone.LIVE.color, sizeDp = 12)
                Text("saved to this drive", style = LocalReadoutType.current.mono, color = Tone.LIVE.color)
            }
        }
    }
}
