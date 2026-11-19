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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.*
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.domain.Scheduling
import com.lifetrack.app.ui.components.AlmanacStat
import com.lifetrack.app.ui.components.CompletionTrend
import com.lifetrack.app.ui.components.HeatmapWidget
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val scheduleDateFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

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
    val ownLogs = remember(id, state.logs) { state.logs.filter { it.habitId == id } }
    val log = ownLogs.firstOrNull { it.date == date }
    val goal = habit.goalOn(date)
    val eligible = !habit.isArchived && Analytics.eligible(habit, date, state.pauses) && date <= state.today
    // Mode and quota status resolve once in composable scope; the schedule block, stat row and
    // weekly rhythm all read the same engine state so their figures can never disagree.
    val mode = Scheduling.mode(habit, state.today)
    val quota = if (mode == ScheduleMode.WEEKLY_QUOTA) remember(habit, ownLogs, state.pauses, state.today) { Scheduling.weeklyQuotaStatus(habit, ownLogs, state.pauses, state.today, state.today) } else null
    // Streak engines walk the whole log list; remembered so the stat row recomputes only on
    // real input changes, not on every unrelated recomposition of the screen. Quota habits
    // have no day chain, so the streak walk is skipped entirely for them.
    val streaks = if (mode == ScheduleMode.WEEKLY_QUOTA) null else remember(habit, ownLogs, state.today, state.pauses, state.streakProtections) {
        Analytics.currentStreak(habit, ownLogs, state.today, state.pauses, state.streakProtections) to
            Analytics.bestStreak(habit, ownLogs, state.today, state.pauses, state.streakProtections)
    }
    val spent = state.freezes.any { it.month == YearMonth.from(date).toString() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Your journey") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }, actions = {
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit habit") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(habit.icon, style = MaterialTheme.typography.displayMedium)
            Text(habit.name, style = MaterialTheme.typography.headlineLarge)
            Text("${habit.category} · Started ${habit.startDate.format(scheduleDateFormat)}", style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurfaceVariant)
            habit.description?.let { Text(it) }
            habit.afterHabitId?.let { parent -> state.habits.firstOrNull { it.id == parent }?.let { Text("After I ${it.name}, I will ${habit.name}.", color = MaterialTheme.colorScheme.primary) } }
            if (habit.isArchived) Text("Archived · History preserved", color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Schedule", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (mode) {
                    ScheduleMode.WEEKDAYS -> Text(
                        "Scheduled on " + habit.scheduledDays.sorted().joinToString(", ") { day ->
                            DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                        },
                        style = MaterialTheme.typography.bodyMedium)
                    ScheduleMode.EVERY_N_DAYS -> {
                        val n = Scheduling.intervalDays(habit, state.today)
                        val anchor = Scheduling.intervalAnchor(habit, state.today)
                        val next = Scheduling.nextScheduledOn(habit, state.today)
                        // Malformed interval data renders nothing rather than blocking the page.
                        if (n != null && anchor != null) {
                            Text("Every $n days", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                next?.let { "From ${anchor.format(scheduleDateFormat)} · next scheduled ${it.format(scheduleDateFormat)}" }
                                    ?: "From ${anchor.format(scheduleDateFormat)}",
                                style = AlmanacType.monoData, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (Scheduling.isScheduledOn(habit, state.today)) "Today is on the rhythm" else "Today is not on the rhythm",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    ScheduleMode.WEEKLY_QUOTA -> if (quota != null) {
                        Text("${quota.requiredDays} sessions per week · Monday through Sunday, any day counts", style = MaterialTheme.typography.bodyMedium)
                        Text(when (quota.state) {
                            Scheduling.WeeklyQuotaState.ACHIEVED -> "This week: ${quota.completedDays} of ${quota.requiredDays} · Achieved"
                            Scheduling.WeeklyQuotaState.ONGOING ->
                                "This week: ${quota.completedDays} of ${quota.requiredDays} · ${quota.requiredDays - quota.completedDays} session${if (quota.requiredDays - quota.completedDays == 1) "" else "s"} to go"
                            Scheduling.WeeklyQuotaState.MISSED ->
                                if (quota.eligibleDays < quota.requiredDays) "This week: ${quota.completedDays} of ${quota.requiredDays} · Started mid-week"
                                else "This week: ${quota.completedDays} of ${quota.requiredDays} · Missed"
                            Scheduling.WeeklyQuotaState.REST -> "This week: rest"
                        }, style = AlmanacType.monoData)
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == ScheduleMode.WEEKLY_QUOTA && quota != null) {
                    // Period-based habits are never presented as a failed daily streak.
                    AlmanacStat("${quota.completedDays} of ${quota.requiredDays}", "This week", Modifier.weight(1f))
                    AlmanacStat(ownLogs.count { it.status == LogStatus.COMPLETED }.toString(), "Completions", Modifier.weight(1f))
                    // Same fairness rule as the status lines: a week with too few eligible days
                    // to ever reach the target is "Started mid-week", never a failure.
                    AlmanacStat(when (quota.state) {
                        Scheduling.WeeklyQuotaState.ACHIEVED -> "Achieved"
                        Scheduling.WeeklyQuotaState.ONGOING -> "Ongoing"
                        Scheduling.WeeklyQuotaState.MISSED -> if (quota.eligibleDays < quota.requiredDays) "Started mid-week" else "Missed"
                        Scheduling.WeeklyQuotaState.REST -> "Rest"
                    }, "Week status", Modifier.weight(1f))
                } else {
                    // Non-quota by construction, so streaks is non-null in this branch.
                    AlmanacStat(streaks!!.first.toString(), "Current streak", Modifier.weight(1f))
                    AlmanacStat(ownLogs.count { it.status == LogStatus.COMPLETED }.toString(), "Completions", Modifier.weight(1f))
                    AlmanacStat(streaks!!.second.toString(), "Best streak", Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DateRange, null); Spacer(Modifier.width(8.dp)); Text("${date.format(scheduleDateFormat)} · ${Analytics.dayNumber(habit, date)?.let { "Day $it" } ?: "Not started"}")
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
                    // Matches the repository's own guard, which checks the mode at the freeze date.
                    if (eligible && Scheduling.mode(habit, date) != ScheduleMode.WEEKLY_QUOTA && date < state.today && (log == null || log.status in setOf(LogStatus.FAILED, LogStatus.IN_PROGRESS))) {
                        OutlinedButton(enabled = !spent && !busy, onClick = { freezeDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(if (spent) "${YearMonth.from(date)} freeze used" else "❄ Use monthly streak freeze") }
                    }
                    // Smart Streaks: per-day decisions for past partial days. Decisions persist across
                    // policy changes, so the block also appears under Strict whenever a decision exists.
                    val streakPolicy = StreakPolicy.fromStored(habit.streakPolicy)
                    val partialDay = eligible && date < state.today && log != null && log.status == LogStatus.IN_PROGRESS && log.progress > 0
                    val existingDecision = state.streakProtections.firstOrNull { it.habitId == habit.id && it.date == date }
                    if ((streakPolicy != StreakPolicy.STRICT || existingDecision != null) && partialDay) {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Smart Streaks", style = MaterialTheme.typography.titleSmall)
                        val autoBridges = com.lifetrack.app.domain.StreakPolicy.automaticBridges(habit, log) == true
                        if (streakPolicy == StreakPolicy.AUTOMATIC) {
                            Text("Threshold ${habit.partialCreditPercent}% — ${log?.progress ?: 0} of ${goal.targetCount} needed; this day " +
                                if (autoBridges) "keeps the chain alive automatically." else "breaks the chain automatically.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (streakPolicy == StreakPolicy.MANUAL && existingDecision == null) {
                            Text("Manual policy — you decide whether this partial day keeps the chain alive.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (existingDecision != null) {
                            Text(
                                if (existingDecision.protectChain) "You decided: this day keeps the chain alive."
                                else "You decided: this day breaks the chain.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            OutlinedButton(enabled = !busy, onClick = { vm.clearStreakProtection(id, date) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Remove my decision")
                            }
                        } else {
                            Text("A day kept by Smart Streaks stays partial — it never counts as fully completed.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (streakPolicy == StreakPolicy.AUTOMATIC) {
                                // Only the override that differs from the automatic outcome is offered.
                                if (autoBridges) {
                                    TextButton(enabled = !busy, onClick = { vm.setStreakProtection(id, date, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("Break the chain for this day", color = almanacColors().terracotta)
                                    }
                                } else {
                                    OutlinedButton(enabled = !busy, onClick = { vm.setStreakProtection(id, date, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("Protect this partial day")
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(enabled = !busy, onClick = { vm.setStreakProtection(id, date, true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Text("Protect chain")
                                    }
                                    TextButton(enabled = !busy, onClick = { vm.setStreakProtection(id, date, false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Text("Break chain", color = almanacColors().terracotta)
                                    }
                                }
                            }
                        }
                    }
                    if (habit.isArchived) Text("Restore this habit to change check-ins.", style = MaterialTheme.typography.bodySmall)
                    if (date < habit.startDate) Text("Check-ins begin on ${habit.startDate.format(scheduleDateFormat)}.", style = MaterialTheme.typography.bodySmall)
                    else if (!eligible && !habit.isArchived) Text(
                        if (mode == ScheduleMode.EVERY_N_DAYS)
                            Scheduling.nextScheduledOn(habit, state.today)?.let { "This is a rest day — next scheduled ${it.format(scheduleDateFormat)}." }
                                ?: "This is a rest day or a preserved pause in your timeline."
                        else "This is a rest day or a preserved pause in your timeline.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (mode == ScheduleMode.WEEKLY_QUOTA) WeeklyRhythm(habit, ownLogs, state.pauses, state.today)
            else CompletionTrend(habit, ownLogs, state.today, pauses = state.pauses)
            if (ownLogs.isNotEmpty()) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("Recent recordings", style = MaterialTheme.typography.titleMedium)
                HistorySpine(ownLogs)
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Your whole routine", style = MaterialTheme.typography.titleLarge)
            Text("Color shows the share of daily-scheduled habits completed each day.", style = MaterialTheme.typography.bodySmall)
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
        AlertDialog(onDismissRequest = { freezeDialog = false }, title = { Text("Protect ${date.format(scheduleDateFormat)}?") }, text = {
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

/** Week-based rhythm for quota habits: the day-based CompletionTrend chart would read as a
 *  misleading flat 0% because rate() excludes period-based habits. Last 6 ISO weeks, oldest first. */
@Composable
private fun WeeklyRhythm(habit: Habit, logs: List<HabitLog>, pauses: List<HabitPause>, today: LocalDate) {
    val weekFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    // Only weeks already governed by the weekly schedule render, matching TodayScreen's
    // per-viewed-date gating: a habit switched to quota today has weekday-era weeks that are
    // not failed weekly attempts. Each week runs a full quota walk over the log list, so the
    // six weeks are resolved once per input change, not on every recomposition.
    val weeks = remember(habit, logs, pauses, today) {
        (5 downTo 0).mapNotNull { i ->
            val week = Analytics.weekStart(today).minusWeeks(i.toLong())
            val weekEnd = week.plusDays(6)
            if (weekEnd.isBefore(habit.startDate)) return@mapNotNull null
            if (Scheduling.mode(habit, week.plusDays(3)) != ScheduleMode.WEEKLY_QUOTA) return@mapNotNull null
            Triple(week, weekEnd, Scheduling.weeklyQuotaStatus(habit, logs, pauses, week.plusDays(3), today))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Weekly rhythm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (weeks.isEmpty()) Text("Your weekly rhythm appears after your first week on this schedule.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        weeks.forEach { (week, weekEnd, st) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${week.format(weekFormat)} – ${weekEnd.format(weekFormat)}", style = MaterialTheme.typography.labelMedium)
                Text("${st.completedDays} of ${st.requiredDays} · " + when (st.state) {
                    Scheduling.WeeklyQuotaState.ACHIEVED -> "Achieved"
                    Scheduling.WeeklyQuotaState.ONGOING -> "Ongoing"
                    // A week that began partway through had fewer eligible days than the quota
                    // requires: it was never fully missable, so it is not labeled as missed.
                    Scheduling.WeeklyQuotaState.MISSED -> if (st.eligibleDays < st.requiredDays) "Started mid-week" else "Missed"
                    Scheduling.WeeklyQuotaState.REST -> "Rest"
                }, style = AlmanacType.monoData)
            }
        }
    }
}

@Composable
fun LogEditorDialog(habit: Habit, date: LocalDate, current: HabitLog?, vm: HabitViewModel, onDismiss: () -> Unit,
                    // Quota days have no chain: a new check-in must not default to a chain status the list hides.
                    initialStatus: LogStatus = current?.status
                        ?: if (Scheduling.mode(habit, date) == ScheduleMode.WEEKLY_QUOTA) LogStatus.COMPLETED else LogStatus.SKIPPED) {
    var statusName by rememberSaveable(habit.id, date) { mutableStateOf(initialStatus.name) }
    val status = LogStatus.valueOf(statusName)
    var note by rememberSaveable(habit.id, date) { mutableStateOf(current?.note ?: "") }
    val goal = habit.goalOn(date)
    // Quota days have no chain: skipped/missed are chain decisions a period-based week does not have.
    val quotaMode = Scheduling.mode(habit, date) == ScheduleMode.WEEKLY_QUOTA
    var progress by rememberSaveable(habit.id, date) { mutableStateOf((if (current?.status == LogStatus.COMPLETED) maxOf(goal.targetCount, current.progress) else current?.progress ?: 0).toString()) }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    // Clearing a day erases its note and Smart Streaks decision, so it asks first.
    var clearConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(habit.id, date) { vm.clearOperationError() }
    // A finite content viewport avoids intrinsic-height feedback between a platform dialog,
    // growing text fields and the scroll container, including with large accessibility fonts
    // and short viewports (85% of the window's container height). Read from LocalWindowInfo
    // (pixels, converted via density) per the Compose lint guidance for window-sized caps.
    val maxDialogHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp)) {
            Column(Modifier.heightIn(max = maxDialogHeight).padding(24.dp)) {
                Text("${habit.icon} ${habit.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), style = MaterialTheme.typography.labelLarge)
                    Text(if (current == null) "Tap a status to save it instantly." else "Choose a status, then save to replace the recorded one.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val statusOptions = if (quotaMode) listOf(LogStatus.COMPLETED, LogStatus.IN_PROGRESS) else LogStatus.entries
                    statusOptions.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = status == option, enabled = !busy,
                                role = androidx.compose.ui.semantics.Role.RadioButton, onClick = {
                                    statusName = option.name
                                    // One-tap save only creates a new check-in; replacing an existing
                                    // day's status goes through the Save button so the change is
                                    // deliberate. IN_PROGRESS keeps the Save button either way because
                                    // its progress field needs a deliberate value.
                                    if (current == null && option != LogStatus.IN_PROGRESS) {
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
                    if (quotaMode) Text("Weekly habits count full days toward the week — no per-day chain decisions.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (goal.targetCount > 1 || status == LogStatus.IN_PROGRESS) OutlinedTextField(progress,
                        { if (it.length <= 6 && it.all(Char::isDigit)) progress = it }, label = { Text("Progress · ${goal.targetCount} ${goal.targetUnit} goal") },
                        colors = almanacFieldColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
                    OutlinedTextField(note, { if (it.length <= 4000) note = it }, label = { Text("Note · optional") }, minLines = 2,
                        colors = almanacFieldColors(), modifier = Modifier.fillMaxWidth())
                    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (current != null) TextButton(enabled = !busy, onClick = { clearConfirm = true }) { Text("Clear check-in", color = MaterialTheme.colorScheme.error) }
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
    if (clearConfirm) AlertDialog(
        onDismissRequest = { if (!busy) clearConfirm = false },
        title = { Text("Clear this check-in?") },
        text = { Text("Its note and any Smart Streaks decision for this day are removed. This cannot be undone.") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { clearConfirm = false; vm.clearLog(habit.id, date, onSaved = onDismiss) }) {
                // "Clear it" keeps the confirm button distinct from the editor's trigger button,
                // so TalkBack never announces two identically-named destructive buttons at once.
                Text("Clear it", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { clearConfirm = false }) { Text("Keep it") } }
    )
}
