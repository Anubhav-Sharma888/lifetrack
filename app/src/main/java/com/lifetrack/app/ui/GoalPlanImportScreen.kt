package com.lifetrack.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalPlanClassification
import com.lifetrack.app.data.GoalPlanDecision
import com.lifetrack.app.data.GoalPlanRecordChange
import com.lifetrack.app.data.GoalPlanRecordKind
import java.time.format.DateTimeFormatter

/**
 * The Goal Plan import flow (Prompt 4): choose a file, review exactly what would change, resolve
 * conflicts record by record, confirm, and land on the imported goal. The preview is read-only —
 * the single write happens on [GoalPlanImportViewModel.confirmImport], in one transaction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalPlanImportScreen(vm: GoalPlanImportViewModel = hiltViewModel(),
                                  onBack: () -> Unit, onViewGoal: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dateFormatter = rememberRowDateFormatter()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            // Canceled selection: nothing loaded, nothing written.
            if (state.phase == GoalPlanImportPhase.READING) vm.reset()
        } else {
            vm.load(uri, queryDisplayName(context, uri))
        }
    }

    BackHandler(enabled = state.phase == GoalPlanImportPhase.IMPORTING) {
        // Prevent accidental back navigation while the single transactional import is committing.
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Import Goal Plan") },
            navigationIcon = {
                IconButton(enabled = state.phase != GoalPlanImportPhase.IMPORTING, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (state.phase == GoalPlanImportPhase.READY) {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh preview")
                    }
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                GoalPlanImportPhase.IDLE -> IdleContent { picker.launch(IMPORT_MIME_TYPES) }
                GoalPlanImportPhase.READING -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Checking the selected file…", style = MaterialTheme.typography.bodyMedium)
                }
                GoalPlanImportPhase.READY -> ReadyContent(state, vm, dateFormatter, onViewGoal)
                // While importing the reviewed content stays visible and the footer freezes.
                GoalPlanImportPhase.IMPORTING -> ReadyContent(state, vm, dateFormatter, onViewGoal, importing = true)
                GoalPlanImportPhase.DONE -> DoneContent(state, vm, onViewGoal, onBack) {
                    picker.launch(IMPORT_MIME_TYPES)
                }
                GoalPlanImportPhase.FAILED -> FailedContent(state, vm, onBack) {
                    picker.launch(IMPORT_MIME_TYPES)
                }
            }
        }
    }
}

private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")

/** Best-effort display name from the provider; a missing name is fine — never identity. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (error: Exception) {
    null
}

@Composable
private fun IdleContent(onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null,
            modifier = Modifier.heightIn(min = 48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Import a Goal Plan", style = MaterialTheme.typography.titleLarge)
        Text(
            "Choose a Goal Plan JSON file — the kind any AI can generate for you with the template " +
                "in LifeTrack's documentation. You will preview every change before anything is saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Non-destructive by design", style = MaterialTheme.typography.titleSmall)
                Text(
                    "• Your existing goals, progress and habit links are never deleted.\n" +
                        "• Matching records keep their completion history — you choose what to update.\n" +
                        "• Importing never replaces your whole app (that's the separate backup restore).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = onChoose) { Text("Choose a Goal Plan file") }
    }
}

@Composable
private fun ReadyContent(state: GoalPlanImportUiState, vm: GoalPlanImportViewModel,
                         dateFormatter: DateTimeFormatter, onViewGoal: (Long) -> Unit,
                         importing: Boolean = false) {
    val preview = state.preview ?: return
    val summary = state.summary ?: return
    val document = preview.document
    val goal = document.goal
    // Sections above ten records start collapsed; the state survives recomposition.
    var phasesExpanded by rememberSaveable("phases") { mutableStateOf(document.phases.size <= 10) }
    var milestonesExpanded by rememberSaveable("milestones") { mutableStateOf(document.milestones.size <= 10) }
    var tasksExpanded by rememberSaveable("tasks") { mutableStateOf(document.tasks.size <= 10) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "overview") {
                ImportGoalOverview(goal, document, state.fileName, dateFormatter, state, vm)
            }
            item(key = "summary") { ImportChangeSummary(summary) }
            if (summary.blocked.isNotEmpty()) {
                item(key = "blocked") {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Resolve before importing", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            summary.blocked.forEach { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }
            importSection("Phases", document.phases.size, "phases", phasesExpanded, { phasesExpanded = !phasesExpanded }) {
                items(preview.changes.filter { it.kind == GoalPlanRecordKind.PHASE }, key = { it.uuid }) { change ->
                    RecordChangeRow(change, state.decisions[change.uuid] ?: change.defaultDecision,
                        state.summary?.goalDecision, vm)
                }
            }
            importSection("Milestones", document.milestones.size, "milestones", milestonesExpanded, { milestonesExpanded = !milestonesExpanded }) {
                items(preview.changes.filter { it.kind == GoalPlanRecordKind.MILESTONE }, key = { it.uuid }) { change ->
                    RecordChangeRow(change, state.decisions[change.uuid] ?: change.defaultDecision,
                        state.summary?.goalDecision, vm)
                }
            }
            importSection("Tasks", document.tasks.size, "tasks", tasksExpanded, { tasksExpanded = !tasksExpanded }) {
                items(preview.changes.filter { it.kind == GoalPlanRecordKind.TASK }, key = { it.uuid }) { change ->
                    RecordChangeRow(change, state.decisions[change.uuid] ?: change.defaultDecision,
                        state.summary?.goalDecision, vm)
                }
            }
            if (document.dependencies.isNotEmpty()) {
                item(key = "dependencies-header") {
                    Text("Dependencies", style = MaterialTheme.typography.titleMedium)
                }
                items(preview.dependencies, key = { it.taskUuid + "-" + it.prerequisiteUuid }) { edge ->
                    DependencyRow(edge)
                }
            }
            item(key = "explanation") {
                Text(
                    "Nothing is saved until you confirm. Keeping a record preserves its progress, " +
                        "history and habit links exactly as they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Always-visible footer: the explicit final action never scrolls out of reach.
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        summary.noop -> "Nothing would change with these decisions — confirming reports an honest no-op."
                        else -> "${summary.toAdd} to add · ${summary.toUpdate} to update · ${summary.toCopy} to copy · " +
                            "${summary.reused} already imported · ${summary.skipped} skipped"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = !importing && summary.blocked.isEmpty(),
                    onClick = vm::confirmImport,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (importing) "Importing…" else "Confirm import") }
            }
        }
    }
}

// The IMPORTING phase renders the ready content with a frozen footer: the reviewed plan stays
// visible, the Confirm button reads "Importing…" and stays disabled until the phase changes.

@Composable
private fun ImportGoalOverview(goal: com.lifetrack.app.data.GoalPlanGoal,
                               document: com.lifetrack.app.data.GoalPlanDocument,
                               fileName: String?, dateFormatter: DateTimeFormatter,
                               state: GoalPlanImportUiState, vm: GoalPlanImportViewModel) {
    val summary = state.summary
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(goal.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            goal.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(goal.category ?: "Personal", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(goal.priority ?: "MEDIUM", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                goal.plannedStartDate?.let { Text("From ${it.format(dateFormatter)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                goal.targetDate?.let { Text("By ${it.format(dateFormatter)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(
                "${document.phases.size} phases · ${document.milestones.size} milestones · " +
                    "${document.tasks.size} tasks · ${document.dependencies.size} dependencies",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            fileName?.let {
                Text("File: $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            // The goal is itself a record with a decision; its differences are shown in full.
            val goalChange = state.preview?.changes?.firstOrNull { it.kind == GoalPlanRecordKind.GOAL }
            if (goalChange != null) {
                Text("This goal", style = MaterialTheme.typography.titleSmall)
                ClassificationLabel(goalChange)
                goalChange.differences.forEach { difference ->
                    Text(
                        "${difference.field}: was \"${difference.existing ?: "not set"}\" → plan says \"${difference.imported ?: "not set"}\"",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (summary?.goalDecision == GoalPlanDecision.IMPORT_AS_COPY) {
                    Text(
                        "Every record will be copied into a new goal — the existing roadmap stays untouched.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DecisionChips(goalChange, state.decisions[goalChange.uuid] ?: goalChange.defaultDecision, vm)
            }
        }
    }
}

/** Collapsible record section inside the preview list: header row, then the records when shown. */
private fun LazyListScope.importSection(title: String, count: Int, tag: String,
                                        expanded: Boolean, onToggle: () -> Unit,
                                        content: LazyListScope.() -> Unit) {
    item(key = "$tag-header") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$title ($count)", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onToggle) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
    }
    if (expanded) content()
}

