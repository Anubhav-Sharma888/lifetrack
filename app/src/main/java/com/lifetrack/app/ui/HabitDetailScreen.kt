package com.lifetrack.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.*
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.ui.components.CompletionTrend
import com.lifetrack.app.ui.components.HeatmapWidget
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(id: Long, state: LifeTrackState, vm: HabitViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val habit = state.habits.firstOrNull { it.id == id }
    if (habit == null) {
        Column(Modifier.padding(24.dp)) { Text("This habit no longer exists."); TextButton(onClick = onBack) { Text("Back") } }
        return
    }
    var selected by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val date = selected?.let(LocalDate::parse)?.coerceAtMost(state.today) ?: state.today
    var showDate by remember { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var freezeDialog by rememberSaveable { mutableStateOf(false) }
    var deletionStep by remember { mutableIntStateOf(0) }
    var deleteText by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    val ownLogs = state.logs.filter { it.habitId == id }
    val log = ownLogs.firstOrNull { it.date == date }
    val goal = habit.goalOn(date)
    val eligible = !habit.isArchived && Analytics.eligible(habit, date, state.pauses) && date <= state.today
    val spent = state.freezes.any { it.month == YearMonth.from(date).toString() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Your journey") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }, actions = {
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit habit") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(habit.icon, style = MaterialTheme.typography.displayMedium)
            Text(habit.name, style = MaterialTheme.typography.headlineLarge)
            Text("${habit.category} · Started ${habit.startDate}", style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurfaceVariant)
            habit.description?.let { Text(it) }
            habit.afterHabitId?.let { parent -> state.habits.firstOrNull { it.id == parent }?.let { Text("After I ${it.name}, I will ${habit.name}.", color = MaterialTheme.colorScheme.primary) } }
            if (habit.isArchived) Text("Archived · History preserved", color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Current streak", Analytics.currentStreak(habit, ownLogs, state.today, state.pauses).toString(), Modifier.weight(1f))
                StatCard("Completions", ownLogs.count { it.status == LogStatus.COMPLETED }.toString(), Modifier.weight(1f))
                StatCard("Best streak", Analytics.bestStreak(habit, ownLogs, state.today, state.pauses).toString(), Modifier.weight(1f))
            }
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DateRange, null); Spacer(Modifier.width(8.dp)); Text("$date · ${Analytics.dayNumber(habit, date)?.let { "Day $it" } ?: "Not started"}")
            }
            // Almanac check-in well: sunken parchment inset instead of an elevated card.
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(log?.let { if (it.isFrozen) "❄ Streak frozen" else when (it.status) { LogStatus.COMPLETED -> "✓ Completed"; LogStatus.SKIPPED -> "↪ Skipped"; LogStatus.FAILED -> "Missed"; LogStatus.IN_PROGRESS -> "In progress" } } ?: "No check-in yet", style = MaterialTheme.typography.titleLarge)
                    if (goal.targetCount > 1) {
                        val count = if (log?.status == LogStatus.COMPLETED) maxOf(goal.targetCount, log.progress) else log?.progress ?: 0
                        Text("$count / ${goal.targetCount} ${goal.targetUnit}", style = AlmanacType.monoData)
                        LinearProgressIndicator(progress = { (count.toFloat() / goal.targetCount).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                    }
                    log?.note?.let { Text(it) }
                    Button(enabled = eligible && !busy, onClick = { showLog = true }, modifier = Modifier.fillMaxWidth()) { Text(if (log == null) "Add check-in / note" else "Edit check-in / note") }
                    if (eligible && date < state.today && (log == null || log.status in setOf(LogStatus.FAILED, LogStatus.IN_PROGRESS))) {
                        OutlinedButton(enabled = !spent && !busy, onClick = { freezeDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(if (spent) "${YearMonth.from(date)} freeze used" else "❄ Use monthly streak freeze") }
                    }
                    if (habit.isArchived) Text("Restore this habit to change check-ins.", style = MaterialTheme.typography.bodySmall)
                    if (date < habit.startDate) Text("Check-ins begin on ${habit.startDate}.", style = MaterialTheme.typography.bodySmall)
                    else if (!eligible && !habit.isArchived) Text("This is a rest day or a preserved pause in your timeline.", style = MaterialTheme.typography.bodySmall)
                }
            }
            CompletionTrend(habit, ownLogs, state.today, pauses = state.pauses)
            if (ownLogs.isNotEmpty()) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("Recent recordings", style = MaterialTheme.typography.titleMedium)
                HistorySpine(ownLogs)
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Your whole routine", style = MaterialTheme.typography.titleLarge)
            Text("Color shows the percentage of all eligible habits completed each day.", style = MaterialTheme.typography.bodySmall)
            HeatmapWidget(state.habits, state.logs, state.today, pauses = state.pauses)
            Text("Skips and freezes bridge a streak without adding a completion. A missing check-in today gives you until midnight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = { vm.archiveHabit(id, !habit.isArchived) }, modifier = Modifier.fillMaxWidth()) { Text(if (habit.isArchived) "Restore habit" else "Archive habit · keep history") }
            TextButton(enabled = !busy, onClick = { deletionStep = 1 }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete habit permanently", color = almanacColors().terracotta, textDecoration = TextDecoration.Underline)
            }
        }
    }
    if (showDate) DateSelectionDialog(date, latest = state.today, onDismiss = { showDate = false }, onSelect = { selected = it.toString(); showDate = false })
    if (showLog) LogEditorDialog(habit, date, log, vm, onDismiss = { showLog = false }, initialStatus = log?.status ?: LogStatus.COMPLETED)
    if (freezeDialog) {
        var note by rememberSaveable(id, date) { mutableStateOf(log?.note ?: "") }
        LaunchedEffect(id, date) { vm.clearOperationError() }
        AlertDialog(onDismissRequest = { freezeDialog = false }, title = { Text("Protect $date?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This uses the app's one freeze for ${YearMonth.from(date)}. It stays spent if you later edit or delete this check-in.")
                OutlinedTextField(note, { if (it.length <= 4000) note = it }, label = { Text("Note · optional") }, colors = almanacFieldColors())
                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.freeze(id, date, note.ifBlank { null }) { freezeDialog = false } }) { Text("Use freeze") } }, dismissButton = { TextButton(enabled = !busy, onClick = { freezeDialog = false }) { Text("Cancel") } })
    }
    if (deletionStep == 1) AlertDialog(onDismissRequest = { deletionStep = 0 }, title = { Text("Delete ${habit.name}?") }, text = { Text("All its check-ins and notes will be erased. Archiving keeps your history. This cannot be undone.") }, confirmButton = { TextButton(onClick = { deletionStep = 2; deleteText = "" }) { Text("Continue", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deletionStep = 0 }) { Text("Keep habit") } })
    if (deletionStep == 2) AlertDialog(onDismissRequest = { deletionStep = 0 }, title = { Text("Confirm permanent deletion") }, text = {
        Column { Text("Type DELETE to erase this habit and all its history."); OutlinedTextField(deleteText, { deleteText = it }, singleLine = true, label = { Text("DELETE") }, colors = almanacFieldColors()) }
    }, confirmButton = { TextButton(enabled = deleteText == "DELETE" && !busy, onClick = { vm.deleteHabit(id, onDeleted = onBack); deletionStep = 0 }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deletionStep = 0 }) { Text("Cancel") } })
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    // Almanac stat well: sunken parchment with a mono metric figure and caps label.
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(label, style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LogEditorDialog(habit: Habit, date: LocalDate, current: HabitLog?, vm: HabitViewModel, onDismiss: () -> Unit, initialStatus: LogStatus = current?.status ?: LogStatus.SKIPPED) {
    var statusName by rememberSaveable(habit.id, date) { mutableStateOf(initialStatus.name) }
    val status = LogStatus.valueOf(statusName)
    var note by rememberSaveable(habit.id, date) { mutableStateOf(current?.note ?: "") }
    val goal = habit.goalOn(date)
    var progress by rememberSaveable(habit.id, date) { mutableStateOf((if (current?.status == LogStatus.COMPLETED) maxOf(goal.targetCount, current.progress) else current?.progress ?: 0).toString()) }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    LaunchedEffect(habit.id, date) { vm.clearOperationError() }
    // A finite content viewport avoids intrinsic-height feedback between a platform dialog,
    // growing text fields and the scroll container, including with large accessibility fonts.
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp)) {
            Column(Modifier.heightIn(max = 620.dp).padding(24.dp)) {
                Text("${habit.icon} ${habit.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date.toString(), style = MaterialTheme.typography.labelLarge)
                    Text("Tap a status to save it instantly.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LogStatus.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = status == option, enabled = !busy,
                                role = androidx.compose.ui.semantics.Role.RadioButton, onClick = {
                                    statusName = option.name
                                    // One-tap save for whole-day statuses; IN_PROGRESS keeps the Save
                                    // button because its progress field needs a deliberate value.
                                    if (option != current?.status && option != LogStatus.IN_PROGRESS) {
                                        vm.setLog(habit.id, date, option, note.ifBlank { null }, onSaved = onDismiss)
                                    }
                                })
                            .semantics(mergeDescendants = true) {}, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = status == option, onClick = null, enabled = !busy)
                            Spacer(Modifier.width(10.dp))
                            Text(when (option) {
                                LogStatus.COMPLETED -> "Completed"
                                LogStatus.SKIPPED -> "Skipped · protects chain"
                                LogStatus.FAILED -> "Missed · breaks chain"
                                LogStatus.IN_PROGRESS -> "In progress"
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (goal.targetCount > 1 || status == LogStatus.IN_PROGRESS) OutlinedTextField(progress,
                        { if (it.length <= 6 && it.all(Char::isDigit)) progress = it }, label = { Text("Progress · ${goal.targetCount} ${goal.targetUnit} goal") },
                        colors = almanacFieldColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
                    OutlinedTextField(note, { if (it.length <= 4000) note = it }, label = { Text("Note · optional") }, minLines = 2,
                        colors = almanacFieldColors(), modifier = Modifier.fillMaxWidth())
                    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (current != null) TextButton(enabled = !busy, onClick = { vm.clearLog(habit.id, date, onSaved = onDismiss) }) { Text("Clear check-in", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    TextButton(enabled = !busy && (status != LogStatus.IN_PROGRESS || progress.toIntOrNull() in 0..100_000), onClick = {
                        if (status == LogStatus.IN_PROGRESS) vm.setProgress(habit.id, date, progress.toInt(), note.ifBlank { null }, onSaved = onDismiss)
                        else vm.setLog(habit.id, date, status, note.ifBlank { null }, onSaved = onDismiss)
                    }) { Text(if (busy) "Saving…" else "Save") }
                }
            }
        }
    }
}
