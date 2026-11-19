package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.*
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.domain.Scheduling
import com.lifetrack.app.ui.components.HabitCard
import com.lifetrack.app.ui.components.ReflectionEditorCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TodayScreen(
    state: LifeTrackState,
    vm: HabitViewModel,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onRestoreBackup: () -> Unit = {},
    onSchedules: () -> Unit = {},
    onAchievements: () -> Unit = {}
) {
    val busy by vm.busy.collectAsState()
    val wasReset by vm.wasReset.collectAsState()
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
    val showFreshStart = wasReset && state.habits.isEmpty() && state.activities.isEmpty() && state.activityEntries.isEmpty()
    val byId = remember(active) { active.associateBy { it.id } }
    val dayLogs = remember(state.logs, date) { state.logs.filter { it.date == date }.associateBy { it.habitId } }
    LaunchedEffect(active.map { it.id to it.sortOrder }) { order = active.sortedBy { it.sortOrder }.map { it.id } }
    val habits = remember(order, dayLogs, query, category, filter, date, state.pauses, active, byId) {
        order.mapNotNull(byId::get).filter { habit ->
            val eligible = Analytics.eligible(habit, date, state.pauses)
            val dueToday = Analytics.dueOn(habit, date, state.pauses)
            val completedToday = dayLogs[habit.id]?.status == LogStatus.COMPLETED
            (query.isBlank() || listOf(habit.name, habit.description.orEmpty(), habit.category).any { it.contains(query.trim(), true) }) &&
                (category == null || habit.category == category) && when (filter) { "Due" -> dueToday && !completedToday; "Completed" -> completedToday; "Rest / planned" -> !eligible; else -> true }
        }
    }
    // Per-habit scheduling facts resolved once per state emission, not per item recomposition:
    // the engine walks pauses, schedule history and (for quotas) the whole log list, which is
    // wasteful to repeat per visible item on every recomposition (drag, check-in, typing).
    // Facts read schedule/config fields only, which goalOn(date) never overrides, so deriving
    // them from the stored habits is behavior-identical to the previous per-item reads.
    val scheduleFacts = remember(habits, date, state.logs, state.pauses, state.today) {
        habits.associate { habit ->
            val mode = Scheduling.mode(habit, date)
            val eligible = Analytics.eligible(habit, date, state.pauses)
            val dueToday = Analytics.dueOn(habit, date, state.pauses)
            val quota = if (mode == ScheduleMode.WEEKLY_QUOTA)
                Scheduling.weeklyQuotaStatus(habit, state.logs, state.pauses, date, state.today) else null
            val next = if (mode == ScheduleMode.EVERY_N_DAYS && !eligible) Scheduling.nextScheduledOn(habit, date) else null
            habit.id to HabitScheduleFacts(eligible, dueToday, mode, quota, next)
        }
    }
    val due = remember(active, date, state.pauses) { active.filter { Analytics.dueOn(it, date, state.pauses) } }
    val completed = due.count { dayLogs[it.id]?.status == LogStatus.COMPLETED }
    // Derived collections cached in composable scope so the LazyColumn rebuilds stay cheap.
    val completedDates = remember(state.logs) { state.logs.filter { it.status == LogStatus.COMPLETED }.map { it.date }.toSet() }
    val categories = remember(active) { active.map { it.category }.distinct().sorted() }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()) }
    // Formatters hoisted out of the header and item builders: construction is per-recomposition otherwise.
    val displayDateFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    val monthYearFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()) }
    val nudgeDateFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()) }
    val nextDateFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()) }
    val weekStartFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val unmarkedYesterday = remember(state.habits, state.logs, state.pauses, state.today) {
        Analytics.unmarked(state.habits, state.logs, state.today.minusDays(1), state.pauses)
    }
    val showNudge = date == state.today && unmarkedYesterday.isNotEmpty() && nudgeDismissed != state.today.minusDays(1).toString()
    // Milestone hint: the locked achievement closest to completion, derived above the LazyColumn
    // (state reads must stay in composable scope, never inside the builder lambda).
    val achievementList by vm.achievements.collectAsStateWithLifecycle()
    val nextMilestone = achievementList.filterNot { it.achieved }
        .minByOrNull { progress -> (progress.definition.target - progress.current).coerceAtLeast(0) }
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
                // Almanac: oversized Newsreader date with a vertical-rule day/month seal lockup.
                // Prev/next chevrons flank the date; the date text itself opens the calendar picker.
                // IntrinsicSize lets the rule grow with the wrapped date at large font scales.
                Row(Modifier.height(IntrinsicSize.Min).padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = date > minimum, onClick = { selected = date.minusDays(1).toString() }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day")
                    }
                    Text(
                        date.format(displayDateFormat),
                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.weight(1f).clickable(onClickLabel = "Open calendar", role = Role.Button) { showDate = true }
                    )
                    IconButton(enabled = date < state.today, onClick = { selected = date.plusDays(1).toString() }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day")
                    }
                    Box(Modifier.padding(start = 12.dp).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.End) {
                        Text(date.dayOfMonth.toString().padStart(2, '0'), style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(date.format(monthYearFormat).uppercase(), style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                // Flight Recorder: seven-day recording strip over the measurement rule.
                RecordingStrip(selected = date, today = state.today,
                    completedDates = completedDates,
                    onSelect = { selected = it.toString(); focusUnmarked = false })
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                if (date != state.today) TextButton(onClick = { selected = null; focusUnmarked = false }) { Text("Back to today") }
                // Almanac register summary: sunken parchment well, mono metric, 4dp sage progress line.
                // An all-quota or all-paused day has no daily-due habits: no register at all
                // rather than a misleading empty "0 of 0".
                if (!showFreshStart && due.isNotEmpty()) Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, almanacColors().rule), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$completed of ${due.size} scheduled habits completed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$completed/${due.size}", style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
                        LinearProgressIndicator(
                            progress = { if (due.isEmpty()) 0f else completed.toFloat() / due.size },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
                if (showNudge) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = .35f)), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (unmarkedYesterday.size == 1) "One habit was" else "${unmarkedYesterday.size} habits were"} left unmarked on ${state.today.minusDays(1).format(nudgeDateFormat)}", style = MaterialTheme.typography.titleSmall)
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
                        colors = almanacFieldColors(),
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(category == null, { category = null }, label = { Text("All categories") })
                        categories.forEach { name -> FilterChip(category == name, { category = name }, label = { Text(name) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "Due", "Completed", "Rest / planned").forEach { choice -> FilterChip(filter == choice, { filter = choice }, label = { Text(choice) }) }
                    }
                }
            }
        }
        if (showFreshStart) {
            item(key = "fresh-start") { FreshStartCard(onAdd, onRestoreBackup) }
            item(key = "fresh-start-reminders") {
                SettingsGroup {
                    SettingsAction("Schedules & Reminders", "Set up daily and habit reminders", Icons.Outlined.Tune, onClick = onSchedules)
                }
            }
        } else if (active.isEmpty()) {
            item(key = "empty-habits") { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌱", style = MaterialTheme.typography.displayLarge, modifier = Modifier.clearAndSetSemantics {})
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
            items(habits, key = { it.id }) { savedHabit ->
                val habit = savedHabit.goalOn(date)
                val log = dayLogs[habit.id]
                // Facts come from the hoisted per-habit map; every id iterated here is present
                // because the map is keyed on exactly the habits list being items'd.
                val facts = scheduleFacts.getValue(habit.id)
                val scheduleNote = when {
                    facts.mode == ScheduleMode.EVERY_N_DAYS && !facts.eligible ->
                        facts.next?.let { next ->
                            "Next scheduled ${next.format(nextDateFormat)}"
                        }
                    facts.quota != null -> {
                        val quota = facts.quota
                        // Past weeks are labeled by their Monday, matching the analytics week bars.
                        val weekLabel = if (Analytics.weekStart(date) == Analytics.weekStart(state.today)) "This week"
                            else "Week of ${Analytics.weekStart(date).format(weekStartFormat)}"
                        when (quota.state) {
                            Scheduling.WeeklyQuotaState.ACHIEVED -> "$weekLabel: ${quota.completedDays} of ${quota.requiredDays} · Achieved"
                            Scheduling.WeeklyQuotaState.ONGOING ->
                                "$weekLabel: ${quota.completedDays} of ${quota.requiredDays} · ${quota.requiredDays - quota.completedDays} session${if (quota.requiredDays - quota.completedDays == 1) "" else "s"} to go"
                            Scheduling.WeeklyQuotaState.MISSED ->
                                if (quota.eligibleDays < quota.requiredDays) "$weekLabel: ${quota.completedDays} of ${quota.requiredDays} · Started mid-week"
                                else "$weekLabel: ${quota.completedDays} of ${quota.requiredDays} · Missed"
                            Scheduling.WeeklyQuotaState.REST -> "$weekLabel: rest"
                        }
                    }
                    else -> null
                }
                ReorderableItem(reorder, key = habit.id) { _ ->
                    SpineItem(nodeColor = when (log?.status) {
                        LogStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        LogStatus.SKIPPED -> MaterialTheme.colorScheme.tertiary
                        LogStatus.FAILED -> almanacColors().terracotta
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }) {
                    HabitCard(habit = habit,
                        dayLabel = Analytics.dayNumber(habit, date)?.let { "Day $it${if (!facts.eligible) " · Rest day" else ""}" } ?: "Starts ${habit.startDate.format(dayFormat)}",
                        scheduleNote = scheduleNote,
                        status = log?.status,
                        stackLabel = habit.afterHabitId?.let { id -> state.habits.firstOrNull { it.id == id }?.name },
                        onOpen = { onOpen(habit.id) },
                        onCheckIn = {
                            if (habit.targetCount > 1 && log?.status != LogStatus.COMPLETED) vm.incrementProgress(habit.id, date)
                            else vm.quickCheckIn(habit.id, date)
                        },
                        onLongCheckIn = { editingLogId = habit.id },
                        checkInEnabled = facts.eligible && !busy,
                        progress = log?.progress ?: 0,
                        // The highlight mirrors the unmarked banner, which lists daily-due habits only
                        // (Analytics.unmarked): quota habits are never overdue, so they never highlight.
                        nudged = focusUnmarked && facts.dueToday && log == null,
                        quickActions = {
                            Box {
                                IconButton(onClick = { quickMenuId = habit.id }) { Icon(Icons.Outlined.MoreVert, "More actions for ${habit.name}") }
                                DropdownMenu(quickMenuId == habit.id, onDismissRequest = { quickMenuId = null }) {
                                    if (habit.targetCount > 1 && facts.eligible && log?.status != LogStatus.COMPLETED) {
                                        DropdownMenuItem(text = { Text("+1 " + habit.targetUnit) }, enabled = !busy, onClick = { vm.incrementProgress(habit.id, date); quickMenuId = null })
                                        DropdownMenuItem(text = { Text("+25% of goal") }, enabled = !busy, onClick = { vm.incrementProgress(habit.id, date, 25); quickMenuId = null })
                                        DropdownMenuItem(text = { Text("+50% of goal") }, enabled = !busy, onClick = { vm.incrementProgress(habit.id, date, 50); quickMenuId = null })
                                        DropdownMenuItem(text = { Text("Complete goal") }, enabled = !busy, onClick = { vm.quickCheckIn(habit.id, date); quickMenuId = null })
                                        HorizontalDivider()
                                    }
                                    // A quota habit is never due on a fixed day, so a visible
                                    // skip/missed affordance would fabricate a daily overdue.
                                    if (facts.mode != ScheduleMode.WEEKLY_QUOTA) {
                                        DropdownMenuItem(text = { Text("Skip this day") }, enabled = facts.eligible && log?.status != LogStatus.SKIPPED, onClick = {
                                            vm.setLog(habit.id, date, LogStatus.SKIPPED, log?.note); quickMenuId = null
                                        })
                                        DropdownMenuItem(text = { Text("Mark as missed") }, enabled = facts.eligible && log?.status != LogStatus.FAILED, onClick = {
                                            vm.setLog(habit.id, date, LogStatus.FAILED, log?.note); quickMenuId = null
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Edit check-in…") }, enabled = facts.eligible, onClick = { editingLogId = habit.id; quickMenuId = null })
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
        // Daily reflection: mood and/or journal for the selected day — today or any past date.
        item(key = "reflection") {
            ReflectionEditorCard(
                date = date,
                today = state.today,
                reflection = state.reflections.firstOrNull { it.date == date },
                onSave = { mood, journal -> vm.setReflection(date, mood, journal) },
                onRemove = { vm.clearReflection(date) },
                enabled = !busy
            )
        }
        // Small next-milestone hint: never displacing the habit cards or the reflection card above
        // it, and hidden during the fresh-start onboarding (nothing to measure yet).
        if (!showFreshStart) {
            nextMilestone?.let { milestone -> item(key = "next-milestone") { NextMilestoneCard(progress = milestone, onClick = onAchievements) } }
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

@Composable
private fun FreshStartCard(onAdd: () -> Unit, onRestore: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SettingsGroup {
        Column(
            Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHigh) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(32.dp))
                }
            }
            Text("You’re starting fresh", style = MaterialTheme.typography.headlineMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("There are no habits or activity logs yet. Add a habit or restore a backup.",
                color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val actionSize = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            Button(onClick = onAdd, modifier = actionSize) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add a habit")
            }
            FilledTonalButton(onClick = onRestore, modifier = actionSize) {
                Icon(Icons.Outlined.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text("Restore a backup")
            }
        }
    }
}

/** Scheduling facts for one habit on the viewed date, resolved once per state emission in
 *  composable scope (see scheduleFacts) so habit items never re-run the engine per recomposition. */
private data class HabitScheduleFacts(
    val eligible: Boolean,
    val dueToday: Boolean,
    val mode: ScheduleMode,
    val quota: Scheduling.WeeklyQuotaStatus?,
    val next: LocalDate?
)