/** The decision outcome counts, shown between the overview and the record sections. */
@Composable
private fun ImportChangeSummary(summary: com.lifetrack.app.data.GoalPlanImportSummary) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What would change", style = MaterialTheme.typography.titleSmall)
            Text(
                "${summary.toAdd} new · ${summary.toUpdate} updates · ${summary.toCopy} copies · " +
                    "${summary.reused} kept · ${summary.skipped} skipped · " +
                    "${summary.edgesToAdd} dependencies to add",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One record's existing-vs-imported review with its decision chips. */
@Composable
private fun RecordChangeRow(change: GoalPlanRecordChange, selected: GoalPlanDecision,
                            goalDecision: GoalPlanDecision?, vm: GoalPlanImportViewModel) {
    val forcedCopy = goalDecision == GoalPlanDecision.IMPORT_AS_COPY && change.kind != GoalPlanRecordKind.GOAL
    val description = buildString {
        append(change.importedTitle).append(". ").append(change.classification.label).append(".")
        change.differences.forEach { difference ->
            append(" ${difference.field}: was ${difference.existing ?: "not set"}, plan says ${difference.imported ?: "not set"}.")
        }
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(change.importedTitle, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                ClassificationLabel(change)
            }
            change.differences.forEach { difference ->
                Text(
                    "${difference.field}: was \"${difference.existing ?: "not set"}\" → plan says \"${difference.imported ?: "not set"}\"",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (forcedCopy) {
                Text("Copied with the goal", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DecisionChips(change, selected, vm)
            }
        }
    }
}

@Composable
private fun ClassificationLabel(change: GoalPlanRecordChange) {
    val (container, content) = when (change.classification) {
        GoalPlanClassification.NEW -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        GoalPlanClassification.EQUIVALENT -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        GoalPlanClassification.CONFLICT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        GoalPlanClassification.CROSS_TYPE_COLLISION,
        GoalPlanClassification.FOREIGN_GOAL_OWNERSHIP -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Text(
        change.classification.label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier.heightIn(min = 24.dp).background(container, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun DecisionChips(change: GoalPlanRecordChange, selected: GoalPlanDecision, vm: GoalPlanImportViewModel) {
    // Two rows of two: four chips never fit one 360dp row.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected == GoalPlanDecision.KEEP_EXISTING,
                enabled = GoalPlanDecision.KEEP_EXISTING in change.allowedDecisions,
                onClick = { vm.setDecision(change.uuid, GoalPlanDecision.KEEP_EXISTING) },
                label = { Text("Keep existing") }
            )
            FilterChip(
                selected = selected == GoalPlanDecision.KEEP_IMPORTED,
                enabled = GoalPlanDecision.KEEP_IMPORTED in change.allowedDecisions,
                onClick = { vm.setDecision(change.uuid, GoalPlanDecision.KEEP_IMPORTED) },
                label = { Text("Use imported") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected == GoalPlanDecision.IMPORT_AS_COPY,
                enabled = GoalPlanDecision.IMPORT_AS_COPY in change.allowedDecisions,
                onClick = { vm.setDecision(change.uuid, GoalPlanDecision.IMPORT_AS_COPY) },
                label = { Text("Copy as new") }
            )
            FilterChip(
                selected = selected == GoalPlanDecision.SKIP,
                enabled = GoalPlanDecision.SKIP in change.allowedDecisions,
                onClick = { vm.setDecision(change.uuid, GoalPlanDecision.SKIP) },
                label = { Text("Skip") }
            )
        }
    }
}

@Composable
private fun DependencyRow(edge: com.lifetrack.app.data.GoalPlanDependencyChange) {
    Text(
        "\"${edge.taskTitle}\" needs \"${edge.prerequisiteTitle}\"" +
            if (edge.initialStatus == com.lifetrack.app.data.GoalPlanEdgeStatus.ALREADY_PRESENT) " (already recorded)" else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DoneContent(state: GoalPlanImportUiState, vm: GoalPlanImportViewModel,
                        onViewGoal: (Long) -> Unit, onBack: () -> Unit, onChooseAnother: () -> Unit) {
    val result = state.result ?: return
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (result.noop) "Nothing needed to change" else "Import complete",
            style = MaterialTheme.typography.titleLarge
        )
        if (result.noop) {
            Text(
                "Every record was already imported or skipped, so LifeTrack wrote nothing. " +
                    "Your data is exactly as it was.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // The real counts — computed outside the Text so the summary can never render empty.
            val parts = buildList {
                if (result.createdGoal) add("1 goal created")
                if (result.updatedGoal) add("1 goal updated")
                listOf(
                    "phases" to (result.createdPhases to result.updatedPhases),
                    "milestones" to (result.createdMilestones to result.updatedMilestones),
                    "tasks" to (result.createdTasks to result.updatedTasks)
                ).forEach { (label, counts) ->
                    val (created, updated) = counts
                    if (created > 0) add("$created $label added")
                    if (updated > 0) add("$updated $label updated")
                }
                if (result.copiedRecords > 0) add("${result.copiedRecords} records copied")
                if (result.reusedRecords > 0) add("${result.reusedRecords} records reused")
                if (result.dependenciesAdded > 0) add("${result.dependenciesAdded} dependencies added")
                if (result.scheduleRevisions > 0) add("${result.scheduleRevisions} date changes recorded in history")
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val goalId = result.goalId
        if (goalId != null) {
            Button(onClick = { onViewGoal(goalId) }) { Text("View goal") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::reset) { Text("Import another file") }
            TextButton(onClick = onBack) { Text("Back to Goals") }
        }
    }
}

@Composable
private fun FailedContent(state: GoalPlanImportUiState, vm: GoalPlanImportViewModel,
                          onBack: () -> Unit, onChooseAnother: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("The import did not happen", style = MaterialTheme.typography.titleLarge)
        Text(
            state.error ?: "The selected file could not be imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "Your data is unchanged — a failed import leaves the database exactly as it was.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.preview != null) {
            Button(onClick = vm::dismissError) { Text("Return to preview") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseAnother) { Text("Choose a different file") }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}
