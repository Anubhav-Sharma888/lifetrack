package com.lifetrack.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalPriority
import java.time.LocalDate

/**
 * Goal creation and editing. Drafts live in rememberSaveable so they survive rotation,
 * backgrounding and app-lock unmounts exactly like the habit editor; the existing record arrives
 * through a live flow and initializes the drafts exactly once. The target date of an existing
 * goal saves through the reschedule path, recording schedule history; a new goal's dates are
 * simply its first plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalEditorScreen(goalId: Long, vm: GoalViewModel, today: LocalDate, onBack: () -> Unit) {
    val existingFlow = remember(goalId) { vm.observeGoalForEditor(goalId) }
    val existing by existingFlow.collectAsStateWithLifecycle(initialValue = if (goalId == 0L) NEW_GOAL else null)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.clearOperationError() }

    var title by rememberSaveable { mutableStateOf(if (goalId == 0L) "" else "") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Personal") }
    var priority by rememberSaveable { mutableStateOf(GoalPriority.MEDIUM.name) }
    var hasPlannedStart by rememberSaveable { mutableStateOf(goalId == 0L) }
    var plannedStart by rememberSaveable { mutableStateOf(today) }
    var hasTargetDate by rememberSaveable { mutableStateOf(false) }
    var targetDate by rememberSaveable { mutableStateOf(today.plusWeeks(12)) }
    var initialized by rememberSaveable(goalId) { mutableStateOf(goalId == 0L) }
    LaunchedEffect(existing) {
        val current = existing
        if (!initialized && current != null) {
            title = current.title
            description = current.description ?: ""
            category = current.category
            priority = current.priority
            hasPlannedStart = current.plannedStartDate != null
            plannedStart = current.plannedStartDate ?: today
            hasTargetDate = current.targetDate != null
            targetDate = current.targetDate ?: today.plusWeeks(12)
            initialized = true
        }
    }

    var pickingDate by rememberSaveable { mutableStateOf("") } // "", "start", "target"
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    val isDirty = if (goalId == 0L) {
        title.isNotBlank() || description.isNotBlank() || category != "Personal" || priority != GoalPriority.MEDIUM.name
    } else {
        val current = existing
        current != null && initialized && (
            title != current.title ||
            description != (current.description ?: "") ||
            category != current.category ||
            priority != current.priority ||
            hasPlannedStart != (current.plannedStartDate != null) ||
            (hasPlannedStart && plannedStart != (current.plannedStartDate ?: today)) ||
            hasTargetDate != (current.targetDate != null) ||
            (hasTargetDate && targetDate != (current.targetDate ?: today.plusWeeks(12)))
        )
    }
    fun requestBack() {
        if (isDirty) discardDialog = true else onBack()
    }
    BackHandler { requestBack() }

    val titleError = when {
        title.isBlank() -> "Give the goal a title."
        title.trim().length > 120 -> "Titles are limited to 120 characters."
        else -> null
    }
    val dateError = if (hasTargetDate && plannedStart.isAfter(targetDate)) {
        "The target date cannot come before the planned start."
    } else null

    Scaffold(topBar = { TopAppBar(
        title = { Text(if (goalId == 0L) "New goal" else "Edit goal") },
        navigationIcon = { IconButton(onClick = { requestBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
    ) }) { padding ->
        if (goalId != 0L && existing == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("This goal no longer exists.", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                title, { title = it },
                label = { Text("Title") },
                supportingText = { titleError?.let { Text(it) } },
                isError = titleError != null,
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                description, { if (it.length <= 2000) description = it },
                label = { Text("Description (optional)") },
                supportingText = { Text("${description.length}/2000") },
                minLines = 2,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                category, { if (it.length <= 40) category = it },
                label = { Text("Category") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Column {
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GoalPriority.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = priority == option.name,
                            onClick = { priority = option.name },
                            shape = SegmentedButtonDefaults.itemShape(index, GoalPriority.entries.size),
                            enabled = !busy,
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Checkbox(checked = hasPlannedStart, onCheckedChange = { hasPlannedStart = it }, enabled = !busy)
                Text("Set a planned start date", style = MaterialTheme.typography.bodyMedium)
            }
            if (hasPlannedStart) {
                DateRow("Planned start", plannedStart, enabled = !busy) { pickingDate = "start" }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Checkbox(checked = hasTargetDate, onCheckedChange = { hasTargetDate = it }, enabled = !busy)
                Text("Set a target date", style = MaterialTheme.typography.bodyMedium)
            }
            if (hasTargetDate) {
                DateRow("Target date", targetDate, enabled = !busy) { pickingDate = "target" }
            }
            dateError?.let { ValidationErrorLine(it) }
            operationError?.let { ValidationErrorLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !busy && titleError == null && dateError == null,
                    onClick = {
                        val base = existing ?: NEW_GOAL
                        val draft = base.copy(
                            title = title.trim(),
                            description = description.trim().takeIf { it.isNotEmpty() },
                            category = category.trim().ifBlank { "Personal" },
                            priority = priority,
                            plannedStartDate = if (hasPlannedStart) plannedStart else null,
                            targetDate = if (hasTargetDate) targetDate else null
                        )
                        vm.saveGoalWithDates(draft) { onBack() }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Save goal")
                }
                OutlinedButton(enabled = !busy, onClick = { requestBack() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
    if (discardDialog) {
        ConfirmDialog(
            title = "Keep your changes?",
            body = "Your edits are still here. Save the goal to keep them, or discard them to leave.",
            confirmLabel = "Discard changes",
            busy = busy,
            onConfirm = { discardDialog = false; onBack() },
            onDismiss = { discardDialog = false }
        )
    }
    when (pickingDate) {
        "start" -> DateSelectionDialog(plannedStart, latest = null, onDismiss = { pickingDate = "" },
            onSelect = { plannedStart = it; pickingDate = "" })
        "target" -> DateSelectionDialog(targetDate, latest = null, onDismiss = { pickingDate = "" },
            onSelect = { targetDate = it; pickingDate = "" })
    }
}

@Composable
private fun DateRow(label: String, value: LocalDate, enabled: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(enabled = enabled, onClick = onOpen) { Text("Change") }
    }
}

@Composable
private fun ValidationErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private val NEW_GOAL = Goal(uuid = "", title = "")
