package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.WorkoutActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun formatQuantityLabel(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun recorderLabel(status: LogStatus, progress: Int, isFrozen: Boolean): String = when (status) {
    LogStatus.COMPLETED -> "Completed"
    LogStatus.SKIPPED -> if (isFrozen) "Frozen" else "Skipped"
    LogStatus.FAILED -> "Missed"
    LogStatus.IN_PROGRESS -> "$progress in progress"
}

/** Flight Recorder: seven-day recording strip. The orange marker tracks the selected day;
 *  a filled tick marks days with completed recordings. Cells are at least 48dp wide
 *  (horizontally scrollable on narrow screens) and expose full date, selection and
 *  recording state to accessibility services. Tapping a day switches the date. */
@Composable
fun RecordingStrip(selected: LocalDate, today: LocalDate, completedDates: Set<LocalDate>, onSelect: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (6 downTo 0).forEach { ago ->
            val day = today.minusDays(ago.toLong())
            val isSelected = day == selected
            val hasLogs = day in completedDates
            val dayLabel = day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
            Column(
                modifier = Modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = day <= today, onClick = { onSelect(day) })
                    .semantics {
                        contentDescription = "$dayLabel · " + when {
                            hasLogs && isSelected -> "has recordings · selected day"
                            hasLogs -> "has recordings"
                            isSelected -> "selected day · no recordings yet"
                            else -> "no recordings yet"
                        }
                        this.selected = isSelected
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth(0.6f).height(2.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent))
                Spacer(Modifier.height(4.dp))
                Text(day.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${day.dayOfMonth}", style = AlmanacType.monoData,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    when {
                        hasLogs -> MaterialTheme.colorScheme.secondary
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> Color.Transparent
                    }))
            }
        }
    }
}

