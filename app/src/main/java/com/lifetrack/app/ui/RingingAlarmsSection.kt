package com.lifetrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The ringing-alarm configuration section (Prompt 6, Part B): an explicit opt-in per habit. Each
 * alarm definition rings with sustained alarm audio at its chosen time until Dismiss/Snooze —
 * none of these actions ever completes the habit. The legacy lightweight reminder is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingingAlarmsSection(habitId: Long, vm: HabitAlarmViewModel) {
    val alarms by vm.alarmsFor(habitId).collectAsStateWithLifecycle(initialValue = emptyList())
    var timeText by rememberSaveable { mutableStateOf("07:00") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ringing alarms", style = MaterialTheme.typography.titleSmall)
            Text(
                "Opt-in: at each time below the device rings like an alarm clock until you " +
                    "dismiss or snooze. Dismissing never completes the habit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            alarms.forEach { alarm ->
                val time = "%02d:%02d".format(alarm.timeMinutes / 60, alarm.timeMinutes % 60)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        time + " · " + when (com.lifetrack.app.data.HabitAlarmRecurrence.fromStored(alarm.recurrence)) {
                            com.lifetrack.app.data.HabitAlarmRecurrence.DAILY -> "Every day"
                            com.lifetrack.app.data.HabitAlarmRecurrence.WEEKDAYS ->
                                "Weekdays: " + alarm.weekdays.sorted().joinToString(", ") { dayName(it) }
                            com.lifetrack.app.data.HabitAlarmRecurrence.ONCE ->
                                "Once on " + (alarm.onceDate?.toString() ?: "")
                        } + if (!alarm.enabled) " (off)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { vm.removeAlarm(alarm) },
                        modifier = Modifier.semantics { contentDescription = "Remove alarm at $time" }
                            .heightIn(min = 48.dp)
                    ) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    timeText, { timeText = it.take(5) },
                    label = { Text("New time (HH:mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorText != null,
                    supportingText = {
                        errorText?.let {
                            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(enabled = alarms.size < HabitAlarmViewModel.LIMIT, onClick = {
                    val parts = timeText.trim().split(':')
                    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                        errorText = "Times must be HH:mm (24-hour), e.g. 06:00."
                    } else if (alarms.any { it.timeMinutes == hour * 60 + minute }) {
                        errorText = "An alarm is already set for $timeText."
                    } else {
                        errorText = null
                        vm.addAlarm(habitId, hour * 60 + minute)
                    }
                }) { Text("Add") }
            }
        }
    }
}

private fun dayName(isoDay: Int): String = when (isoDay) {
    1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"; 5 -> "Fri"; 6 -> "Sat"; 7 -> "Sun"
    else -> "?"
}
