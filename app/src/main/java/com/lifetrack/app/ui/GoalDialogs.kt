package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.GoalTaskStatus
import com.lifetrack.app.data.MilestoneStatus
import com.lifetrack.app.data.PhaseStatus
import com.lifetrack.app.data.GoalTransitions
import com.lifetrack.app.domain.GoalTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Goal roadmap editors as custom dialogs (the Prompt 9 shell: no platform-default width, surface
 * shell, 85%-of-window height cap — the platform AlertDialog width derivation measure-loops under
 * Robolectric, and no second window is ever stacked on a platform AlertDialog). Every editor keeps
 * its draft in rememberSaveable, gates saves on [GoalViewModel.busy], and surfaces repository
 * validation failures inline as a polite live region. Date changes on existing records save
 * through the reschedule operations, so schedule history is written by the UI as well.
 */

private const val TITLE_LIMIT = 120
private const val DESCRIPTION_LIMIT = 2000

/** Dialog shell: scrollable content plus an always-visible footer, so Save/Cancel never scroll
 *  out of reach on short viewports or long editors. */
@Composable
private fun EditorShell(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit
) {
    val maxDialogHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.width(560.dp).fillMaxWidth().padding(20.dp)
        ) {
            Column(Modifier.heightIn(max = maxDialogHeight).padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { content() }
                Spacer(Modifier.height(8.dp))
                footer()
            }
        }
    }
}

