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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
fun SettingsScreen(
    state: LifeTrackState,
    vm: HabitViewModel,
    onImport: () -> Unit = {},
    onSchedules: () -> Unit = {},
    onOpen: (Long) -> Unit
) {
    val context = LocalContext.current
    val settings = state.settings
    val latestSettings by rememberUpdatedState(settings)
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    var themeDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    var licensesDialog by remember { mutableStateOf(false) }
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
    // The reset dialog closes only once the erase is actually saved; a failure leaves it open with the error.
    LaunchedEffect(Unit) {
        vm.events.collect { if (it is UiEvent.DataReset) { showResetConfirm = false; resetConfirmation = "" } }
    }
    val launchExport: (Boolean) -> Unit = { encrypted ->
        encryptedExport = encrypted
        val prefix = if (encrypted) "lifetrack-encrypted" else "lifetrack"
        export.launch("$prefix-${state.today}-${System.currentTimeMillis()}.json")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Settings & Preferences", style = MaterialTheme.typography.headlineLarge)
            Text("Your data. Your device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIcon(Icons.Outlined.Shield)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Private by design", style = MaterialTheme.typography.titleLarge)
                    Text("No accounts, cloud sync, ads or tracking. Habits, notes and settings stay on this device.")
                    Text("Uninstalling LifeTrack or clearing app storage deletes all local data. Export a backup regularly.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SettingsSection("Cadence & notifications") {
            SettingsAction("Schedules & Reminders", "Habit schedules and daily check-ins", Icons.Outlined.Notifications, onClick = onSchedules)
        }
        SettingsSection("Display & accessibility") {
            SettingsAction("Theme", settings.theme.lowercase().replaceFirstChar(Char::titlecase), Icons.Outlined.DarkMode, !busy) { themeDialog = true }
            HorizontalDivider()
            PreferenceSwitch("Wallpaper colors", "Material You · Android 12 and later", settings.dynamicColor, Build.VERSION.SDK_INT >= 31 && !busy) { vm.updateSettings(settings.copy(dynamicColor = it)) }
            HorizontalDivider()
            PreferenceSwitch("Haptic feedback", "A gentle tick when you check in", settings.hapticsEnabled, !busy) { vm.updateSettings(settings.copy(hapticsEnabled = it)) }
            HorizontalDivider()
            PreferenceSwitch("Animations and celebrations", "Turn off motion and milestone confetti", settings.animationsEnabled, !busy) { vm.updateSettings(settings.copy(animationsEnabled = it)) }
        }
        SettingsSection("Data & backups") {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Backup history", style = MaterialTheme.typography.titleMedium)
                    val lastExport = settings.lastExportAt?.let { timestamp ->
                        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
                    }
                    Text(lastExport?.let { "Last export · $it" } ?: "No backup exported yet", style = AlmanacType.monoData)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SettingsAction("Export data", "Save a complete JSON backup", Icons.Outlined.FileUpload, enabled = !busy) { launchExport(false) }
            HorizontalDivider()
            SettingsAction("Export encrypted backup", "Protect the file with a password · AES-256-GCM", Icons.Outlined.Lock, enabled = !busy) { launchExport(true) }
            HorizontalDivider()
            SettingsAction("Import data", "Restore a backup · replaces current data", Icons.Outlined.FileDownload, enabled = !busy, onClick = onImport)
            HorizontalDivider()
            PreferenceSwitch("Monthly backup reminder", "A reminder after 30 days", settings.backupReminder, !busy) { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else vm.updateSettings(settings.copy(backupReminder = enabled))
            }
            permissionMessage?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            SettingsAction("Reset LifeTrack", "Erase all local data and start fresh", Icons.Outlined.DeleteForever, enabled = !busy, destructive = true) {
                vm.clearOperationError()
                showResetConfirm = true
            }
        }
        val archived = state.habits.filter { it.isArchived }
        if (archived.isNotEmpty()) {
            SettingsSection("Archived habits (${archived.size})") {
                archived.forEachIndexed { index, habit ->
                    if (index > 0) HorizontalDivider()
                    SettingsAction("${habit.icon} ${habit.name}", "View history or restore", Icons.Outlined.Archive) { onOpen(habit.id) }
                }
            }
        }
        SettingsGroup {
            SettingsAction("About LifeTrack", "Version 1.7.0", Icons.Outlined.Info) { aboutDialog = true }
            HorizontalDivider()
            SettingsAction("Open-source licenses", "Runtime library license and notices", Icons.Outlined.Description) { licensesDialog = true }
        }
    }
    if (themeDialog) AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("Choose theme") }, text = {
        Column { listOf("SYSTEM", "LIGHT", "DARK").forEach { theme ->
            Row(Modifier.fillMaxWidth().clickable { vm.updateSettings(settings.copy(theme = theme)); themeDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.theme == theme, onClick = { vm.updateSettings(settings.copy(theme = theme)); themeDialog = false })
                Text(theme.lowercase().replaceFirstChar(Char::titlecase))
            }
        } }
    }, confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Done") } })
    if (showResetConfirm) ResetLifeTrackDialog(
        confirmation = resetConfirmation,
        busy = busy,
        error = operationError,
        onConfirmationChange = { if (it.length <= 16) resetConfirmation = it },
        onExport = launchExport,
        onErase = vm::resetAllData,
        onCancel = {
            vm.clearOperationError()
            showResetConfirm = false
            resetConfirmation = ""
        }
    )
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
    if (aboutDialog) AlertDialog(onDismissRequest = { aboutDialog = false }, title = { Text("LifeTrack 1.7.0") }, text = {
        Text("An offline habit companion. Every habit has its own Day 1.\n\nAll user records live in a local Room database. There is no Internet permission or automatic cloud backup. Choose plain JSON for readability or optional password encryption for a private portable copy.\n\nLocal habit reminders are inexact and may be deferred by Android.\n\nCharts: Vico. Reordering: Calvin Reorderable. AndroidX, Kotlin and Hilt. Runtime libraries use Apache 2.0.", Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text("Close") } })
    if (licensesDialog) {
        val license by produceState<String?>(null) {
            value = withContext(Dispatchers.IO) { context.assets.open("LICENSE-APACHE-2.0.txt").bufferedReader().use { it.readText() } }
        }
        AlertDialog(onDismissRequest = { licensesDialog = false }, title = { Text("Open-source licenses") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("AndroidX, Kotlin, kotlinx.coroutines, kotlinx.serialization, Dagger/Hilt, Vico by Patryk & Patrick, and Reorderable by Calvin Liang are distributed under Apache License 2.0.\n\nThe bundled typefaces Newsreader, Plus Jakarta Sans and JetBrains Mono are distributed under the SIL Open Font License 1.1; their license texts ship in the app assets (OFL-*.txt).\n\n")
                Text(license ?: "Loading license…", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { licensesDialog = false }) { Text("Close") } })
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup(content = content)
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, almanacColors().rule)
    ) { Column(content = content) }
}

@Composable
internal fun SettingsIcon(icon: ImageVector, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = if (destructive) colors.errorContainer else colors.surfaceContainerHigh) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (destructive) colors.onErrorContainer else colors.onSurface)
        }
    }
}

@Composable
internal fun SettingsAction(title: String, subtitle: String, icon: ImageVector, enabled: Boolean = true,
    destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(title, color = if (destructive) colors.error else colors.onSurface) },
        supportingContent = { Text(subtitle) },
        leadingContent = { SettingsIcon(icon, destructive) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null, tint = if (destructive) colors.error else colors.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = colors.surface)
    )
}