/** Flight Recorder spine: thin vertical rule with a node dot, wrapping recorder rows. */
@Composable
fun SpineItem(nodeColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Box(Modifier.width(2.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.size(9.dp).clip(CircleShape).border(2.dp, nodeColor, CircleShape).background(MaterialTheme.colorScheme.surface))
            Box(Modifier.width(2.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Exercise trace: recent entries as scaled ticks on a shared baseline, units in the label. */
@Composable
fun ExerciseTrace(activity: WorkoutActivity, entries: List<ActivityEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val ordered = entries.sortedBy { it.date }
    val max = ordered.maxOf { it.quantity }
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(bottom = 3.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ordered.forEach { entry ->
                Box(Modifier.weight(1f).height((30 * (entry.quantity / max).coerceIn(0.06, 1.0)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f))
                    .semantics { contentDescription = entry.date.toString() + ": " + formatQuantityLabel(entry.quantity) + " " + activity.unit })
            }
        }
        HorizontalDivider(color = baseline)
        Text("Daily totals · " + activity.unit + " · oldest to newest", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Recent habit recordings as dated spine rows with labeled status nodes. */
@Composable
fun HistorySpine(logs: List<HabitLog>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        logs.sortedByDescending { it.date }.take(10).forEach { log ->
            SpineItem(nodeColor = when (log.status) {
                LogStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                LogStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
                LogStatus.FAILED -> almanacColors().terracotta
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }) {
                Column {
                    Text(recorderLabel(log.status, log.progress, log.isFrozen),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(log.date.toString(), style = AlmanacType.monoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    log.note?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
fun ActivitiesScreen(state: LifeTrackState, vm: HabitViewModel) {
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val firstDay = LocalDate.of(1900, 1, 1)
    val day = selectedDay?.let(LocalDate::parse)?.coerceIn(firstDay, state.today) ?: state.today
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()) }
    val activitiesById = remember(state.activities) { state.activities.associateBy { it.id } }
    val recentDays = remember(state.activityEntries, day) {
        state.activityEntries.filter { it.date <= day }
            .groupBy { it.date }.toSortedMap(reverseOrder()).entries.take(7)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Activity ledger", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Activities", style = MaterialTheme.typography.headlineLarge)
            Text("Record workouts and other activities alongside your habits.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = day > firstDay, onClick = { selectedDay = day.minusDays(1).toString() }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day")
                }
                TextButton(onClick = { selectedDay = null }, modifier = Modifier.weight(1f)) {
                    Text(if (day == state.today) "Today · ${day.format(dateFormat)}" else day.format(dateFormat))
                }
                IconButton(enabled = day < state.today, onClick = { selectedDay = day.plusDays(1).toString() }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day")
                }
            }
        }
        ActivitySection(state, vm, day)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent activity by date", style = MaterialTheme.typography.titleLarge)
            if (recentDays.isEmpty()) {
                Text("No activity entries through this date.", style = MaterialTheme.typography.bodyMedium)
            }
            recentDays.forEach { (date, entries) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(date.format(dateFormat), style = MaterialTheme.typography.titleMedium)
                        entries.sortedBy { activitiesById[it.activityId]?.sortOrder }.forEach { entry ->
                            activitiesById[entry.activityId]?.let { activity ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("${activity.name} · ${formatQuantityLabel(entry.quantity)} ${activity.unit}",
                                    style = MaterialTheme.typography.bodyMedium)
                                entry.note?.takeIf(String::isNotBlank)?.let { note ->
                                    Text(note, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared activity actions for Today and the Activities screen. */
@Composable
fun ActivitySection(state: LifeTrackState, vm: HabitViewModel, date: LocalDate, modifier: Modifier = Modifier) {
    val busy by vm.busy.collectAsState()
    var editingActivityId by rememberSaveable { mutableStateOf<Long?>(null) }
    var loggingActivityId by rememberSaveable { mutableStateOf<Long?>(null) }
    var loggingDate by rememberSaveable { mutableStateOf<String?>(null) }
    var historyActivityId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingActivityId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Activities", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { editingActivityId = 0L }) {
                Icon(Icons.Outlined.Add, null); Text("Activity")
            }
        }
        if (state.activities.isEmpty()) {
            Text("Track workouts, runs and repetitions — any activity, any day, in any combination.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { editingActivityId = 0L }) { Text("Add your first activity") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.activities.sortedBy { it.sortOrder }.forEach { activity ->
                    val entry = state.activityEntries.firstOrNull { it.activityId == activity.id && it.date == date }
                    val weekAgo = date.minusDays(6)
                    val week = state.activityEntries.filter { it.activityId == activity.id && it.date in weekAgo..date }
                    val weekTotal = week.sumOf { it.quantity }
                    val weekLabel = if (week.isEmpty()) "No entries this week" else "${formatQuantityLabel(weekTotal)} ${activity.unit} this week"
                    val recent = state.activityEntries.filter { it.activityId == activity.id }
                        .sortedByDescending { it.date }.take(14)
                    SpineItem(nodeColor = if (entry != null) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                        Text(activity.icon, fontSize = 20.sp)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(activity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(activity.unit + (activity.target?.let { " · target ${formatQuantityLabel(it)}" } ?: "") + " · $weekLabel",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        entry?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        entry?.let {
                                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                                Text("${formatQuantityLabel(it.quantity)} ${activity.unit}",
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                    style = AlmanacType.monoData, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    IconButton(enabled = !busy, onClick = { historyActivityId = activity.id }) {
                                        Icon(Icons.Outlined.History, "History for ${activity.name}")
                                    }
                                    Button(enabled = !busy, onClick = { loggingActivityId = activity.id; loggingDate = date.toString() },
                                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                        Text(if (entry == null) "Log" else "Edit")
                                    }
                                }
                                if (recent.isNotEmpty()) ExerciseTrace(activity, recent)
                            }
                        }
                    }
                }
            }
        }
    }
    editingActivityId?.let { id -> ActivityEditorDialog(id, state, vm, onDismiss = { editingActivityId = null }) }
    loggingActivityId?.let { id ->
        ActivityLogDialog(id, loggingDate?.let(LocalDate::parse) ?: date, state, vm, onDismiss = { loggingActivityId = null })
    }
    historyActivityId?.let { id ->
        state.activities.firstOrNull { it.id == id }?.let { activity ->
            ActivityHistoryDialog(activity, state, vm,
                onDismiss = { historyActivityId = null },
                onLogFor = { logDate -> historyActivityId = null; loggingActivityId = activity.id; loggingDate = logDate.toString() },
                onDelete = { historyActivityId = null; deletingActivityId = activity.id })
        }
    }
    deletingActivityId?.let { id ->
        state.activities.firstOrNull { it.id == id }?.let { activity ->
            val entryCount = state.activityEntries.count { it.activityId == id }
            AlertDialog(onDismissRequest = { deletingActivityId = null }, title = { Text("Delete this activity?") }, text = {
                Text("Deleting ${activity.name} also removes its $entryCount logged ${if (entryCount == 1) "entry" else "entries"}. This cannot be undone.")
            }, confirmButton = { TextButton(enabled = !busy, onClick = { vm.deleteActivity(id, onSaved = { deletingActivityId = null }) }) {
                Text("Delete activity", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deletingActivityId = null }) { Text("Keep activity") } })
        }
    }
}

@Composable
fun ActivityEditorDialog(id: Long, state: LifeTrackState, vm: HabitViewModel, onDismiss: () -> Unit) {
    val existing = state.activities.firstOrNull { it.id == id }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: "💪") }
    var unit by rememberSaveable { mutableStateOf(existing?.unit ?: "") }
    var target by rememberSaveable { mutableStateOf(existing?.target?.let { formatQuantityLabel(it) } ?: "") }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    LaunchedEffect(id) { vm.clearOperationError() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "New activity" else "Edit activity") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("Activity name") }, singleLine = true, colors = almanacFieldColors())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(icon, { if (it.length <= 32) icon = it }, label = { Text("Emoji") }, singleLine = true, colors = almanacFieldColors(), modifier = Modifier.weight(1f))
                OutlinedTextField(unit, { if (it.length <= 24) unit = it }, label = { Text("Unit · pushups, miles…") }, singleLine = true, colors = almanacFieldColors(), modifier = Modifier.weight(2f))
            }
            OutlinedTextField(target, { if (it.length <= 7 && it.all { c -> c.isDigit() || c == '.' }) target = it },
                label = { Text("Daily target · optional") }, singleLine = true, colors = almanacFieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Text("Actual quantities are recorded as-is: logging 32 against a 20 target is a good day, never an overshoot.", style = MaterialTheme.typography.bodySmall)
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        vm.saveActivity(WorkoutActivity(id = existing?.id ?: 0, name = name.trim(), icon = icon.trim().ifBlank { "💪" },
            unit = unit.trim(), target = target.toDoubleOrNull(), sortOrder = existing?.sortOrder ?: state.activities.size,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(), updatedAt = System.currentTimeMillis()),
            onSaved = { onDismiss() })
    }) { Text(if (busy) "Saving…" else "Save activity") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ActivityLogDialog(activityId: Long, date: LocalDate, state: LifeTrackState, vm: HabitViewModel, onDismiss: () -> Unit) {
    val activity = state.activities.firstOrNull { it.id == activityId } ?: return
    val entry = state.activityEntries.firstOrNull { it.activityId == activityId && it.date == date }
    val last = state.activityEntries.filter { it.activityId == activityId }.maxByOrNull { it.date }
    var quantity by rememberSaveable(activityId, date) {
        mutableStateOf(entry?.let { formatQuantityLabel(it.quantity) } ?: last?.let { formatQuantityLabel(it.quantity) } ?: activity.target?.let { formatQuantityLabel(it) } ?: "")
    }
    var note by rememberSaveable(activityId, date) { mutableStateOf(entry?.note ?: "") }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    LaunchedEffect(activityId, date) { vm.clearOperationError() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("${activity.icon} ${activity.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(quantity, { if (it.length <= 9 && (it.isEmpty() || it.all { c -> c.isDigit() || c == '.' })) quantity = it },
                label = { Text("Quantity · ${activity.unit}") }, singleLine = true, colors = almanacFieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Text(if (activity.target != null) "Target ${formatQuantityLabel(activity.target)} — actuals are recorded exactly as performed, even beyond it."
                else "No target — log the real number. Same-day logs replace the previous value.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("Note · optional") }, singleLine = true, colors = almanacFieldColors())
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && quantity.toDoubleOrNull()?.let { it > 0 } == true, onClick = {
        vm.setActivityEntry(activityId, date, quantity.toDouble(), note.ifBlank { null }, onSaved = onDismiss)
    }) { Text(if (busy) "Saving…" else "Save entry") } }, dismissButton = {
        Row {
            if (entry != null) TextButton(enabled = !busy, onClick = { vm.clearActivityEntry(activityId, date, onSaved = onDismiss) }) {
                Text("Clear entry", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    })
}

@Composable
fun ActivityHistoryDialog(activity: WorkoutActivity, state: LifeTrackState, vm: HabitViewModel,
                          onDismiss: () -> Unit, onLogFor: (LocalDate) -> Unit, onDelete: () -> Unit) {
    val history = state.activityEntries.filter { it.activityId == activity.id }.sortedByDescending { it.date }
    val monthAgo = state.today.minusDays(27)
    val inWindow = history.filter { it.date >= monthAgo }
    val total = inWindow.sumOf { it.quantity }
    val best = inWindow.maxOfOrNull { it.quantity } ?: 0.0
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${activity.icon} ${activity.name}") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("History · newest first", style = MaterialTheme.typography.titleSmall)
            if (activity.target != null) Text("Target ${formatQuantityLabel(activity.target)} ${activity.unit} per day · actuals may sit above or below it.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("${formatQuantityLabel(total)}", "LAST 28 DAYS", Modifier.weight(1f))
                Stat("${inWindow.size}", "DAYS LOGGED", Modifier.weight(1f))
                Stat("${formatQuantityLabel(best)}", "BEST DAY", Modifier.weight(1f))
            }
            if (history.isEmpty()) Text("No entries yet — log the first session from Today.", style = MaterialTheme.typography.bodySmall)
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Activity progression", style = MaterialTheme.typography.titleMedium)
                ExerciseTrace(activity, history.take(14))
                Text("Entries", style = MaterialTheme.typography.titleMedium)
                history.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Date seal: two-line month / day stamp.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(entry.date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.date.format(DateTimeFormatter.ofPattern("d", Locale.getDefault())),
                                style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${formatQuantityLabel(entry.quantity)} ${activity.unit}", style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurface)
                            entry.note?.let { note ->
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 4.dp)) {
                                    Text(note, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                        IconButton(onClick = { onLogFor(entry.date) }) {
                            Icon(Icons.Outlined.Edit, "Edit ${activity.name} entry for ${entry.date}")
                        }
                    }
                }
            }
            TextButton(enabled = !vm.busy.collectAsState().value, onClick = onDelete) {
                Text("Delete activity", color = MaterialTheme.colorScheme.error) }
            Text("History sits alongside habit consistency in Analytics.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    // Almanac stat well: sunken parchment with mono figures and caps labels.
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(label, style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