@Composable
private fun OperationErrorLine(operationError: String?) {
    operationError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate?, placeholder: String, enabled: Boolean,
                      onChange: (LocalDate?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value?.toString() ?: placeholder, style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        TextButton(enabled = enabled, onClick = { picking = true }) {
            Text(if (value == null) "Set" else "Change")
        }
        if (value != null) {
            TextButton(enabled = enabled, onClick = { onChange(null) }) { Text("Clear") }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                ?: LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1900..LocalDate.now().year + 100
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let {
                    onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                picking = false
            }) { Text("Choose date") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}

/** Radio-style status rows shared by the editors (48dp rows, merged semantics). */
@Composable
private fun <T> StatusRows(options: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    options.forEach { option ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectableRow(selected == option, enabled) { onSelect(option) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == option, onClick = null, enabled = enabled)
            Spacer(Modifier.width(10.dp))
            Text(label(option), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun Modifier.selectableRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit): Modifier =
    this.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)

// ---- Phase editor ----

@Composable
internal fun PhaseEditorDialog(
    goalId: Long,
    phase: GoalPhase?,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable(phase?.id) { mutableStateOf(phase?.name ?: "") }
    var description by rememberSaveable(phase?.id) { mutableStateOf(phase?.description ?: "") }
    var start by rememberSaveable(phase?.id) { mutableStateOf(phase?.plannedStartDate) }
    var end by rememberSaveable(phase?.id) { mutableStateOf(phase?.plannedEndDate) }
    var status by rememberSaveable(phase?.id) { mutableStateOf(phase?.status ?: PhaseStatus.PLANNED.name) }
    val nameError = when {
        name.isBlank() -> "Give the phase a name."
        name.trim().length > TITLE_LIMIT -> "Names are limited to $TITLE_LIMIT characters."
        else -> null
    }
    val dateError = if (start != null && end != null && (start as LocalDate).isAfter(end as LocalDate)) "A phase cannot end before it starts." else null
    val datesChanged = phase != null && (phase.plannedStartDate != start || phase.plannedEndDate != end)
    EditorShell(if (phase == null) "Add phase" else "Edit phase", busy, onDismiss,
        content = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !busy,
                supportingText = { nameError?.let { Text(it) } }, isError = nameError != null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { if (it.length <= DESCRIPTION_LIMIT) description = it },
                label = { Text("Description (optional)") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
            DateField("Planned start", start, "No planned start", !busy) { start = it }
            DateField("Planned end", end, "No planned end", !busy) { end = it }
            if (phase != null) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                StatusRows(PhaseStatus.entries.filter { GoalTransitions.legal(GoalTransitions.phases, PhaseStatus.fromStored(status), it) }, PhaseStatus.fromStored(status), !busy,
                    label = { when (it) {
                        PhaseStatus.PLANNED -> "Planned"
                        PhaseStatus.ACTIVE -> "Active"
                        PhaseStatus.COMPLETED -> "Completed"
                        PhaseStatus.CANCELLED -> "Cancelled"
                    } }) { status = it.name }
                Text("Changing dates records a schedule revision so the original plan stays visible.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            dateError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (phase != null) {
                Text("Deleting detaches its milestones and tasks — nothing under the goal is destroyed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OperationErrorLine(operationError)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(enabled = !busy && nameError == null && dateError == null,
                    onClick = {
                        val draft = (phase ?: GoalPhase(uuid = "", goalId = goalId, name = "")).copy(
                            name = name.trim(),
                            description = description.trim().takeIf { it.isNotEmpty() },
                            plannedStartDate = start, plannedEndDate = end, status = status
                        )
                        vm.savePhase(draft, datesChanged, onSaved = onDismiss)
                    }, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Save phase")
                }
                if (phase != null) {
                    OutlinedButton(enabled = !busy, onClick = { vm.deletePhase(phase.id) { onDismiss() } }, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                } else {
                    OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        })
}

// ---- Milestone editor ----

@Composable
internal fun MilestoneEditorDialog(
    goalId: Long,
    phases: List<GoalPhase>,
    milestone: GoalMilestone?,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var title by rememberSaveable(milestone?.id) { mutableStateOf(milestone?.title ?: "") }
    var description by rememberSaveable(milestone?.id) { mutableStateOf(milestone?.description ?: "") }
    var phaseId by rememberSaveable(milestone?.id) { mutableStateOf(milestone?.phaseId) }
    var deadline by rememberSaveable(milestone?.id) { mutableStateOf(milestone?.targetDate) }
    var status by rememberSaveable(milestone?.id) { mutableStateOf(milestone?.status ?: MilestoneStatus.PENDING.name) }
    val titleError = when {
        title.isBlank() -> "Give the milestone a title."
        title.trim().length > TITLE_LIMIT -> "Titles are limited to $TITLE_LIMIT characters."
        else -> null
    }
    val deadlineChanged = milestone != null && milestone.targetDate != deadline
    EditorShell(if (milestone == null) "Add milestone" else "Edit milestone", busy, onDismiss,
        content = {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !busy,
                supportingText = { titleError?.let { Text(it) } }, isError = titleError != null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { if (it.length <= DESCRIPTION_LIMIT) description = it },
                label = { Text("Description (optional)") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
            DateField("Deadline", deadline, "No deadline", !busy) { deadline = it }
            Text("Phase (optional)", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableRow(phaseId == null, !busy) { phaseId = null },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = phaseId == null, onClick = null, enabled = !busy)
                Spacer(Modifier.width(10.dp))
                Text("No phase — anchored to the goal", style = MaterialTheme.typography.bodyMedium)
            }
            phases.forEach { candidate ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectableRow(phaseId == candidate.id, !busy) { phaseId = candidate.id },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = phaseId == candidate.id, onClick = null, enabled = !busy)
                    Spacer(Modifier.width(10.dp))
                    Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (milestone != null) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                StatusRows(MilestoneStatus.entries.filter { GoalTransitions.legal(GoalTransitions.milestones, MilestoneStatus.fromStored(status), it) }, MilestoneStatus.fromStored(status), !busy,
                    label = { if (it == MilestoneStatus.PENDING) "Pending" else "Achieved" }) { status = it.name }
                Text("Achieving records when it happened — a passing deadline never completes a milestone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OperationErrorLine(operationError)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(enabled = !busy && titleError == null,
                    onClick = {
                        val draft = (milestone ?: GoalMilestone(uuid = "", goalId = goalId, title = "")).copy(
                            title = title.trim(),
                            description = description.trim().takeIf { it.isNotEmpty() },
                            phaseId = phaseId, targetDate = deadline, status = status
                        )
                        vm.saveMilestone(draft, deadlineChanged, onSaved = onDismiss)
                    }, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Save milestone")
                }
                if (milestone != null) {
                    OutlinedButton(enabled = !busy, onClick = { vm.deleteMilestone(milestone.id) { onDismiss() } }, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                } else {
                    OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        })
}

// ---- Task editor (with dependency management) ----

@Composable
internal fun TaskEditorDialog(
    goalId: Long,
    phases: List<GoalPhase>,
    milestones: List<GoalMilestone>,
    task: GoalTask?,
    allTasks: List<GoalTask>,
    dependencies: List<com.lifetrack.app.data.GoalTaskDependency>,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var description by rememberSaveable(task?.id) { mutableStateOf(task?.description ?: "") }
    var phaseId by rememberSaveable(task?.id) { mutableStateOf(task?.phaseId) }
    var milestoneId by rememberSaveable(task?.id) { mutableStateOf(task?.milestoneId) }
    var plannedDate by rememberSaveable(task?.id) { mutableStateOf(task?.plannedDate) }
    var deadline by rememberSaveable(task?.id) { mutableStateOf(task?.deadline) }
    var estimated by rememberSaveable(task?.id) { mutableStateOf(task?.estimatedMinutes?.toString() ?: "") }
    var status by rememberSaveable(task?.id) { mutableStateOf(task?.status ?: GoalTaskStatus.NOT_STARTED.name) }
    var addingPrerequisite by rememberSaveable(task?.id) { mutableStateOf(false) }
    val titleError = when {
        title.isBlank() -> "Give the task a title."
        title.trim().length > TITLE_LIMIT -> "Titles are limited to $TITLE_LIMIT characters."
        else -> null
    }
    val dateError = if (plannedDate != null && deadline != null && (plannedDate as LocalDate).isAfter(deadline as LocalDate)) {
        "A task cannot be planned after its deadline."
    } else null
    val estimatedError = estimated.toIntOrNull()?.let { minutes ->
        if (minutes in 1..10080) null else "Estimates are between 1 and 10,080 minutes."
    } ?: if (estimated.isBlank()) null else "Estimates are between 1 and 10,080 minutes."
    val datesChanged = task != null && (task.plannedDate != plannedDate || task.deadline != deadline)
    val prerequisites = dependencies.filter { it.dependentTaskId == task?.id }
        .mapNotNull { edge -> allTasks.firstOrNull { it.id == edge.prerequisiteTaskId } }
    val candidates = allTasks.filter { candidate ->
        candidate.id != task?.id && prerequisites.none { it.id == candidate.id } &&
            (task == null || !GoalTimeline.wouldCreateCycle(task.id, candidate.id, dependencies))
    }
    EditorShell(if (task == null) "Add task" else "Edit task", busy, onDismiss,
        content = {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !busy,
            supportingText = { titleError?.let { Text(it) } }, isError = titleError != null, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { if (it.length <= DESCRIPTION_LIMIT) description = it },
            label = { Text("Description (optional)") }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
        DateField("Planned date", plannedDate, "No planned date", !busy) { plannedDate = it }
        DateField("Deadline", deadline, "No deadline", !busy) { deadline = it }
        dateError?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        OutlinedTextField(estimated, { estimated = it.takeWhile(Char::isDigit).take(6) },
            label = { Text("Estimated minutes (optional)") }, singleLine = true, enabled = !busy,
            supportingText = { estimatedError?.let { Text(it) } }, isError = estimatedError != null,
            modifier = Modifier.fillMaxWidth())
        Text("Phase (optional)", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableRow(phaseId == null, !busy) { phaseId = null },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = phaseId == null, onClick = null, enabled = !busy)
            Spacer(Modifier.width(10.dp))
            Text("No phase", style = MaterialTheme.typography.bodyMedium)
        }
        phases.forEach { candidate ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectableRow(phaseId == candidate.id, !busy) { phaseId = candidate.id },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = phaseId == candidate.id, onClick = null, enabled = !busy)
                Spacer(Modifier.width(10.dp))
                Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Milestone (optional)", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableRow(milestoneId == null, !busy) { milestoneId = null },
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = milestoneId == null, onClick = null, enabled = !busy)
            Spacer(Modifier.width(10.dp))
            Text("No milestone", style = MaterialTheme.typography.bodyMedium)
        }
        milestones.forEach { candidate ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectableRow(milestoneId == candidate.id, !busy) { milestoneId = candidate.id },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = milestoneId == candidate.id, onClick = null, enabled = !busy)
                Spacer(Modifier.width(10.dp))
                Text(candidate.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (task != null) {
            Text("Status", style = MaterialTheme.typography.labelLarge)
            StatusRows(GoalTaskStatus.entries.filter { GoalTransitions.legal(GoalTransitions.tasks, GoalTaskStatus.fromStored(status), it) }, GoalTaskStatus.fromStored(status), !busy, label = { when (it) {
                GoalTaskStatus.NOT_STARTED -> "Not started"
                GoalTaskStatus.IN_PROGRESS -> "In progress"
                GoalTaskStatus.COMPLETED -> "Completed"
                GoalTaskStatus.SKIPPED -> "Skipped"
            } }) { status = it.name }
            Text("Reopening a completed task clears its completion record. A passing deadline never completes anything.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Dependencies: read-only removal here; adding happens below.
            Text("Needs first", style = MaterialTheme.typography.labelLarge)
            if (prerequisites.isEmpty()) {
                Text("No prerequisites — this task is ready when you are.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                prerequisites.forEach { prerequisite ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(prerequisite.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { vm.removeDependency(task.id, prerequisite.id) }) {
                            Text("Remove")
                        }
                    }
                }
            }
            if (addingPrerequisite) {
                if (candidates.isEmpty()) {
                    Text("Every other task in this goal is already a prerequisite.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    candidates.forEach { candidate ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableRow(false, !busy) {
                            vm.addDependency(task.id, candidate.id)
                            addingPrerequisite = false
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(10.dp))
                            Text(candidate.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                TextButton(enabled = !busy && candidates.isNotEmpty(), onClick = { addingPrerequisite = true }) {
                    Text("Add a prerequisite")
                }
            }
            Text("Tasks can only depend on work in the same goal. Cycles are rejected.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OperationErrorLine(operationError)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(enabled = !busy && titleError == null && dateError == null && estimatedError == null,
                    onClick = {
                        val draft = (task ?: GoalTask(uuid = "", goalId = goalId, title = "")).copy(
                            title = title.trim(),
                            description = description.trim().takeIf { it.isNotEmpty() },
                            phaseId = phaseId, milestoneId = milestoneId,
                            plannedDate = plannedDate, deadline = deadline,
                            estimatedMinutes = estimated.toIntOrNull()?.takeIf { it in 1..10080 },
                            status = status
                        )
                        vm.saveTask(draft, datesChanged, onSaved = onDismiss)
                    }, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Save task")
                }
                if (task != null) {
                    OutlinedButton(enabled = !busy, onClick = { vm.deleteTask(task.id) { onDismiss() } }, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                } else {
                    OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        })
}

// ---- Move-dates dialog (rescheduling with history and optional dependent shift) ----

@Composable
internal fun MoveDatesDialog(
    task: GoalTask,
    allTasks: List<GoalTask>,
    dependencies: List<com.lifetrack.app.data.GoalTaskDependency>,
    originalPlannedDate: LocalDate?,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var plannedDate by rememberSaveable(task.id) { mutableStateOf(task.plannedDate) }
    var deadline by rememberSaveable(task.id) { mutableStateOf(task.deadline) }
    var shiftDependents by rememberSaveable(task.id) { mutableStateOf(false) }
    val dateError = if (plannedDate != null && deadline != null && (plannedDate as LocalDate).isAfter(deadline as LocalDate)) {
        "A task cannot be planned after its deadline."
    } else null
    val changed = task.plannedDate != plannedDate || task.deadline != deadline
    // The dependent preview follows the drafted dates, so the confirmation always matches reality.
    val dependentPreview = if (changed) GoalTimeline.shiftPreview(task, allTasks, dependencies, plannedDate, deadline) else emptyList()
    EditorShell("Move dates", busy, onDismiss,
        content = {
        originalPlannedDate?.let {
            Text("Originally planned: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DateField("Planned date", plannedDate, "No planned date", !busy) { plannedDate = it }
        DateField("Deadline", deadline, "No deadline", !busy) { deadline = it }
        dateError?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (dependentPreview.isNotEmpty()) {
            val infeasible = dependentPreview.count { it.plannedDate != null && it.deadline != null && it.plannedDate.isAfter(it.deadline) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shiftDependents, onCheckedChange = { shiftDependents = it }, enabled = !busy)
                Column {
                    Text("Also move ${dependentPreview.size} dependent task(s)", style = MaterialTheme.typography.bodyMedium)
                    Text(dependentPreview.joinToString(", ") { it.title }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
            }
            Text("Completed and skipped dependents keep their recorded dates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shiftDependents && infeasible > 0) {
                Text("$infeasible dependent task(s) would end up planned after their own deadline. " +
                        "Move them first, or shift their deadlines too.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        OperationErrorLine(operationError)
        },
        footer = {
        val shiftBlocked = shiftDependents && dependentPreview.any { it.plannedDate != null && it.deadline != null && it.plannedDate.isAfter(it.deadline) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy && dateError == null && changed && !shiftBlocked,
                onClick = { vm.rescheduleTask(task.id, plannedDate, deadline, shiftDependents, onSaved = onDismiss) },
                modifier = Modifier.weight(1f)) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Move")
            }
            OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
        })
}

@Composable
internal fun MoveMilestoneDialog(
    milestone: GoalMilestone,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var deadline by rememberSaveable(milestone.id) { mutableStateOf(milestone.targetDate) }
    val changed = milestone.targetDate != deadline
    EditorShell("Move deadline", busy, onDismiss,
        content = {
        DateField("Deadline", deadline, "No deadline", !busy) { deadline = it }
        OperationErrorLine(operationError)
        },
        footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy && changed,
                onClick = { vm.rescheduleMilestone(milestone.id, deadline, onSaved = onDismiss) },
                modifier = Modifier.weight(1f)) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Move")
            }
            OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
        })
}

@Composable
internal fun MovePhaseDialog(
    phase: GoalPhase,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var start by rememberSaveable(phase.id) { mutableStateOf(phase.plannedStartDate) }
    var end by rememberSaveable(phase.id) { mutableStateOf(phase.plannedEndDate) }
    val dateError = if (start != null && end != null && (start as LocalDate).isAfter(end as LocalDate)) "A phase cannot end before it starts." else null
    val changed = phase.plannedStartDate != start || phase.plannedEndDate != end
    EditorShell("Move phase window", busy, onDismiss,
        content = {
        DateField("Planned start", start, "No planned start", !busy) { start = it }
        DateField("Planned end", end, "No planned end", !busy) { end = it }
        dateError?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        OperationErrorLine(operationError)
        },
        footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy && dateError == null && changed,
                onClick = { vm.reschedulePhase(phase.id, start, end, onSaved = onDismiss) },
                modifier = Modifier.weight(1f)) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Move")
            }
            OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
        })
}

@Composable
internal fun MoveGoalTargetDialog(
    goal: com.lifetrack.app.data.Goal,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var targetDate by rememberSaveable(goal.id) { mutableStateOf(goal.targetDate) }
    val changed = goal.targetDate != targetDate
    EditorShell("Move target date", busy, onDismiss,
        content = {
        DateField("Target date", targetDate, "No target date", !busy) { targetDate = it }
        Text("Tasks and phases are never moved silently — move them individually or with the shift preview.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OperationErrorLine(operationError)
        },
        footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy && changed,
                onClick = { vm.rescheduleGoal(goal.id, targetDate, onSaved = onDismiss) },
                modifier = Modifier.weight(1f)) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Move")
            }
            OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
        })
}

/**
 * Milestone verification (Prompt 3): shows the milestone's outcome, its supporting tasks with
 * their completion states, and requires an explicit user confirmation to mark it achieved. If
 * supporting tasks remain incomplete the dialog says so plainly — a deliberate confirmation, not
 * an implication that the underlying work finished. Reopening is the explicit ACHIEVED → PENDING
 * transition and clears the achievement instant.
 */
@Composable
internal fun MilestoneVerifyDialog(
    milestone: GoalMilestone,
    supportingTasks: List<GoalTask>,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val achieved = milestone.status == MilestoneStatus.ACHIEVED.name
    val completedTasks = supportingTasks.count { it.status == GoalTaskStatus.COMPLETED.name }
    val incompleteTasks = supportingTasks.size - completedTasks
    EditorShell(if (achieved) "Milestone achieved" else "Verify milestone", busy, onDismiss,
        content = {
            Text(milestone.title, style = MaterialTheme.typography.titleMedium)
            milestone.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (supportingTasks.isEmpty()) {
                Text("No supporting tasks are linked to this milestone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Supporting tasks: $completedTasks of ${supportingTasks.size} completed.",
                    style = MaterialTheme.typography.bodyMedium)
                supportingTasks.forEach { task ->
                    val state = when (GoalTaskStatus.fromStored(task.status)) {
                        GoalTaskStatus.COMPLETED -> "Completed"
                        GoalTaskStatus.IN_PROGRESS -> "In progress"
                        GoalTaskStatus.SKIPPED -> "Skipped"
                        GoalTaskStatus.NOT_STARTED -> "Not started"
                    }
                    Text("· ${task.title} — $state", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (achieved) {
                    Text("Achieved " + java.time.Instant.ofEpochMilli(milestone.achievedAt ?: 0)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate() +
                        ". Reopening clears the achievement record.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!achieved && incompleteTasks > 0) {
                    Text("$incompleteTasks supporting task(s) are not completed. Marking the milestone " +
                            "achieved records YOUR confirmation that the outcome was met — it will not " +
                            "claim the unfinished tasks were done.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            OperationErrorLine(operationError)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (achieved) {
                    Button(enabled = !busy,
                        onClick = { vm.setMilestoneVerification(milestone.id, false); onDismiss() },
                        modifier = Modifier.weight(1f)) { Text("Reopen milestone") }
                } else {
                    Button(enabled = !busy,
                        onClick = { vm.setMilestoneVerification(milestone.id, true); onDismiss() },
                        modifier = Modifier.weight(1f)) {
                        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                        else Text(if (incompleteTasks > 0) "Confirm achieved" else "Mark achieved")
                    }
                }
                OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        })
}

/**
 * Link an existing habit as a goal's supporting effort (Prompt 3). Lists only not-yet-linked
 * habits; linking is a read-only association and never duplicates a habit record.
 */
@Composable
internal fun HabitPickerDialog(
    goalId: Long,
    linkedHabitIds: List<Long>,
    habits: List<Habit>,
    vm: GoalViewModel,
    operationError: String?,
    onDismiss: () -> Unit
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val candidates = habits.filter { habit -> habit.id !in linkedHabitIds }
    EditorShell("Link a supporting habit", busy, onDismiss,
        content = {
            if (candidates.isEmpty()) {
                Text("Every habit is already linked to this goal, or no habits exist yet. Create " +
                        "habits on the Today screen — they can support any goal without being recreated.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Completing a linked habit supports the goal — it never completes a task or the goal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                candidates.forEach { habit ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectableRow(false, !busy) {
                        vm.linkHabit(goalId, habit.id)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(habit.name, style = MaterialTheme.typography.bodyMedium)
                            if (habit.isArchived) Text("Archived — historical effort stays visible",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OperationErrorLine(operationError)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        })
}
