package com.lifetrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalSession
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Editor drafts survive rotation and lock cycles via rememberSaveable (the Prompt 9 convention). */
private data class SessionEditorDraft(
    val id: Long = 0,
    val uuid: String = "",
    val title: String = "",
    val description: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val startTimeText: String = "17:00",
    val endDate: LocalDate = LocalDate.now(),
    val endTimeText: String = "18:30",
    val timezoneId: String = ZoneId.systemDefault().id,
    val goalId: Long = 0L,
    val phaseId: Long = 0L,
    val taskId: Long = 0L,
    val startReminder: Boolean = true,
    val endCheckIn: Boolean = true
)

/** The session editor: create, edit, or create-with-prefilled goal/task context. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditorScreen(vm: SessionViewModel = hiltViewModel(), editUuid: String? = null,
                                 prefillGoalId: Long = 0L, prefillTaskId: Long = 0L,
                                 onBack: () -> Unit, onSaved: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    val goals by vm.observeGoals().collectAsStateWithLifecycle(initialValue = emptyList())

    var draft by remember { mutableStateOf(SessionEditorDraft()) }
    var loaded by remember { mutableStateOf(editUuid == null) }
    var titleError by rememberSaveable { mutableStateOf<String?>(null) }
    var timeError by rememberSaveable { mutableStateOf<String?>(null) }

    // Prefill from navigation context — only when the caller supplied it, never "recent goal".
    LaunchedEffect(prefillGoalId, prefillTaskId) {
        if (prefillGoalId > 0 || prefillTaskId > 0) {
            draft = draft.copy(
                goalId = prefillGoalId,
                taskId = prefillTaskId,
                startDate = draft.startDate.plusDays(1),
                endDate = draft.endDate.plusDays(1)
            )
        }
    }
    LaunchedEffect(editUuid) {
        if (editUuid != null && !loaded) {
            runCatching { vm.findSession(editUuid) }.getOrNull()?.let { session ->
                draft = SessionEditorDraft(
                    id = session.id, uuid = session.uuid, title = session.title,
                    description = session.description.orEmpty(),
                    startDate = session.plannedStartDate, startTimeText = session.plannedStartTime.toString(),
                    endDate = session.plannedEndDate, endTimeText = session.plannedEndTime.toString(),
                    timezoneId = session.timezoneId,
                    goalId = session.goalId ?: 0L, phaseId = session.phaseId ?: 0L, taskId = session.taskId ?: 0L,
                    startReminder = session.startReminder, endCheckIn = session.endCheckIn
                )
                loaded = true
            }
        }
    }

    val phases = if (draft.goalId > 0) vm.phasesForGoal(draft.goalId).collectAsStateWithLifecycle(initialValue = emptyList()).value else emptyList()
    val tasks = if (draft.goalId > 0) vm.tasksForGoal(draft.goalId).collectAsStateWithLifecycle(initialValue = emptyList()).value else emptyList()

    fun parseTime(text: String): LocalTime? = runCatching {
        val parts = text.trim().split(':')
        require(parts.size == 2)
        LocalTime.of(parts[0].trim().toInt(), parts[1].trim().toInt())
    }.getOrNull()

    fun validateAndSave() {
        var valid = true
        if (draft.title.isBlank() || draft.title.length > 120) {
            titleError = "Session titles must contain 1–120 characters."; valid = false
        } else titleError = null
        val start = parseTime(draft.startTimeText)
        val end = parseTime(draft.endTimeText)
        if (start == null || end == null) {
            timeError = "Times must be HH:mm (24-hour), e.g. 17:00."; valid = false
        } else {
            val durationMinutes = java.time.Duration.between(
                draft.startDate.atTime(start), draft.endDate.atTime(end)).toMinutes()
            if (durationMinutes < 1) {
                timeError = "A session cannot end before or exactly when it starts."; valid = false
            } else if (durationMinutes > 1_440) {
                timeError = "A session must last at most 24 hours."; valid = false
            } else timeError = null
        }
        if (!valid) return
        vm.saveSession(GoalSession(
            id = draft.id,
            uuid = draft.uuid,
            title = draft.title.trim(),
            description = draft.description.trim().takeIf { it.isNotEmpty() },
            plannedStartDate = draft.startDate, plannedStartTime = start ?: LocalTime.NOON,
            plannedEndDate = draft.endDate, plannedEndTime = end ?: LocalTime.NOON,
            timezoneId = draft.timezoneId,
            goalId = draft.goalId.takeIf { it > 0 },
            phaseId = draft.phaseId.takeIf { it > 0 },
            taskId = draft.taskId.takeIf { it > 0 },
            startReminder = draft.startReminder, endCheckIn = draft.endCheckIn
        )) { onSaved() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (editUuid == null) "Schedule session" else "Edit session") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                draft.title, { draft = draft.copy(title = it.take(120)); },
                label = { Text("Title") },
                isError = titleError != null,
                supportingText = { titleError?.let { Text(it) } },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                draft.description, { draft = draft.copy(description = it.take(2000)) },
                label = { Text("Description (optional)") }, minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            DateField("Start date", draft.startDate, "Today", enabled = true) { draft = draft.copy(startDate = it ?: draft.startDate) }
            OutlinedTextField(
                draft.startTimeText, { draft = draft.copy(startTimeText = it) },
                label = { Text("Start time (HH:mm)") }, isError = timeError != null,
                supportingText = { timeError?.let { Text(it) } },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            DateField("End date", draft.endDate, "Same day", enabled = true) { draft = draft.copy(endDate = it ?: draft.endDate) }
            OutlinedTextField(
                draft.endTimeText, { draft = draft.copy(endTimeText = it) },
                label = { Text("End time (HH:mm)") }, isError = timeError != null,
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            // The session's own zone is recorded at creation and never reinterpreted (PRD §6.G):
            // a curated IANA list keeps the control simple while staying timezone-correct.
            TimezonePicker(draft.timezoneId) { draft = draft.copy(timezoneId = it) }
            if (goals.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Link to your goals (optional)", style = MaterialTheme.typography.titleSmall)
                        PickerRow("Goal", goals.map { it.id to it.title }, draft.goalId) { selectedId ->
                            draft = draft.copy(goalId = selectedId, phaseId = 0L, taskId = 0L)
                        }
                        if (draft.goalId > 0 && phases.isNotEmpty()) {
                            PickerRow("Phase", phases.map { it.id to it.name }, draft.phaseId) { selectedId ->
                                draft = draft.copy(phaseId = selectedId)
                            }
                        }
                        if (draft.goalId > 0 && tasks.isNotEmpty()) {
                            PickerRow("Task", tasks.map { it.id to it.title }, draft.taskId) { selectedId ->
                                draft = draft.copy(taskId = selectedId)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Start reminder", style = MaterialTheme.typography.bodyMedium)
                    Text("Best-effort notification at the planned start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = draft.startReminder, onCheckedChange = { draft = draft.copy(startReminder = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("End check-in", style = MaterialTheme.typography.bodyMedium)
                    Text("Best-effort notification at the planned end",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = draft.endCheckIn, onCheckedChange = { draft = draft.copy(endCheckIn = it) })
            }
            operationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy, onClick = ::validateAndSave, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Saving…" else "Save session")
                }
                Button(enabled = !busy, onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** Time zone picker: device default plus common zones — IANA ids only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimezonePicker(current: String, onSelect: (String) -> Unit) {
    val zones = listOf(ZoneId.systemDefault().id, "UTC", "America/New_York", "America/Chicago",
        "America/Los_Angeles", "Europe/London", "Europe/Berlin", "Europe/Istanbul", "Asia/Dubai",
        "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney").distinct()
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            current, {},
            label = { Text("Time zone") }, readOnly = true, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            zones.forEach { zone ->
                DropdownMenuItem(
                    text = { Text(zone + if (zone == ZoneId.systemDefault().id) " (device)" else "") },
                    onClick = { onSelect(zone); expanded = false }
                )
            }
        }
    }
}

/** Single-select dropdown row over labeled id options ("None" always present for clearing). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(label: String, options: List<Pair<Long, String>>, selectedId: Long,
                      onSelect: (Long) -> Unit) {
    var expanded by rememberSaveable("$label-expanded") { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selectedId }?.second ?: "None"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            "$label: $current", {}, readOnly = true, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onSelect(0L); expanded = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
