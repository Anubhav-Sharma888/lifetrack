package com.lifetrack.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.domain.Scheduling
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchedulesScreen(state: LifeTrackState, vm: HabitViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val dailyMinutes by vm.dailyReminderMinutes.collectAsState()
    val busy by vm.busy.collectAsState()
    val habits = state.habits.filterNot { it.isArchived }.sortedBy { it.sortOrder }
    var editingDaily by rememberSaveable { mutableStateOf(false) }
    var pendingMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var permissionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val dailyNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val minutes = pendingMinutes
        pendingMinutes = null
        if (granted && minutes != null) vm.setDailyReminder(minutes)
        if (!granted) permissionMessage = "Notifications are off. Enable them in Android settings, then set a reminder here."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Schedules & Reminders") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The TopAppBar carries the screen title; the body keeps only the explaining subline.
                    Text("Choose when your habits are due and when you get a notification.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "daily-reminder") {
                SettingsSection("Daily reminder") {
                    SettingsAction(
                        "Daily check-in reminder",
                        dailyMinutes?.let { "Daily at ${reminderTime(it)}" } ?: "Off · choose a time",
                        Icons.Outlined.Notifications, enabled = !busy, onClick = { editingDaily = true }
                    )
                    permissionMessage?.let {
                        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "habits-heading") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Habit schedules", Modifier.weight(1f), style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("${habits.count { it.reminderMinutes != null }} reminders on", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = AlmanacType.monoCaption)
                    }
                }
            }
            if (habits.isEmpty()) item(key = "no-habits") {
                SettingsGroup { Text("Add a habit from Today to set its schedule and reminder.", Modifier.padding(16.dp)) }
            }
            items(habits, key = { it.id }) { habit ->
                HabitScheduleCard(habit, state.logs, state.pauses, state.today, onEdit = { onEdit(habit.id) })
            }
            item(key = "notification-timing") {
                Text("Android may deliver reminders later than the time you select.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (editingDaily) {
        val current = dailyMinutes
        val time = rememberTimePickerState(initialHour = current?.div(60) ?: 20, initialMinute = current?.rem(60) ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editingDaily = false },
            title = { Text("Daily check-in reminder") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Get one notification a day if you still have habits to check in.")
                    TimePicker(time)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val minutes = time.hour * 60 + time.minute
                    editingDaily = false
                    permissionMessage = null
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingMinutes = minutes
                        dailyNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.setDailyReminder(minutes)
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (current != null) TextButton(enabled = !busy, onClick = {
                        vm.setDailyReminder(null)
                        editingDaily = false
                    }) { Text("Turn off") }
                    TextButton(onClick = { editingDaily = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun HabitScheduleCard(habit: Habit, logs: List<HabitLog>, pauses: List<HabitPause>, today: LocalDate, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locale = Locale.getDefault()
    val days = DayOfWeek.values()
    val scheduledDays = days.filter { it.value in habit.scheduledDays }.joinToString { it.getDisplayName(TextStyle.FULL, locale) }
    val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    val mode = Scheduling.mode(habit, today)
    val isQuota = mode == ScheduleMode.WEEKLY_QUOTA
    SettingsGroup {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = colors.surfaceContainerHigh) {
                    Box(Modifier.size(38.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { Text(habit.icon) }
                }
                Column {
                    Text(habit.name, style = MaterialTheme.typography.titleLarge)
                    Text(habit.category, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            when (mode) {
                ScheduleMode.WEEKDAYS -> Row(
                    Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Scheduled on $scheduledDays" },
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    days.forEach { day ->
                        val selected = day.value in habit.scheduledDays
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                            color = if (selected) colors.primary else colors.surfaceContainerHigh,
                            contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant) {
                            Box(Modifier.heightIn(min = 32.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(day.getDisplayName(TextStyle.NARROW, locale), style = AlmanacType.monoData)
                            }
                        }
                    }
                }
                ScheduleMode.EVERY_N_DAYS -> {
                    val n = Scheduling.intervalDays(habit, today)
                    val anchor = Scheduling.intervalAnchor(habit, today)
                    val next = Scheduling.nextScheduledOn(habit, today)
                    // Malformed interval data renders nothing rather than an empty gap.
                    if (n != null && anchor != null) {
                        Text("Every $n days", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            next?.let { "From ${anchor.format(dateFormat)} · next scheduled ${it.format(dateFormat)}" }
                                ?: "From ${anchor.format(dateFormat)}",
                            style = AlmanacType.monoData, color = colors.onSurfaceVariant)
                    }
                }
                ScheduleMode.WEEKLY_QUOTA -> {
                    // Full log walk per quota card — cached per card recomposition scope.
                    val quota = remember(habit, logs, pauses, today) { Scheduling.weeklyQuotaStatus(habit, logs, pauses, today, today) }
                    Text("${quota.requiredDays} sessions per week · Monday through Sunday", style = MaterialTheme.typography.bodyLarge)
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text(
                    // Quota reminders never fire (claimReminder excludes them), whatever an older save stored.
                    if (isQuota) "Flexible days · no daily reminder"
                    else habit.reminderMinutes?.let { "Reminder at ${reminderTime(it)}" } ?: "Reminder off",
                    style = AlmanacType.monoData, color = colors.onSurfaceVariant)
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit schedule") }
        }
    }
}

private fun reminderTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
