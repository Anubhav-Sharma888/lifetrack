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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifetrack.app.data.Habit
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitEditorScreen(id: Long, state: LifeTrackState, vm: HabitViewModel, onBack: () -> Unit) {
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
    val targetProtected = state.logs.any { it.habitId == id }
    val selectedDays = days.split(',').mapNotNull(String::toIntOrNull).sorted()
    val targetNumber = target.toIntOrNull()
    val reminderNumber = if (reminder && hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59) hour.toInt() * 60 + minute.toInt() else null
    val valid = name.isNotBlank() && name.length <= 120 && category.isNotBlank() && category.length <= 40 &&
        icon.isNotBlank() && Regex("#[0-9a-fA-F]{6}").matches(color) && selectedDays.isNotEmpty() &&
        targetNumber != null && targetNumber in 1..100_000 && unit.isNotBlank() && unit.length <= 24 && (!reminder || reminderNumber != null)
    val dirty = name != (original?.name ?: "") || description != (original?.description ?: "") || icon != (original?.icon ?: "🌱") ||
        color != (original?.colorHex ?: "#43866B") || category != (original?.category ?: "Personal") ||
        start != (original?.startDate ?: state.today).toString() || afterId != original?.afterHabitId ||
        selectedDays != (original?.scheduledDays ?: (1..7).toList()) || target != (original?.targetCount ?: 1).toString() ||
        unit != (original?.targetUnit ?: "times") || reminderNumber != original?.reminderMinutes
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
            OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text("Habit name") }, singleLine = true,
                supportingText = { Text("${name.length}/120") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { if (it.length <= 2000) description = it }, label = { Text("Description · optional") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(icon, { if (it.length <= 32) icon = it }, label = { Text("Emoji / icon") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(category, { if (it.length <= 40) category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(2f))
            }
            OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.DateRange, null); Spacer(Modifier.width(12.dp)); Text("Start date · $start")
            }
            Text("Day 1 stays anchored to this date. Planned rest days do not break your chain.", style = MaterialTheme.typography.bodySmall)
            Text("Scheduled days", style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("A measurable intention", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(target, { if (it.length <= 6 && it.all(Char::isDigit)) target = it }, enabled = !targetProtected, label = { Text("Daily target") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    isError = targetNumber == null || targetNumber !in 1..100_000, modifier = Modifier.weight(1f))
                OutlinedTextField(unit, { if (it.length <= 24) unit = it }, enabled = !targetProtected, label = { Text("Unit") }, placeholder = { Text("pages, glasses…") }, singleLine = true, modifier = Modifier.weight(1.5f))
            }
            Text("Use 1 time for a simple check-in, or track a count toward your goal.", style = MaterialTheme.typography.bodySmall)
            if (targetProtected) Text("Existing check-ins keep their original goal. Create a new habit to change its target or unit; your other settings remain editable.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Habit reminder", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(reminder, onCheckedChange = { enabled ->
                    reminder = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, modifier = Modifier.semantics { contentDescription = "Habit reminder"; stateDescription = if (reminder) "Enabled" else "Disabled"; role = Role.Switch })
            }
            if (reminder) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hour, { if (it.length <= 2 && it.all(Char::isDigit)) hour = it }, label = { Text("Hour · 00–23") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(minute, { if (it.length <= 2 && it.all(Char::isDigit)) minute = it }, label = { Text("Minute · 00–59") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("A local nudge at or after this time on scheduled days. Android may delay background work; this is not an exact alarm.", style = MaterialTheme.typography.bodySmall)
                permissionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Color", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("#43866B", "#547DBB", "#B67B98", "#B48647", "#8877B0", "#BA7166").forEach { hex ->
                    Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Surface(color = Color(android.graphics.Color.parseColor(hex)), shape = CircleShape,
                            border = if (hex.equals(color, true)) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                            modifier = Modifier.size(44.dp).clickable(onClickLabel = "Use color $hex") { color = hex }) {}
                    }
                }
            }
            OutlinedTextField(color, { if (it.length <= 7) color = it }, label = { Text("Hex color") }, isError = !Regex("#[0-9a-fA-F]{6}").matches(color), singleLine = true, modifier = Modifier.fillMaxWidth())
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("Habit stacking", style = MaterialTheme.typography.titleMedium)
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
            Button(enabled = valid && !busy, onClick = {
                vm.saveHabit((original ?: Habit(name = name)).copy(
                    name = name.trim(), description = description.trim().ifBlank { null }, icon = icon.trim(), colorHex = color.uppercase(),
                    category = category.trim(), startDate = LocalDate.parse(start), afterHabitId = afterId,
                    scheduledDays = selectedDays, targetCount = targetNumber!!, targetUnit = unit.trim(), reminderMinutes = reminderNumber
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
