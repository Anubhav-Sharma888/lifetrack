package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoveUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalMilestone
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.GoalPhase
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskStatus
import com.lifetrack.app.domain.GoalProgress
import com.lifetrack.app.domain.GoalTimeline
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Goal detail screen: overview, honest progress counts, the vertical timeline, and the full
 * roadmap hierarchy (phases → milestones → tasks). Everything derives from [GoalTimeline.snapshot]
 * through the ViewModel; the screen only renders and dispatches. Long roadmaps stay lazy, and
 * every section is one card in a single LazyColumn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalDetailScreen(goalId: Long, vm: GoalViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    // The goal is selected on the hot detail StateFlow: switching screens switches the chain, and
    // recompositions read the conflated value instead of restarting a cold collection.
    LaunchedEffect(goalId) { vm.selectGoal(goalId) }
    val detailState by vm.detail.collectAsStateWithLifecycle()
    val habitRecords by vm.habitRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val habitLogRecords by vm.habitLogRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.clearOperationError() }
    // State for THIS route only: while a fresh selection loads (or after the id changed), the hot
    // flow still holds the previous goal's value — it must never render or feed actions here.
    val state: GoalDetailState? = detailState?.takeIf { it.goal.id == goalId }
    var dialog by rememberSaveable { mutableStateOf("") } // "", "phase", "milestone", "task", "moveTask", "moveMilestone", "movePhase", "verifyMilestone", "linkHabit", "archive", "deleteGoal"
    var selectedPhaseId by rememberSaveable { mutableStateOf(0L) }
    var selectedMilestoneId by rememberSaveable { mutableStateOf(0L) }
    var selectedTaskId by rememberSaveable { mutableStateOf(0L) }
    val dateFormatter = rememberRowDateFormatter()

    Scaffold(topBar = { TopAppBar(
        title = { Text(state?.goal?.title ?: "Goal", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        actions = {
            IconButton(enabled = state != null && !busy, onClick = { onEdit(goalId) }) {
                Icon(Icons.Outlined.Edit, "Edit goal")
            }
        }
    ) }) { padding ->
        if (state == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("This goal no longer exists.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onBack) { Text("Back to Goals") }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "overview") {
                OverviewSection(state, dateFormatter,
                    onArchive = { dialog = "archive" },
                    onDelete = { dialog = "deleteGoal" },
                    onMoveDeadline = { dialog = "moveGoalDeadline" })
            }
            item(key = "progress") { ProgressSection(state, dateFormatter) }
            item(key = "timeline") { TimelineSection(state, dateFormatter) }
            item(key = "phases-header") {
                SectionHeader("Phases") { TextButton(enabled = !busy, onClick = {
                    selectedPhaseId = 0; dialog = "phase"
                }) { Text("Add phase") } }
            }
            if (state.phases.isEmpty()) {
                item(key = "phases-empty") {
                    EmptyLine("No phases yet. Phases divide the goal into major chunks — their order is yours to set.")
                }
            } else {
                items(state.phases, key = { "phase-${it.phase.id}" }) { view ->
                    PhaseCard(view, dateFormatter, busy, vm,
                        onEdit = { selectedPhaseId = view.phase.id; dialog = "phase" },
                        onMoveDates = { selectedPhaseId = view.phase.id; dialog = "movePhase" })
                }
            }
            item(key = "milestones-header") {
                SectionHeader("Milestones") { TextButton(enabled = !busy, onClick = {
                    selectedMilestoneId = 0; dialog = "milestone"
                }) { Text("Add milestone") } }
            }
            if (state.milestones.isEmpty()) {
                item(key = "milestones-empty") {
                    EmptyLine("No milestones yet. Milestones are verifiable checkpoints — you mark them achieved.")
                }
            } else {
                items(state.milestones, key = { "milestone-${it.milestone.id}" }) { view ->
                    MilestoneRow(view, dateFormatter, busy, vm,
                        onEdit = { selectedMilestoneId = view.milestone.id; dialog = "milestone" },
                        onMoveDeadline = { selectedMilestoneId = view.milestone.id; dialog = "moveMilestone" },
                        onVerify = { selectedMilestoneId = view.milestone.id; dialog = "verifyMilestone" })
                }
            }
            item(key = "tasks-header") {
                SectionHeader("Tasks") { TextButton(enabled = !busy, onClick = {
                    selectedTaskId = 0; dialog = "task"
                }) { Text("Add task") } }
            }
            if (state.tasks.isEmpty()) {
                item(key = "tasks-empty") {
                    EmptyLine("No tasks yet. Tasks are the concrete work; dependencies keep their order honest.")
                }
            } else {
                state.tasksByPhase.forEach { (phaseId, rows) ->
                    item(key = "tasks-group-$phaseId") {
                        Text(
                            when {
                                phaseId != null -> state.phases.firstOrNull { it.phase.id == phaseId }?.phase?.name ?: "Tasks"
                                else -> "Without a phase"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(rows, key = { "task-${it.task.id}" }) { view ->
                        TaskRow(view, state.originalPlannedDates[view.task.uuid], dateFormatter, busy, vm,
                            onEdit = { selectedTaskId = view.task.id; dialog = "task" },
                            onMoveDates = { selectedTaskId = view.task.id; dialog = "moveTask" })
                    }
                }
            }
            item(key = "habits-header") {
                SectionHeader("Supporting Habits") { TextButton(enabled = !busy, onClick = {
                    dialog = "linkHabit"
                }) { Text("Link a habit") } }
            }
            if (state.habitLinks.isEmpty()) {
                item(key = "habits-empty") {
                    EmptyLine("No supporting habits yet. Link an existing habit to see its consistency next to this goal — completing it never completes a task.")
                }
            } else {
                items(state.habitLinks, key = { "habit-link-${it.habitId}" }) { link ->
                    val habit = habitRecords.firstOrNull { it.id == link.habitId }
                    if (habit != null) {
                        HabitLinkRow(habit, habitLogRecords.filter { it.habitId == link.habitId },
                            state.today, busy, onUnlink = { vm.unlinkHabit(goalId, link.habitId) })
                    }
                }
            }
        }
        val phases = state?.phases?.map { it.phase } ?: emptyList()
        val milestones = state?.milestones?.map { it.milestone } ?: emptyList()
        when (dialog) {
            "phase" -> PhaseEditorDialog(goalId, phases.firstOrNull { it.id == selectedPhaseId }, vm,
                operationError, onDismiss = { dialog = "" })
            "movePhase" -> phases.firstOrNull { it.id == selectedPhaseId }?.let {
                MovePhaseDialog(it, vm, operationError) { dialog = "" }
            }
            "milestone" -> MilestoneEditorDialog(goalId, phases, milestones.firstOrNull { it.id == selectedMilestoneId },
                vm, operationError) { dialog = "" }
            "moveMilestone" -> milestones.firstOrNull { it.id == selectedMilestoneId }?.let {
                MoveMilestoneDialog(it, vm, operationError) { dialog = "" }
            }
            "task" -> TaskEditorDialog(goalId, phases, milestones,
                state.tasks.firstOrNull { it.task.id == selectedTaskId }?.task, state.tasks.map { it.task },
                state.dependencies, vm, operationError) { dialog = "" }
            "moveTask" -> state.tasks.firstOrNull { it.task.id == selectedTaskId }?.let { view ->
                MoveDatesDialog(view.task, state.tasks.map { it.task }, state.dependencies,
                    state.originalPlannedDates[view.task.uuid], vm, operationError) { dialog = "" }
            }
            "moveGoalDeadline" -> MoveGoalTargetDialog(state.goal, vm, operationError) { dialog = "" }
            "verifyMilestone" -> milestones.firstOrNull { it.id == selectedMilestoneId }?.let { milestone ->
                MilestoneVerifyDialog(
                    milestone = milestone,
                    supportingTasks = state.tasks.map { it.task }.filter { it.milestoneId == milestone.id },
                    vm = vm,
                    operationError = operationError,
                    onDismiss = { dialog = "" })
            }
            "linkHabit" -> HabitPickerDialog(
                goalId = goalId,
                linkedHabitIds = state.habitLinks.map { it.habitId },
                habits = habitRecords,
                vm = vm,
                operationError = operationError,
                onDismiss = { dialog = "" })
            "archive" -> ConfirmDialog(
                title = if (state.goal.isArchived) "Restore this goal?" else "Archive this goal?",
                body = if (state.goal.isArchived)
                    "Restoring brings the goal back to your active lists. Nothing else changes."
                else "Archiving files the goal away — its phases, milestones, tasks and history all stay. You can restore it any time.",
                confirmLabel = if (state.goal.isArchived) "Restore" else "Archive",
                busy = busy,
                onConfirm = { vm.archiveGoal(state.goal.id, !state.goal.isArchived); dialog = "" },
                onDismiss = { dialog = "" })
            "deleteGoal" -> ConfirmDialog(
                title = "Delete this goal?",
                body = "Deleting removes the goal with all of its phases, milestones, tasks and history. Your habits and activities are never touched.",
                confirmLabel = "Delete forever",
                busy = busy,
                onConfirm = { vm.deleteGoal(state.goal.id); dialog = ""; onBack() },
                onDismiss = { dialog = "" })
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OverviewSection(state: GoalDetailState, dateFormatter: DateTimeFormatter,
                            onArchive: () -> Unit, onDelete: () -> Unit, onMoveDeadline: () -> Unit) {
    val goal = state.goal
    val counts = if (state.totalTasksOrZero() == 0) "No tasks yet" else "${state.completedTasks} of ${state.tasks.size} tasks completed"
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(statusLabel(goal.status), archived = goal.isArchived)
                Text(goal.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            goal.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(counts, style = MaterialTheme.typography.bodyMedium)
            val dateLine = buildString {
                goal.plannedStartDate?.let { append("Planned start ${it.format(dateFormatter)}. ") }
                goal.targetDate?.let { append("Target ${it.format(dateFormatter)}.") }
            }
            if (dateLine.isNotBlank()) Text(dateLine.trim(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.overdueCount() > 0) {
                Text("${state.overdueCount()} task(s) overdue", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onArchive) {
                    Text(if (goal.isArchived) "Restore" else "Archive")
                }
                TextButton(onClick = onMoveDeadline) { Text("Move target date") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun GoalDetailState.totalTasksOrZero(): Int = tasks.size
private fun GoalDetailState.overdueCount(): Int = tasks.count { it.urgency == GoalTimeline.Urgency.OVERDUE }

internal fun statusLabel(status: String): String = when (com.lifetrack.app.data.GoalStatus.fromStored(status)) {
    com.lifetrack.app.data.GoalStatus.PLANNED -> "Planned"
    com.lifetrack.app.data.GoalStatus.ACTIVE -> "Active"
    com.lifetrack.app.data.GoalStatus.COMPLETED -> "Completed"
    com.lifetrack.app.data.GoalStatus.PAUSED -> "Paused"
    com.lifetrack.app.data.GoalStatus.CANCELLED -> "Cancelled"
}

/** The vertical timeline: phase windows with a current-period marker, milestone deadlines below. */
@Composable
private fun TimelineSection(state: GoalDetailState, dateFormatter: DateTimeFormatter) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleMedium)
            if (state.phases.isEmpty() && state.milestones.isEmpty()) {
                EmptyLine("Add phases and milestones to see the plan over time.")
            } else {
                state.phases.forEach { view ->
                    TimelineRow(
                        marker = when {
                            view.isCurrent -> MaterialTheme.colorScheme.primary
                            view.isPast -> MaterialTheme.colorScheme.outlineVariant
                            else -> MaterialTheme.colorScheme.outline
                        },
                        headline = view.phase.name + when {
                            view.isCurrent -> " · current"
                            view.isPast -> " · past"
                            view.isFuture -> " · upcoming"
                            else -> ""
                        },
                        detail = listOfNotNull(
                            view.phase.plannedStartDate?.format(dateFormatter),
                            view.phase.plannedEndDate?.let { "→ ${it.format(dateFormatter)}" }
                        ).joinToString(" ") + if (view.totalTasks > 0) " · ${view.completedTasks}/${view.totalTasks} tasks" else ""
                    )
                }
                state.milestones.forEach { view ->
                    TimelineRow(
                        marker = if (view.milestone.status == com.lifetrack.app.data.MilestoneStatus.ACHIEVED.name)
                            MaterialTheme.colorScheme.secondary else if (view.overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline,
                        headline = view.milestone.title + when {
                            view.overdue -> " · ${view.daysOverdue}d overdue"
                            else -> ""
                        },
                        detail = view.milestone.targetDate?.let { "By ${it.format(dateFormatter)}" } ?: "No deadline"
                    )
                }
                if (state.phases.none { it.isCurrent } && state.phases.isNotEmpty()) {
                    Text("No phase covers today.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(marker: androidx.compose.ui.graphics.Color, headline: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).background(marker, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhaseCard(view: GoalTimeline.PhaseView, dateFormatter: DateTimeFormatter, busy: Boolean,
                      vm: GoalViewModel, onEdit: () -> Unit, onMoveDates: () -> Unit) {
    val phase = view.phase
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text(phase.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Row {
                    IconButton(enabled = !busy, onClick = { vm.movePhase(phase.goalId, phase.id, -1) }) {
                        Icon(Icons.Outlined.ArrowUpward, "Move phase up")
                    }
                    IconButton(enabled = !busy, onClick = { vm.movePhase(phase.goalId, phase.id, +1) }) {
                        Icon(Icons.Outlined.ArrowDownward, "Move phase down")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(when (com.lifetrack.app.data.PhaseStatus.fromStored(phase.status)) {
                    com.lifetrack.app.data.PhaseStatus.PLANNED -> "Planned"
                    com.lifetrack.app.data.PhaseStatus.ACTIVE -> "Active"
                    com.lifetrack.app.data.PhaseStatus.COMPLETED -> "Completed"
                    com.lifetrack.app.data.PhaseStatus.CANCELLED -> "Cancelled"
                }, archived = false)
                if (view.isCurrent) Text("current", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (view.totalTasks > 0) Text("${view.completedTasks}/${view.totalTasks} tasks",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val dateLine = listOfNotNull(
                phase.plannedStartDate?.format(dateFormatter),
                phase.plannedEndDate?.let { "→ ${it.format(dateFormatter)}" }
            ).joinToString(" ")
            if (dateLine.isNotBlank()) Text(dateLine, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            phase.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = onEdit) { Text("Edit") }
                TextButton(enabled = !busy, onClick = onMoveDates) { Text("Move window") }
            }
        }
    }
}

@Composable
private fun MilestoneRow(view: GoalTimeline.MilestoneView, dateFormatter: DateTimeFormatter, busy: Boolean,
                         vm: GoalViewModel, onEdit: () -> Unit, onMoveDeadline: () -> Unit,
                         onVerify: () -> Unit) {
    val milestone = view.milestone
    val achieved = milestone.status == com.lifetrack.app.data.MilestoneStatus.ACHIEVED.name
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(
            if (achieved) MaterialTheme.colorScheme.secondary
            else if (view.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            CircleShape
        ))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(milestone.title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                milestone.targetDate?.let { Text("By ${it.format(dateFormatter)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (view.overdue) Text("${view.daysOverdue}d overdue", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                if (achieved) Text("Achieved", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
        TextButton(enabled = !busy, onClick = onVerify) { Text(if (achieved) "Reopen" else "Verify") }
        TextButton(enabled = !busy, onClick = onMoveDeadline) { Text("Move") }
        TextButton(enabled = !busy, onClick = onEdit) { Text("Edit") }
    }
}

@Composable
private fun TaskRow(view: GoalTimeline.TaskView, originalPlannedDate: java.time.LocalDate?,
                    dateFormatter: DateTimeFormatter, busy: Boolean, vm: GoalViewModel,
                    onEdit: () -> Unit, onMoveDates: () -> Unit) {
    val task = view.task
    val completed = task.status == GoalTaskStatus.COMPLETED.name
    val skipped = task.status == GoalTaskStatus.SKIPPED.name
    val statusLine = when {
        completed -> "Completed"
        skipped -> "Skipped"
        task.status == GoalTaskStatus.IN_PROGRESS.name -> "In progress"
        else -> "Not started"
    }
    val gateLabels = view.prerequisites.map { prerequisite ->
        "Needs: " + prerequisite.title + when (prerequisite.gate) {
            GoalTimeline.PrerequisiteGate.SATISFIED_COMPLETED -> " (done)"
            GoalTimeline.PrerequisiteGate.SATISFIED_BYPASSED -> " (bypassed)"
            GoalTimeline.PrerequisiteGate.PENDING -> " (pending)"
        }
    }
    val markers = buildList {
        if (view.blocked) add("Blocked by ${view.blockingTaskTitles.firstOrNull() ?: "prerequisites"}")
        gateLabels.forEach { add(it) }
        if (view.timingConflict) add("Timing conflict")
        if (view.urgency == GoalTimeline.Urgency.OVERDUE) add("${view.daysOverdue}d overdue")
        if (view.urgency == GoalTimeline.Urgency.DUE_TODAY) add("Due today")
        if (view.completedAfterDeadline) add("Finished late")
        originalPlannedDate?.let { if (it != task.plannedDate) add("Originally planned $it") }
    }
    val rowDescription = "${task.title}. $statusLine" + markers.joinToString(", ", prefix = ". ") { it }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLine, style = MaterialTheme.typography.bodySmall,
                    color = if (completed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                task.plannedDate?.let { Text("Plan ${it.format(dateFormatter)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                task.deadline?.let { Text("Due ${it.format(dateFormatter)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (markers.isNotEmpty()) {
                Text(markers.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = when {
                        view.urgency == GoalTimeline.Urgency.OVERDUE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // One-tap status action for the state the task is in (Prompt 3 quick actions); the row
        // keeps Move/Edit for planning. The repository state machine owns every transition.
        val quickAction = when (GoalTaskStatus.fromStored(task.status)) {
            GoalTaskStatus.NOT_STARTED -> "Start" to GoalTaskStatus.IN_PROGRESS
            GoalTaskStatus.IN_PROGRESS -> "Done" to GoalTaskStatus.COMPLETED
            GoalTaskStatus.COMPLETED -> "Reopen" to GoalTaskStatus.NOT_STARTED
            GoalTaskStatus.SKIPPED -> "Restore" to GoalTaskStatus.NOT_STARTED
        }
        TextButton(enabled = !busy, onClick = { vm.setTaskStatus(task.id, quickAction.second) }) {
            Text(quickAction.first)
        }
        TextButton(enabled = !busy, onClick = onMoveDates) { Text("Move") }
        TextButton(enabled = !busy, onClick = onEdit) { Text("Edit") }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirmLabel: String, busy: Boolean,
                          onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val maxDialogHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.width(560.dp).fillMaxWidth().padding(20.dp)) {
            Column(Modifier.heightIn(max = maxDialogHeight).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(enabled = !busy, onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmLabel) }
                    OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The Progress section: transparent, clearly labeled counts from [GoalProgress]. Task completion
 * (with skipped work kept in the denominator), milestone achievement, and the derived "ready for
 * review" suggestion are shown as separate facts — never one weighted score, never a silent
 * completion write.
 */
@Composable
private fun ProgressSection(state: GoalDetailState, dateFormatter: DateTimeFormatter) {
    val progress = GoalProgress.goalProgress(
        state.tasks.map { it.task },
        state.milestones.map { it.milestone }
    )
    val taskRateLabel = when (val rate = progress.taskCompletionRate) {
        null -> "Tasks: not yet measurable — no tasks defined"
        else -> "Task progress: $rate% (${progress.tasks.completed} of ${progress.tasks.total} complete)"
    }
    val milestoneRateLabel = when (val rate = progress.milestoneAchievementRate) {
        null -> "Milestones: none defined"
        else -> "Milestone progress: $rate% (${progress.milestones.achieved} of ${progress.milestones.total} achieved)"
    }
    val description = buildString {
        append(taskRateLabel).append(". ")
        append(milestoneRateLabel).append(". ")
        append("Tasks: ${progress.tasks.completed} completed, ${progress.tasks.skipped} skipped, " +
            "${progress.tasks.notDone} not done. ")
        append("Milestones: ${progress.milestones.pending} pending. ")
        if (progress.readyForReview == true) append("All required work complete — ready for review.")
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Progress", style = MaterialTheme.typography.titleMedium)
            Text(taskRateLabel, style = MaterialTheme.typography.bodyMedium)
            Text(milestoneRateLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Tasks: ${progress.tasks.completed} completed · ${progress.tasks.skipped} skipped · " +
                    "${progress.tasks.notDone} not done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress.readyForReview == true) {
                Text(
                    "All required work complete — ready for review.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** One linked supporting habit with an honest, clearly labeled read-only activity signal. */
@Composable
private fun HabitLinkRow(habit: Habit, logs: List<HabitLog>, today: LocalDate, busy: Boolean,
                         onUnlink: () -> Unit) {
    val dateFormatter = rememberRowDateFormatter()
    val activity = GoalRepository.habitActivitySignal(habit, logs, today)
    val signal = when {
        activity.totalCompletions == 0 -> "No completed check-ins yet"
        else -> "${activity.totalCompletions} completed check-ins · " +
            "${activity.completionsInWindow} in the last ${GoalRepository.HABIT_ACTIVITY_WINDOW_DAYS} days" +
            (activity.lastCompletedDate?.let { " · last ${it.format(dateFormatter)}" } ?: "")
    }
    val rowDescription = "${habit.name}. Supporting habit" +
        (if (habit.isArchived) ", archived" else "") + ". $signal."
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(habit.name, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (habit.isArchived) Text("Archived", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(signal, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(enabled = !busy, onClick = onUnlink) { Text("Unlink") }
    }
}
