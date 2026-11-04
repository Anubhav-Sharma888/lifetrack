package com.lifetrack.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.*
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.ui.components.HabitCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TodayScreen(state: LifeTrackState, vm: HabitViewModel, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val busy by vm.busy.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val minimum = LocalDate.of(1900, 1, 1)
    val date = selected?.let(LocalDate::parse)?.coerceIn(minimum, state.today) ?: state.today
    var showDate by rememberSaveable { mutableStateOf(false) }
    var editingLogId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var orderMenuId by rememberSaveable { mutableStateOf<Long?>(null) }
    var quickMenuId by rememberSaveable { mutableStateOf<Long?>(null) }
    var focusUnmarked by rememberSaveable { mutableStateOf(false) }
    var nudgeDismissed by rememberSaveable { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf(emptyList<Long>()) }
    val active = state.habits.filterNot { it.isArchived }
    val byId = active.associateBy { it.id }
    val dayLogs = remember(state.logs, date) { state.logs.filter { it.date == date }.associateBy { it.habitId } }
    LaunchedEffect(active.map { it.id to it.sortOrder }) { order = active.sortedBy { it.sortOrder }.map { it.id } }
    val habits = order.mapNotNull(byId::get).filter { habit ->
        val due = Analytics.eligible(habit, date, state.pauses)
        val completed = dayLogs[habit.id]?.status == LogStatus.COMPLETED
        (query.isBlank() || listOf(habit.name, habit.description.orEmpty(), habit.category).any { it.contains(query.trim(), true) }) &&
            (category == null || habit.category == category) && when (filter) { "Due" -> due && !completed; "Completed" -> due && completed; "Rest / planned" -> !due; else -> true }
    }
    val due = active.filter { Analytics.eligible(it, date, state.pauses) }
    val completed = due.count { dayLogs[it.id]?.status == LogStatus.COMPLETED }
    val unmarkedYesterday = remember(state.habits, state.logs, state.pauses, state.today) {
        Analytics.unmarked(state.habits, state.logs, state.today.minusDays(1), state.pauses)
    }
    val showNudge = date == state.today && unmarkedYesterday.isNotEmpty() && nudgeDismissed != state.today.minusDays(1).toString()
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = (from.key as? Long)?.let(order::indexOf) ?: -1
        val toIndex = (to.key as? Long)?.let(order::indexOf) ?: -1
        if (fromIndex >= 0 && toIndex >= 0) order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "today-header") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("LifeTrack", style = MaterialTheme.typography.headlineLarge); Text("Small steps. Your own pace.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Outlined.FilterList, "Search and filter habits") }
                    FilledTonalIconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "Add habit") }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = date > minimum, onClick = { selected = date.minusDays(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day") }
                    TextButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                        Text(if (date == state.today) "Today · ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))}" else date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
                    }
                    IconButton(enabled = date < state.today, onClick = { selected = date.plusDays(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day") }
                }
                // Flight Recorder: oversized date headline in tabular numerals over a thin measurement rule.
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black, fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                // Flight Recorder: seven-day recording strip over the measurement rule.
                RecordingStrip(selected = date, today = state.today,
                    completedDates = state.logs.filter { it.status == LogStatus.COMPLETED }.map { it.date }.toSet(),
                    onSelect = { selected = it.toString(); focusUnmarked = false })
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                if (date != state.today) TextButton(onClick = { selected = null; focusUnmarked = false }) { Text("Back to today") }
                Text("$completed of ${due.size} scheduled habits completed", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (due.isEmpty()) 0f else completed.toFloat() / due.size }, modifier = Modifier.fillMaxWidth())
                if (showNudge) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (unmarkedYesterday.size == 1) "One habit was" else "${unmarkedYesterday.size} habits were"} left unmarked on ${state.today.minusDays(1).format(DateTimeFormatter.ofPattern("EEE, d MMM"))}", style = MaterialTheme.typography.titleSmall)
                            Text(unmarkedYesterday.take(3).joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { selected = state.today.minusDays(1).toString(); focusUnmarked = true }) { Text("Mark them now") }
                                TextButton(onClick = { nudgeDismissed = state.today.minusDays(1).toString() }) { Text("Leave it") }
                            }
                        }
                    }
                }
                if (showFilters) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(query, { query = it }, label = { Text("Search habits") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(category == null, { category = null }, label = { Text("All categories") })
                        active.map { it.category }.distinct().sorted().forEach { name -> FilterChip(category == name, { category = name }, label = { Text(name) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "Due", "Completed", "Rest / planned").forEach { choice -> FilterChip(filter == choice, { filter = choice }, label = { Text(choice) }) }
                    }
                }
            }
        }
        if (active.isEmpty()) {
            item(key = "empty-habits") { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌱", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(16.dp)); Text("Start with something small", style = MaterialTheme.typography.headlineSmall)
                Text("A page. A walk. A quiet minute. Pick a suggestion or create your own habit. Choose the days and pace that work for you.", Modifier.padding(vertical = 16.dp))
                Button(onClick = onAdd) { Text("Create your first habit") }
            }
            }
        } else if (habits.isEmpty()) {
            item(key = "filtered-habits") {
                Column(Modifier.padding(12.dp)) {
                    Text("No matching habits", style = MaterialTheme.typography.titleLarge)
                    Text("Try another search or filter.")
                    TextButton(onClick = { query = ""; category = null; filter = "All" }) { Text("Reset all filters") }
                }
            }
        } else {
            items(habits, key = { it.id }) { habit ->
                val log = dayLogs[habit.id]
                val eligible = Analytics.eligible(habit, date, state.pauses)
                ReorderableItem(reorder, key = habit.id) { _ ->
                    SpineItem(nodeColor = when (log?.status) {
                        LogStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        LogStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }) {
                    HabitCard(habit = habit,
                        dayLabel = Analytics.dayNumber(habit, date)?.let { "Day $it${if (!eligible) " · Rest day" else ""}" } ?: "Starts ${habit.startDate}",
                        status = log?.status,
                        stackLabel = habit.afterHabitId?.let { id -> state.habits.firstOrNull { it.id == id }?.name },
                        onOpen = { onOpen(habit.id) },
                        onCheckIn = {
                            if (habit.targetCount > 1 && log?.status != LogStatus.COMPLETED) vm.incrementProgress(habit.id, date)
                            else vm.quickCheckIn(habit.id, date)
                        },
                        onLongCheckIn = { editingLogId = habit.id },
                        checkInEnabled = eligible && !busy,
                        progress = log?.progress ?: 0,
                        nudged = focusUnmarked && eligible && log == null,
                        quickActions = {
                            Box {
                                IconButton(onClick = { quickMenuId = habit.id }) { Icon(Icons.Outlined.MoreVert, "More actions for ${habit.name}") }
                                DropdownMenu(quickMenuId == habit.id, onDismissRequest = { quickMenuId = null }) {
                                    DropdownMenuItem(text = { Text("Skip this day") }, enabled = eligible && log?.status != LogStatus.SKIPPED, onClick = {
                                        vm.setLog(habit.id, date, LogStatus.SKIPPED, log?.note); quickMenuId = null
                                    })
                                    DropdownMenuItem(text = { Text("Mark as missed") }, enabled = eligible && log?.status != LogStatus.FAILED, onClick = {
                                        vm.setLog(habit.id, date, LogStatus.FAILED, log?.note); quickMenuId = null
                                    })
                                    DropdownMenuItem(text = { Text("Edit check-in…") }, enabled = eligible, onClick = { editingLogId = habit.id; quickMenuId = null })
                                }
                            }
                        },
                        dragHandle = {
                            Box {
                            IconButton(onClick = { orderMenuId = habit.id }, modifier = Modifier.draggableHandle(onDragStopped = { vm.reorderHabits(order) }).semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Move habit up") {
                                        val index = order.indexOf(habit.id)
                                        if (index > 0) { order = order.toMutableList().apply { add(index - 1, removeAt(index)) }; vm.reorderHabits(order); true } else false
                                    },
                                    CustomAccessibilityAction("Move habit down") {
                                        val index = order.indexOf(habit.id)
                                        if (index in 0 until order.lastIndex) { order = order.toMutableList().apply { add(index + 1, removeAt(index)) }; vm.reorderHabits(order); true } else false
                                    }
                                )
                            }) { Icon(Icons.Outlined.DragHandle, "Reorder ${habit.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            DropdownMenu(orderMenuId == habit.id, onDismissRequest = { orderMenuId = null }) {
                                val index = order.indexOf(habit.id)
                                DropdownMenuItem(text = { Text("Move habit up") }, enabled = index > 0, onClick = {
                                    order = order.toMutableList().apply { add(index - 1, removeAt(index)) }; vm.reorderHabits(order); orderMenuId = null
                                })
                                DropdownMenuItem(text = { Text("Move habit down") }, enabled = index in 0 until order.lastIndex, onClick = {
                                    order = order.toMutableList().apply { add(index + 1, removeAt(index)) }; vm.reorderHabits(order); orderMenuId = null
                                })
                            }
                            }
                        })
                    }
                }
            }
            item { Text("Tap to check in, use the ··· menu for one-tap skip or missed, or hold to set a count or note. Rest days protect your chain.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
        }
        item(key = "activity-section") {
            ActivitySection(state, vm, date)
        }
    }
    if (showDate) DateSelectionDialog(date, latest = state.today, onDismiss = { showDate = false }, onSelect = { selected = it.toString(); focusUnmarked = false; showDate = false })
    editingLogId?.let { id -> state.habits.firstOrNull { it.id == id }?.let { habit ->
        LogEditorDialog(habit, date, dayLogs[id], vm, onDismiss = { editingLogId = null })
    } }
}
