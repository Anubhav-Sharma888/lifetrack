package com.lifetrack.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** SAF owns the save destination. Downloads is offered as the initial location. */
private class CreateBackupDocument : ActivityResultContracts.CreateDocument("application/json") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(state: LifeTrackState, vm: HabitViewModel, onImport: () -> Unit = {}, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val settings = state.settings
    val latestSettings by rememberUpdatedState(settings)
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    var themeDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    var licensesDialog by remember { mutableStateOf(false) }
    var reminderDialog by remember { mutableStateOf(false) }
    var pendingReminderMinutes by remember { mutableStateOf<Int?>(null) }
    var pendingEncryptedExport by rememberSaveable { mutableStateOf<String?>(null) }
    var encryptedExport by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    // A password is deliberately never put into saved instance state or preferences.
    var backupPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    // The typed reset confirmation stays out of saved instance state too, like the backup password.
    var resetConfirmation by remember { mutableStateOf("") }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri != null) {
            if (encryptedExport) { vm.clearOperationError(); pendingEncryptedExport = uri.toString(); backupPassword = ""; confirmPassword = "" }
            else vm.export(uri)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.updateSettings(latestSettings.copy(backupReminder = true))
        else permissionMessage = "Notifications are off. You can enable them in Android settings and turn reminders on here."
    }
    val dailyNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val minutes = pendingReminderMinutes
        pendingReminderMinutes = null
        if (granted && minutes != null) vm.setDailyReminder(minutes)
        else permissionMessage = "Notifications are off. You can enable them in Android settings and turn reminders on here."
        reminderDialog = false
    }
    // The reset dialog closes only once the erase is actually saved; a failure leaves it open with the error.
    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.DataReset) { showResetConfirm = false; resetConfirmation = "" } }
    }
    val dailyMinutes by vm.dailyReminderMinutes.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Text("Your data. Your device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                Text("Private by design", style = MaterialTheme.typography.titleLarge)
                Text("No accounts, cloud sync, ads or tracking. Habits, notes and settings stay on this device.")
                Text("Uninstalling LifeTrack or clearing app storage deletes all local data. Export a backup regularly.", color = MaterialTheme.colorScheme.error)
            }
        }
        Text("Data & backups", style = MaterialTheme.typography.titleMedium)
        SettingsAction("Export data", "Save a complete JSON backup", Icons.Outlined.FileUpload, enabled = !busy) {
            encryptedExport = false
            export.launch("lifetrack-${state.today}-${System.currentTimeMillis()}.json")
        }
        SettingsAction("Export encrypted backup", "Protect the file with a password · AES-256-GCM", Icons.Outlined.Lock, enabled = !busy) {
            encryptedExport = true
            export.launch("lifetrack-encrypted-${state.today}-${System.currentTimeMillis()}.json")
        }
        SettingsAction("Import data", "Restore a backup · replaces current data", Icons.Outlined.FileDownload, enabled = !busy) { onImport() }
        SettingsAction("Reset LifeTrack", "Erase all data on this device", Icons.Outlined.DeleteForever, enabled = !busy) { vm.clearOperationError(); showResetConfirm = true }
        Text(settings.lastExportAt?.let { "Last export: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))}" } ?: "No backup exported yet", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monthly backup reminder", style = MaterialTheme.typography.titleSmall)
                Text("A gentle reminder after 30 days. Export to your computer or a provider you choose.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.backupReminder, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Monthly backup reminder" }, onCheckedChange = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.updateSettings(settings.copy(backupReminder = enabled))
            })
        }
        permissionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text("Gentle reminders", style = MaterialTheme.typography.titleMedium)
        SettingsAction("Daily check-in reminder", dailyMinutes?.let { m -> "Daily at %02d:%02d · system notification".format(m / 60, m % 60) } ?: "Off · choose a time", Icons.Outlined.Notifications, enabled = !busy) { reminderDialog = true }
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SettingsAction("Theme", settings.theme.lowercase().replaceFirstChar(Char::titlecase), Icons.Outlined.DarkMode, !busy) { themeDialog = true }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wallpaper colors", style = MaterialTheme.typography.titleSmall)
                Text("Material You · Android 12 and later", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.dynamicColor, enabled = Build.VERSION.SDK_INT >= 31 && !busy,
                modifier = Modifier.semantics { contentDescription = "Wallpaper colors" }, onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) })
        }
        PreferenceSwitch("Haptic feedback", "A gentle tick when you check in", settings.hapticsEnabled, !busy) { vm.updateSettings(settings.copy(hapticsEnabled = it)) }
        PreferenceSwitch("Animations and celebrations", "Turn off motion and milestone confetti", settings.animationsEnabled, !busy) { vm.updateSettings(settings.copy(animationsEnabled = it)) }
        val archived = state.habits.filter { it.isArchived }
        if (archived.isNotEmpty()) {
            HorizontalDivider()
            Text("Archived habits (${archived.size})", style = MaterialTheme.typography.titleMedium)
            archived.forEach { habit -> SettingsAction("${habit.icon} ${habit.name}", "View history or restore", Icons.Outlined.Archive) { onOpen(habit.id) } }
        }
        HorizontalDivider()
        SettingsAction("About LifeTrack", "Version 1.5.0 · Made for your own pace", Icons.Outlined.Info) { aboutDialog = true }
        SettingsAction("Open-source licenses", "Runtime library license and notices", Icons.Outlined.Description) { licensesDialog = true }
        Spacer(Modifier.height(16.dp))
    }
    if (themeDialog) AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("Choose theme") }, text = {
        Column { listOf("SYSTEM", "LIGHT", "DARK").forEach { theme ->
            Row(Modifier.fillMaxWidth().clickable { vm.updateSettings(settings.copy(theme = theme)); themeDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.theme == theme, onClick = { vm.updateSettings(settings.copy(theme = theme)); themeDialog = false })
                Text(theme.lowercase().replaceFirstChar(Char::titlecase))
            }
        } }
    }, confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Done") } })
    if (reminderDialog) {
        val current = dailyMinutes
        val timeState = rememberTimePickerState(initialHour = current?.div(60) ?: 20, initialMinute = current?.rem(60) ?: 0, is24Hour = true)
        AlertDialog(onDismissRequest = { reminderDialog = false }, title = { Text("Daily check-in reminder") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A once-a-day notification at your time, while habits are still open.")
                TimePicker(timeState)
                Text("Reminders are inexact and may be deferred by Android.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val minutes = timeState.hour * 60 + timeState.minute
                reminderDialog = false
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingReminderMinutes = minutes
                    dailyNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else vm.setDailyReminder(minutes)
            }) { Text("Save") }
        }, dismissButton = {
            Row {
                if (current != null) TextButton(enabled = !busy, onClick = { vm.setDailyReminder(null); reminderDialog = false }) { Text("Turn off") }
                TextButton(onClick = { reminderDialog = false }) { Text("Cancel") }
            }
        })
    }
    if (showResetConfirm) AlertDialog(onDismissRequest = { if (!busy) { vm.clearOperationError(); showResetConfirm = false } }, title = { Text("Reset LifeTrack") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This permanently erases all LifeTrack data on this device: active and archived habits, check-ins and their notes, dated goals, workouts and logged activities, the daily reminder, and app preferences. Previously exported backup files remain available. Export a backup first if you want a copy, then type RESET to confirm.")
            OutlinedTextField(resetConfirmation, { if (it.length <= 16) resetConfirmation = it }, label = { Text("Type RESET to confirm") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false), singleLine = true)
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        }
    }, confirmButton = {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                encryptedExport = false
                export.launch("lifetrack-${state.today}-${System.currentTimeMillis()}.json")
            }) { Text("Export a backup first") }
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                encryptedExport = true
                export.launch("lifetrack-encrypted-${state.today}-${System.currentTimeMillis()}.json")
            }) { Text("Export an encrypted backup") }
            TextButton(enabled = !busy && resetConfirmation == "RESET", modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { vm.resetAllData() }) { Text("Erase all local data") }
        }
    }, dismissButton = { TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { vm.clearOperationError(); showResetConfirm = false; resetConfirmation = "" }) { Text("Cancel") } })
    pendingEncryptedExport?.let { path -> AlertDialog(onDismissRequest = { if (!busy) { pendingEncryptedExport = null; backupPassword = ""; confirmPassword = "" } }, title = { Text("Protect this backup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a strong password of at least 10 characters. It is not saved anywhere in LifeTrack. A forgotten password cannot be recovered.")
            OutlinedTextField(backupPassword, { if (it.length <= 1024) backupPassword = it }, label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
            OutlinedTextField(confirmPassword, { if (it.length <= 1024) confirmPassword = it }, label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
            Text("The selected file is written only after encryption succeeds. Keep a private copy of your password.", style = MaterialTheme.typography.bodySmall)
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }, confirmButton = { TextButton(enabled = !busy && backupPassword.length >= 10 && backupPassword == confirmPassword, onClick = {
        vm.export(Uri.parse(path), backupPassword.toCharArray()) { pendingEncryptedExport = null; backupPassword = ""; confirmPassword = "" }
    }) { Text(if (busy) "Encrypting…" else "Encrypt and export") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pendingEncryptedExport = null; backupPassword = ""; confirmPassword = "" }) { Text("Cancel") } }) }
    if (aboutDialog) AlertDialog(onDismissRequest = { aboutDialog = false }, title = { Text("LifeTrack 1.3.0") }, text = {
        Text("An offline habit companion. Every habit has its own Day 1.\n\nAll user records live in a local Room database. There is no Internet permission or automatic cloud backup. Choose plain JSON for readability or optional password encryption for a private portable copy.\n\nLocal habit reminders are inexact and may be deferred by Android.\n\nCharts: Vico. Reordering: Calvin Reorderable. AndroidX, Kotlin and Hilt. Runtime libraries use Apache 2.0.", Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text("Close") } })
    if (licensesDialog) {
        val license by produceState<String?>(null) {
            value = withContext(Dispatchers.IO) { context.assets.open("LICENSE-APACHE-2.0.txt").bufferedReader().use { it.readText() } }
        }
        AlertDialog(onDismissRequest = { licensesDialog = false }, title = { Text("Open-source licenses") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("AndroidX, Kotlin, kotlinx.coroutines, kotlinx.serialization, Dagger/Hilt, Vico by Patryk & Patrick, and Reorderable by Calvin Liang are distributed under Apache License 2.0.\n\n")
                Text(license ?: "Loading license…", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { licensesDialog = false }) { Text("Close") } })
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    )
}
