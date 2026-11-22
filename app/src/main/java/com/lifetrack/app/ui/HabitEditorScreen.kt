package com.lifetrack.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.ScheduleRevision
import com.lifetrack.app.data.StreakPolicy
import com.lifetrack.app.domain.Scheduling
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorScreen(id: Long, state: LifeTrackState, vm: HabitViewModel,
                      alarmVm: HabitAlarmViewModel? = null, onBack: () -> Unit) {
    val original = state.habits.firstOrNull { it.id == id }
    if (id != 0L && original == null) { Column(Modifier.padding(24.dp)) { Text("This habit no longer exists."); TextButton(onClick = onBack) { Text("Back") } }; return }
    var name by rememberSaveable(id) { mutableStateOf(original?.name ?: "") }
    var description by rememberSaveable(id) { mutableStateOf(original?.description ?: "") }
    var icon by rememberSaveable(id) { mutableStateOf(original?.icon ?: "🌱") }
    var color by rememberSaveable(id) { mutableStateOf(original?.colorHex ?: "#43866B") }
    var category by rememberSaveable(id) { mutableStateOf(original?.category ?: "Personal") }
    var start by rememberSaveable(id) { mutableStateOf((original?.startDate ?: state.today).toString()) }
    var afterId by rememberSaveable(id) { mutableStateOf(original?.afterHabitId) }
    var days by rememberSaveable(id) { mutableStateOf((original?.scheduledDays ?: (1..7).toList()).joinToString(",")) }
    var target by rememberSaveable(id) { mutableStateOf((original?.targetCount ?: 1).toString()) }
    var unit by rememberSaveable(id) { mutableStateOf(original?.targetUnit ?: "times") }
    var reminder by rememberSaveable(id) { mutableStateOf(original?.reminderMinutes != null) }
    var hour by rememberSaveable(id) { mutableStateOf(((original?.reminderMinutes ?: 540) / 60).toString().padStart(2, '0')) }
    var minute by rememberSaveable(id) { mutableStateOf(((original?.reminderMinutes ?: 540) % 60).toString().padStart(2, '0')) }
    var streakPolicy by rememberSaveable(id) { mutableStateOf(original?.streakPolicy ?: StreakPolicy.STRICT.name) }
    var partialCredit by rememberSaveable(id) { mutableStateOf((original?.partialCreditPercent ?: 75).toString()) }
    var scheduleMode by rememberSaveable(id) { mutableStateOf(original?.scheduleMode ?: ScheduleMode.WEEKDAYS.name) }
    var interval by rememberSaveable(id) { mutableStateOf((original?.intervalDays ?: 7).toString()) }
    var weekly by rememberSaveable(id) { mutableStateOf((original?.weeklyTarget ?: 3).toString()) }
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var stackMenu by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    var permissionNote by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) permissionNote = "The habit will be saved. Enable notifications in Android settings to receive its reminder."
    }
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    // A save failure leaves the editor open; a fresh entry starts without a stale error.
    LaunchedEffect(id) { vm.clearOperationError() }
    val hasCheckInToday = state.logs.any { it.habitId == id && it.date == state.today }
    val selectedDays = days.split(',').mapNotNull(String::toIntOrNull).sorted()
    val targetNumber = target.toIntOrNull()
    val partialCreditNumber = partialCredit.toIntOrNull()
    val intervalNumber = interval.toIntOrNull()
    val weeklyNumber = weekly.toIntOrNull()
    // Unknown stored values render as WEEKDAYS, matching how the scheduling engine canonicalizes them.
    val selectedMode = ScheduleMode.fromStored(scheduleMode)
    val intervalValid = selectedMode != ScheduleMode.EVERY_N_DAYS ||
        (intervalNumber != null && intervalNumber in Scheduling.MIN_INTERVAL_DAYS..Scheduling.MAX_INTERVAL_DAYS)
    val weeklyValid = selectedMode != ScheduleMode.WEEKLY_QUOTA || (weeklyNumber != null && weeklyNumber in 1..7)
    val weekdaysValid = selectedMode != ScheduleMode.WEEKDAYS || selectedDays.isNotEmpty()
    // Unknown stored values render as STRICT, matching how the streak engine canonicalizes them.
    val selectedPolicy = StreakPolicy.fromStored(streakPolicy)
    val reminderNumber = if (reminder && hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59) hour.toInt() * 60 + minute.toInt() else null
    val thresholdValid = streakPolicy != StreakPolicy.AUTOMATIC.name ||
        (partialCreditNumber != null && partialCreditNumber in 1..100)
    // Mirrors repository.saveHabit: a flexible revision is seeded when a new habit starts outside
    // WEEKDAYS or when the mode-relevant parameter changes. A NEW flexible habit seeds at its own
    // start date (a backdated start must not invent daily dues on the placeholder grid); an
    // existing habit's parameter change re-anchors the rhythm today. The preview covers only the
    // two flexible modes.
    val revisionSeeded = (original == null && selectedMode != ScheduleMode.WEEKDAYS) ||
        (original != null && (selectedMode == ScheduleMode.EVERY_N_DAYS && intervalNumber != original.intervalDays ||
            selectedMode == ScheduleMode.WEEKLY_QUOTA && weeklyNumber != original.weeklyTarget))
    val previewHabit = if (selectedMode != ScheduleMode.WEEKDAYS && intervalValid && weeklyValid) {
        val startDate = LocalDate.parse(start)
        val seededRevision = if (revisionSeeded) listOf(
            ScheduleRevision(
                if (original == null) startDate else state.today, (1..7).toList(), mode = selectedMode.name,
                intervalDays = if (selectedMode == ScheduleMode.EVERY_N_DAYS) intervalNumber else null,
                weeklyTarget = if (selectedMode == ScheduleMode.WEEKLY_QUOTA) weeklyNumber else null
            )
        ) else emptyList()
        val previewHistory = original?.scheduleHistory.orEmpty() + seededRevision
        (original ?: Habit(name = name.ifBlank { "·" })).copy(
            scheduleMode = selectedMode.name,
            intervalDays = if (selectedMode == ScheduleMode.EVERY_N_DAYS) intervalNumber else null,
            weeklyTarget = if (selectedMode == ScheduleMode.WEEKLY_QUOTA) weeklyNumber else null,
            scheduledDays = selectedDays, startDate = startDate,
            scheduleHistory = previewHistory, isArchived = false)
    } else null
    // Canonical engine calls only — the anchor resolves from history (a seeded revision yields its
    // effectiveFrom: the start date for new habits, today for parameter changes; unchanged habits
    // resolve the stored effective anchor); the UI never does its own date arithmetic.
    val scheduleDateFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    val todayFormatted = state.today.format(scheduleDateFormat)
    val previewAnchor = previewHabit?.let { Scheduling.intervalAnchor(it, state.today) ?: state.today }
    val previewScheduledToday = previewHabit?.let { Scheduling.isScheduledOn(it, state.today) } == true
    val previewNextDate = previewHabit?.let { Scheduling.nextScheduledOn(it, state.today) }
    val valid = name.isNotBlank() && name.length <= 120 && category.isNotBlank() && category.length <= 40 &&
        icon.isNotBlank() && Regex("#[0-9a-fA-F]{6}").matches(color) && weekdaysValid && intervalValid && weeklyValid &&
        targetNumber != null && targetNumber in 1..100_000 && unit.isNotBlank() && unit.length <= 24 && (!reminder || reminderNumber != null) &&
        thresholdValid
    val dirty = name != (original?.name ?: "") || description != (original?.description ?: "") || icon != (original?.icon ?: "🌱") ||
        color != (original?.colorHex ?: "#43866B") || category != (original?.category ?: "Personal") ||
        start != (original?.startDate ?: state.today).toString() || afterId != original?.afterHabitId ||
        selectedDays != (original?.scheduledDays ?: (1..7).toList()) || target != (original?.targetCount ?: 1).toString() ||
        unit != (original?.targetUnit ?: "times") || reminderNumber != original?.reminderMinutes ||
        streakPolicy != (original?.streakPolicy ?: StreakPolicy.STRICT.name) || partialCredit != (original?.partialCreditPercent ?: 75).toString() ||
        scheduleMode != (original?.scheduleMode ?: ScheduleMode.WEEKDAYS.name) ||
        interval != (original?.intervalDays ?: 7).toString() || weekly != (original?.weeklyTarget ?: 3).toString()
    val requestBack: () -> Unit = { if (!busy) { if (dirty) discard = true else onBack() } }
    BackHandler { requestBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (id == 0L) "A new beginning" else "Edit habit") }, navigationIcon = {
        IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Make room for what matters.", style = MaterialTheme.typography.headlineSmall)
            if (id == 0L) Box {
                OutlinedButton(onClick = { templateMenu = true }) { Text("Start from a gentle suggestion") }
                DropdownMenu(templateMenu, { templateMenu = false }) { habitTemplates.forEach { template ->
                    DropdownMenuItem(text = { Text("${template.icon} ${template.name}") }, onClick = {
                        name = template.name; description = template.description; icon = template.icon; category = template.category
                        target = template.target.toString(); unit = template.unit; templateMenu = false
                    })
                } }
            }
            OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text("Habit name") }, singleLine = true, colors = almanacFieldColors(),
                supportingText = { Text("${name.length}/120") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { if (it.length <= 2000) description = it }, label = { Text("Description · optional") }, minLines = 2, colors = almanacFieldColors(), modifier = Modifier.fillMaxWidth())
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(icon, { if (it.length <= 32) icon = it }, label = { Text("Emoji / icon") }, singleLine = true, colors = almanacFieldColors(), modifier = Modifier.weight(1f))
                OutlinedTextField(category, { if (it.length <= 40) category = it }, label = { Text("Category") }, singleLine = true, colors = almanacFieldColors(), modifier = Modifier.weight(2f))
            }
            OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DateRange, null); Spacer(Modifier.width(12.dp)); Text("Start date · $start")
            }
            Text("Day 1 stays anchored to this date. Planned rest days do not break your chain.", style = MaterialTheme.typography.bodySmall)
            Text("Schedule", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                Triple(ScheduleMode.WEEKDAYS, "Selected weekdays", "Specific days each week. Your current routine, unchanged."),
                Triple(ScheduleMode.EVERY_N_DAYS, "Every N days", "A repeating rhythm from an anchor date — every 3 days, for example — with no weekday pattern."),
                Triple(ScheduleMode.WEEKLY_QUOTA, "X times per week", "Any day counts toward a weekly number of sessions, Monday through Sunday.")
            ).forEach { (mode, title, supporting) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selectedMode == mode, role = Role.RadioButton) { scheduleMode = mode.name },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == mode, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selectedMode == ScheduleMode.WEEKDAYS) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { day ->
                        val full = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())
                        FilterChip(selected = day in selectedDays, onClick = { days = selectedDays.toMutableSet().apply { if (!remove(day)) add(day) }.sorted().joinToString(",") },
                            label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = "$full, ${if (day in selectedDays) "scheduled" else "rest day"}" })
                    }
                }
                if (selectedDays.isEmpty()) Text("Choose at least one scheduled day.", color = MaterialTheme.colorScheme.error)
                Text("Weekday changes apply from today; past schedules remain in your history.", style = MaterialTheme.typography.bodySmall)
            }
            if (selectedMode == ScheduleMode.EVERY_N_DAYS) {
                OutlinedTextField(interval, { if (it.length <= 3 && it.all(Char::isDigit)) interval = it },
                    label = { Text("Interval · days (2–365)") }, colors = almanacFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = !intervalValid,
                    supportingText = {
                        // Live region only on the error; the descriptive sentence renders as plain text.
                        if (intervalValid) Text("${previewAnchor?.format(scheduleDateFormat) ?: todayFormatted}, then every $intervalNumber days.")
                        else Text("Enter an interval between 2 and 365 days.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    modifier = Modifier.fillMaxWidth())
                if (previewHabit != null) {
                    val anchorText = previewAnchor?.format(scheduleDateFormat) ?: todayFormatted
                    Text(
                        // Only an existing habit's parameter change restarts the rhythm today; a new
                        // habit simply runs from its start date, so both states share one phrasing.
                        if (revisionSeeded && original != null) "Saving restarts the rhythm from today ($todayFormatted). Your sessions keep a steady gap, whatever the month or year."
                        else "The rhythm runs from $anchorText — sessions keep a steady gap, whatever the month or year.",
                        style = MaterialTheme.typography.bodySmall)
                    if (previewScheduledToday) {
                        Text("Today is on your rhythm.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        Text(
                            previewNextDate?.let { next -> "Today is a rest day on this rhythm — next: ${next.format(scheduleDateFormat)}" }
                                ?: "Today is a rest day on this rhythm.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // On-rhythm days get the labeled next date; on a rest day the date is already
                    // embedded in the status line above, so a second copy would read twice.
                    if (previewScheduledToday) previewNextDate?.let { next ->
                        Text("Next scheduled date · ${next.format(scheduleDateFormat)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (selectedMode == ScheduleMode.WEEKLY_QUOTA) {
                OutlinedTextField(weekly, { if (it.length <= 1 && it.all(Char::isDigit)) weekly = it },
                    label = { Text("Sessions per week (1–7)") }, colors = almanacFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = !weeklyValid,
                    supportingText = {
                        if (weeklyValid) Text("Do it on any $weekly day${if (weekly == "1") "" else "s"} each week, in any combination.")
                        else Text("Choose between 1 and 7 days per week.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    modifier = Modifier.fillMaxWidth())
                Text("Weeks run Monday through Sunday and reset every Monday. There are no fixed due dates — complete your sessions on whichever days suit you. Only fully completed days count toward the weekly total.", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("A measurable intention", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(target, { if (it.length <= 6 && it.all(Char::isDigit)) target = it }, label = { Text("Daily target") },
                    colors = almanacFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = targetNumber == null || targetNumber !in 1..100_000, modifier = Modifier.weight(1f))
                OutlinedTextField(unit, { if (it.length <= 24) unit = it }, label = { Text("Unit") }, placeholder = { Text("pages, glasses…") }, singleLine = true, colors = almanacFieldColors(), modifier = Modifier.weight(1.5f))
            }
            Text("Use 1 time for a simple check-in, or track a count toward your goal.", style = MaterialTheme.typography.bodySmall)
            if (original != null) Text(
                if (hasCheckInToday) "Goal changes start tomorrow. Existing check-ins keep their original goal."
                else "Goal changes start today. Earlier check-ins keep their original goal.",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Smart Streaks", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedMode == ScheduleMode.WEEKLY_QUOTA) Text("Habits with a weekly target don't use day streaks — consistency is measured per week.", style = MaterialTheme.typography.bodySmall)
            listOf(
                Triple(StreakPolicy.STRICT, "Strict", "Only fully completed days keep the chain alive. Today's behavior, unchanged."),
                Triple(StreakPolicy.AUTOMATIC, "Automatic", "A past partial day at or above the threshold keeps the chain alive without counting as completed."),
                Triple(StreakPolicy.MANUAL, "Manual", "You decide per day whether a partial day protects the chain.")
            ).forEach { (policy, title, supporting) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selectedPolicy == policy, role = Role.RadioButton) { streakPolicy = policy.name },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedPolicy == policy, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (streakPolicy == StreakPolicy.AUTOMATIC.name) {
                OutlinedTextField(partialCredit, { if (it.length <= 3 && it.all(Char::isDigit)) partialCredit = it },
                    label = { Text("Partial-credit threshold · %") }, colors = almanacFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = !thresholdValid,
                    supportingText = {
                        Text(
                            if (thresholdValid) "Suggested: 75 — six of eight glasses keeps the day."
                            else "Enter a threshold between 1 and 100.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    modifier = Modifier.fillMaxWidth())
            }
            Text(
                when (selectedPolicy) {
                    StreakPolicy.STRICT -> "Your earlier per-day decisions still apply. Everything else needs a fully completed day."
                    StreakPolicy.AUTOMATIC -> "Changes to the policy or threshold apply to your history — past partial days are re-evaluated."
                    StreakPolicy.MANUAL -> "Decisions are per day and persist even if you change the policy later."
                },
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            val reminderApplicable = selectedMode != ScheduleMode.WEEKLY_QUOTA
            // Ringing alarms (Prompt 6, opt-in): independent of the legacy reminder. Only shown
            // for saved habits — a draft has no id to attach alarm definitions to yet.
            if (id > 0 && alarmVm != null) {
                RingingAlarmsSection(habitId = id, vm = alarmVm)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = reminder && reminderApplicable, enabled = reminderApplicable, role = Role.Switch, onValueChange = { enabled ->
                        if (reminderApplicable) {
                            reminder = enabled
                            if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Habit reminder", Modifier.weight(1f), style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(reminder && reminderApplicable, onCheckedChange = null, enabled = reminderApplicable,
                    modifier = Modifier.semantics { contentDescription = "Habit reminder"; stateDescription = when {
                        !reminderApplicable -> "Not available for weekly-target habits"
                        reminder -> "Enabled"
                        else -> "Disabled"
                    }; role = Role.Switch })
            }
            if (selectedMode == ScheduleMode.WEEKLY_QUOTA) {
                Text("This habit doesn't receive daily reminders — you choose your own days.", style = MaterialTheme.typography.bodySmall)
            } else if (reminder) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hour, { if (it.length <= 2 && it.all(Char::isDigit)) hour = it }, label = { Text("Hour · 00–23") }, colors = almanacFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(minute, { if (it.length <= 2 && it.all(Char::isDigit)) minute = it }, label = { Text("Minute · 00–59") }, colors = almanacFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("A local nudge at or after this time on scheduled days. Android may delay background work; this is not an exact alarm.", style = MaterialTheme.typography.bodySmall)
                permissionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Color", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Amber" to "#D97706", "Rust" to "#C2410C", "Emerald" to "#059669", "Blue" to "#2563EB",
                    "Indigo" to "#4F46E5", "Violet" to "#9333EA", "Slate" to "#475569", "Bronze" to "#B45309").forEach { (name, hex) ->
                    Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Surface(color = Color(android.graphics.Color.parseColor(hex)), shape = CircleShape,
                            border = if (hex.equals(color, true)) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(44.dp)
                                .semantics { stateDescription = if (hex.equals(color, true)) "Selected: $name" else "Not selected: $name" }
                                .clickable(onClickLabel = "Use $name color", role = Role.Button) { color = hex }) {}
                    }
                }
            }
            OutlinedTextField(color, { if (it.length <= 7) color = it }, label = { Text("Hex color") }, isError = !Regex("#[0-9a-fA-F]{6}").matches(color), singleLine = true, colors = almanacFieldColors(), modifier = Modifier.fillMaxWidth())
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Habit stacking", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { stackMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(afterId?.let { parent -> state.habits.firstOrNull { it.id == parent }?.let { "After ${it.name}, I will…" } } ?: "Link to an existing habit · optional")
                }
                DropdownMenu(stackMenu, { stackMenu = false }) {
                    DropdownMenuItem(text = { Text("No linked habit") }, onClick = { afterId = null; stackMenu = false })
                    state.habits.filter { it.id != id && (!it.isArchived || it.id == original?.afterHabitId) }.forEach { habit ->
                        DropdownMenuItem(text = { Text("${habit.icon} After ${habit.name}") }, onClick = { afterId = habit.id; stackMenu = false })
                    }
                }
            }
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.fillMaxWidth()) }
            Button(enabled = valid && !busy, onClick = {
                vm.saveHabit((original ?: Habit(name = name)).copy(
                    name = name.trim(), description = description.trim().ifBlank { null }, icon = icon.trim(), colorHex = color.uppercase(),
                    category = category.trim(), startDate = LocalDate.parse(start), afterHabitId = afterId,
                    scheduledDays = selectedDays, targetCount = targetNumber!!, targetUnit = unit.trim(),
                    // A quota habit's stored reminder never fires, so saving null keeps the model honest.
                    reminderMinutes = if (selectedMode == ScheduleMode.WEEKLY_QUOTA) null else reminderNumber,
                    streakPolicy = streakPolicy, scheduleMode = selectedMode.name,
                    intervalDays = if (selectedMode == ScheduleMode.EVERY_N_DAYS) intervalNumber else null,
                    weeklyTarget = if (selectedMode == ScheduleMode.WEEKLY_QUOTA) weeklyNumber else null,
                    // An out-of-range hidden draft must never block saving or silently replace a stored value.
                    partialCreditPercent = partialCredit.toIntOrNull()?.takeIf { it in 1..100 }
                        ?: (original?.partialCreditPercent ?: 75)
                )) { onBack() }
            }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (busy) "Saving…" else "Save habit") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (dateDialog) DateSelectionDialog(LocalDate.parse(start), onDismiss = { dateDialog = false }, onSelect = { start = it.toString(); dateDialog = false })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Keep your changes?") },
        text = { Text("Your edits are still here. Save the habit to keep them, or discard them to leave.") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}
