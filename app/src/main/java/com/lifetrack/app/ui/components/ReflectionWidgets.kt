package com.lifetrack.app.ui.components

// ---------------------------------------------------------------------------
// Daily reflections: the day's optional mood and journal, rendered in a sunken
// parchment well. Moods are self-reported feelings on a 1–5 scale — never a
// diagnosis, and never treated as the cause or the consequence of anything.
// ---------------------------------------------------------------------------

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifetrack.app.data.DailyReflection
import com.lifetrack.app.ui.AlmanacType
import com.lifetrack.app.ui.almanacColors
import com.lifetrack.app.ui.almanacFieldColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Emoji for a self-reported 1–5 mood; anything outside the scale renders as a quiet dot. */
internal fun moodEmoji(mood: Int): String = when (mood) {
    1 -> "😔"
    2 -> "😕"
    3 -> "😐"
    4 -> "🙂"
    5 -> "😄"
    else -> "·"
}

/** Plain-language label matching the emoji scale, used in headlines and accessibility text. */
internal fun moodLabel(mood: Int): String = when (mood) {
    1 -> "Very low"
    2 -> "Low"
    3 -> "Neutral"
    4 -> "Good"
    5 -> "Great"
    else -> "Unknown"
}

/**
 * One row of five mood choices on the self-reported 1–5 scale. Each choice is a 48dp circle that
 * carries the meaning for TalkBack (the emoji is decoration only) with a radio-button role, so
 * assistive tech reads one selected-aware stop per mood.
 */
@Composable
fun MoodSelector(
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        (1..5).forEach { mood ->
            val isSelected = selected == mood
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton
                    ) { onSelect(mood) }
                    .border(1.dp, if (isSelected) Color.Transparent else hairline, CircleShape)
                    .semantics {
                        // selectable(selected = …) already announces the state; keep the description to meaning only.
                        contentDescription = "Mood $mood of 5: ${moodLabel(mood)}"
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    moodEmoji(mood),
                    fontSize = 22.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
    }
}

/**
 * Sunken parchment well for one day's reflection. Empty days invite entry ("How was the day?") with
 * the editor already open; days with a saved reflection read it back — mood headline, journal, mono
 * date — behind an explicit Edit affordance. Saving hands the draft to the caller without touching
 * the repository, and removal always passes a confirmation dialog. Drafts reset whenever the stored
 * reflection changes underneath (save, import, undo) and re-seed per date; the caller's
 * SaveableStateProvider owns process-death restoration, so plain remember keeps the memory honest.
 */
@Composable
fun ReflectionEditorCard(
    date: LocalDate,
    today: LocalDate,
    reflection: DailyReflection?,
    onSave: (mood: Int?, journal: String?) -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val almanac = almanacColors()
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()) }
    val dateLabel = remember(date, dateFormat) { date.format(dateFormat) }

    // Drafts re-seed from the stored reflection whenever the date or any stored field changes
    // (save, import, undo); identical values keep the draft across unrelated recompositions.
    // rememberSaveable keeps the draft alive across scroll-away, tab switches and process death —
    // a half-typed journal must not vanish because a LazyColumn item scrolled out of the viewport.
    var draftMood by rememberSaveable(date, reflection?.updatedAt, reflection?.journal, reflection?.mood) {
        mutableStateOf(reflection?.mood)
    }
    var draftJournal by rememberSaveable(date, reflection?.updatedAt, reflection?.journal, reflection?.mood) {
        mutableStateOf(reflection?.journal.orEmpty())
    }
    // Saved days stay in reading mode until "Edit"; empty days invite with a button rather than
    // auto-opening a text field inside the scrollable Today list (which fights list scrolling).
    var editing by rememberSaveable(date, reflection?.updatedAt, reflection?.journal, reflection?.mood) {
        mutableStateOf(false)
    }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    // The repository trims and requires at least one field; mirror those rules so the
    // Save button only lights up for a change the caller can actually persist.
    val normalizedJournal = draftJournal.trim().takeIf { it.isNotEmpty() }
    val changed = draftMood != reflection?.mood || normalizedJournal != reflection?.journal
    val hasContent = draftMood != null || draftJournal.isNotBlank()
    val saveEnabled = enabled && changed && hasContent

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, almanac.rule)
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(dateLabel, style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editing) {
                if (date == today) {
                    Text("How was the day?", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("How was that day?", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Optional. Moods and notes stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MoodSelector(
                    selected = draftMood,
                    onSelect = { draftMood = it },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draftJournal,
                    onValueChange = { if (it.length <= 2_000) draftJournal = it },
                    label = { Text("Journal · optional") },
                    supportingText = {
                        Text(
                            "${draftJournal.length} / 2000",
                            style = AlmanacType.monoData,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    enabled = enabled,
                    colors = almanacFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onSave(draftMood, draftJournal.ifBlank { null }) },
                    enabled = saveEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Save reflection") }
                // Cancel restores the saved reflection, or closes the invitation on an empty day.
                TextButton(
                    enabled = enabled,
                    onClick = {
                        draftMood = reflection?.mood
                        draftJournal = reflection?.journal.orEmpty()
                        editing = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel") }
            } else {
                val mood = reflection?.mood
                if (mood != null) {
                    Text("${moodEmoji(mood)} ${moodLabel(mood)}", style = MaterialTheme.typography.titleMedium)
                }
                reflection?.journal?.let { journal ->
                    Text(journal, style = MaterialTheme.typography.bodyMedium)
                }
                if (reflection == null) {
                    Text(
                        if (date == today) "How was the day?" else "How was that day?",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Optional. Moods and notes stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(enabled = enabled, onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Add a reflection")
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Edit")
                    }
                    TextButton(enabled = enabled, onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this reflection?") },
            text = { Text("The mood and journal for this day will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove reflection", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}
