package com.lifetrack.app.ui

import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.lifetrack.app.data.BackupKind
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun LifeTrackApp(settingsRequest: Int, habitRequest: Long? = null, vm: HabitViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    // Lives ABOVE the app-lock gate: route saveable state (editor drafts, scroll positions) survives
    // every lock/unmount/remount cycle, exactly as AppLockGateTest pins.
    val routeState = rememberSaveableStateHolder()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "today"
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var celebration by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val busy by vm.busy.collectAsState()
    val operationError by vm.operationError.collectAsState()
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    // One import trigger shared by the Settings row and the Today restore action. The selected
    // file is classified before any password-specific UI is shown (see ImportDialog).
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.clearOperationError(); vm.clearImportClassification(); pendingImport = uri?.toString() }
    val launchImport = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshToday() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settingsRequest, state.loaded) {
        if (settingsRequest > 0 && state.loaded) nav.navigate("settings") { launchSingleTop = true }
    }
    LaunchedEffect(habitRequest, state.loaded) {
        if (habitRequest != null && state.loaded) nav.navigate("habit/$habitRequest") { launchSingleTop = true }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is UiEvent.Undoable -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(event.text, actionLabel = "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) vm.undoCheckIn(event.token)
                }
                is UiEvent.CheckedIn -> {
                    if (state.settings.hapticsEnabled) view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
                    if (state.settings.animationsEnabled && event.day in setOf(7L, 30L, 100L, 365L)) {
                        celebration++
                        scope.launch { snackbar.showSnackbar("Day ${event.day}. Look how far you've come! 🎉") }
                    }
                }
                is UiEvent.DataReset -> nav.navigate("today") { popUpTo(nav.graph.findStartDestination().id) { inclusive = true }; launchSingleTop = true }
            }
        }
    }
    // Achievement celebrations: one subtle snackbar + confetti per newly earned milestone.
    LaunchedEffect(Unit) {
        vm.achievementCelebration.collect { progress ->
            if (state.settings.animationsEnabled) celebration++
            scope.launch { snackbar.showSnackbar("🏆 Milestone earned — ${progress.definition.title}") }
        }
    }
    LifeTrackTheme(state.settings) {
        AppLockGate(enabled = state.settings.appLockEnabled) {
        routeState.SaveableStateProvider("app-content") {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (route in listOf("today", "activities", "analytics", "settings")) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(
                                Triple("today", "Today", Icons.Outlined.CheckCircle),
                                Triple("activities", "Activities", Icons.Outlined.DirectionsRun),
                                Triple("analytics", "Analytics", Icons.Outlined.Insights),
                                Triple("settings", "Settings", Icons.Outlined.Settings)
                            ).forEach { (destination, label, icon) ->
                                NavigationBarItem(
                                    selected = route == destination,
                                    onClick = { nav.navigate(destination) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    } },
                                    icon = { Icon(icon, contentDescription = null) }, label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                if (state.loadError != null) {
                    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                        Text("Your data needs another try", style = MaterialTheme.typography.titleLarge)
                        Text(state.loadError!!)
                        Button(onClick = vm::retryLoading) { Text("Retry opening data") }
                    }
                } else if (!state.loaded) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else NavHost(nav, "today", Modifier.padding(padding).consumeWindowInsets(padding)) {
                    composable("today") { TodayScreen(state, vm, onAdd = { nav.navigate("edit/0") }, onOpen = { nav.navigate("habit/$it") }, onRestoreBackup = launchImport,
                        onSchedules = { nav.navigate("schedules") }, onAchievements = { nav.navigate("achievements") { launchSingleTop = true } }) }
                    composable("activities") { ActivitiesScreen(state, vm) }
                    composable("analytics") { AnalyticsScreen(state.habits, state.logs, state.today, state.pauses, state.activities, state.activityEntries, state.streakProtections, state.reflections, vm.achievements.collectAsStateWithLifecycle().value, onAchievements = { nav.navigate("achievements") { launchSingleTop = true } }) }
                    composable("achievements") { AchievementsScreen(state, vm.achievements.collectAsStateWithLifecycle().value, vm.achievementAcknowledgments.collectAsStateWithLifecycle().value, onBack = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(state, vm, onOpen = { nav.navigate("habit/$it") }, onImport = launchImport,
                        onSchedules = { nav.navigate("schedules") }) }
                    composable("schedules") { SchedulesScreen(state, vm, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/$it") }) }
                    composable("habit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        val id = it.arguments!!.getLong("id")
                        HabitDetailScreen(id, state, vm, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/$id") })
                    }
                    composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        HabitEditorScreen(it.arguments!!.getLong("id"), state, vm, onBack = { nav.popBackStack() })
                    }
                }
            }
            pendingImport?.let { path ->
                // Classify the selected file BEFORE any password-specific UI is shown (I/O in
                // the view model, off the main thread). One dialog window, never stacked.
                LaunchedEffect(path) { vm.classifyImport(path.toUri()) }
                val classification by vm.importClassification.collectAsStateWithLifecycle()
                ImportDialog(
                    classification = classification,
                    busy = busy,
                    operationError = operationError,
                    onDismiss = { if (!busy) { pendingImport = null; vm.clearImportClassification() } },
                    onRestore = { password ->
                        vm.import(path.toUri(), password) { pendingImport = null; vm.clearImportClassification() }
                    })
            }
            if (state.settings.animationsEnabled) MilestoneConfetti(celebration)
        }
        }
        }
    }
}

/** The single import dialog: classification-first UX. PLAIN backups restore with no password
 *  field at all; ENCRYPTED backups request the password; an unopenable file shows a clear
 *  error. Restore stays enabled only once a classification exists, and a failed restore
 *  keeps the dialog open with the error so the user can retry. */
@Composable
internal fun ImportDialog(
    classification: HabitViewModel.ImportClassification?,
    busy: Boolean,
    operationError: String?,
    onDismiss: () -> Unit,
    onRestore: (CharArray?) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    // Custom dialog shell (the LogEditorDialog idiom): platform-AlertDialog width derivation
    // measure-loops under Robolectric, and the shell keeps this a single predictable window.
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp)) {
            Column(Modifier.heightIn(max = with(LocalDensity.current) {
                (LocalWindowInfo.current.containerSize.height * 0.85f).toDp()
            }).padding(24.dp)) {
                Text(when {
                    classification?.kind == BackupKind.ENCRYPTED -> "Restore password-protected LifeTrack backup?"
                    classification?.kind == BackupKind.PLAIN -> "Restore unencrypted LifeTrack backup?"
                    classification?.error != null -> "Backup could not be opened"
                    else -> "Inspecting backup…"
                }, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        classification?.kind == BackupKind.ENCRYPTED -> {
                            Text("Password-protected LifeTrack backup. Restoring replaces current habits, history, pauses, freezes, activities, reflections and settings. Export your current data first if you want to keep it.")
                            OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text("Backup password") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
                            Text("A wrong password or damaged file leaves current records intact and this dialog open so you can retry.", style = MaterialTheme.typography.bodySmall)
                        }
                        classification?.kind == BackupKind.PLAIN -> {
                            Text("Unencrypted LifeTrack backup — no password is needed. Restoring replaces current habits, history, pauses, freezes, activities, reflections and settings. Export your current data first if you want to keep it.")
                            Text("A failed restore stays open so you can retry.", style = MaterialTheme.typography.bodySmall)
                        }
                        classification?.error != null -> Text(classification.error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        else -> Text("Inspecting the selected backup…", style = MaterialTheme.typography.bodySmall)
                    }
                    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    // The footer button exists only once the classification is known and the
                    // restore can proceed — while inspecting, Cancel alone is offered.
                    val plainReady = classification?.kind == BackupKind.PLAIN
                    val encryptedReady = classification?.kind == BackupKind.ENCRYPTED && password.isNotEmpty()
                    if (plainReady || encryptedReady) TextButton(enabled = !busy, onClick = {
                        // Plain restores pass no password material at all; encrypted restores hand the
                        // entered password to the crypto layer, which zeroes the array when it finishes.
                        onRestore(if (plainReady) null else password.toCharArray())
                    }) { Text(if (busy) "Restoring…" else "Replace and restore") }
                }
            }
        }
    }
}

@Composable
private fun MilestoneConfetti(trigger: Int) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) { progress.snapTo(0f); progress.animateTo(1f, tween(1800)) }
    }
    if (progress.value < 1f) Canvas(Modifier.fillMaxSize()) {
        val colors = listOf(Color(0xFF427C62), Color(0xFFE5B75D), Color(0xFF8E85BC), Color(0xFFDD8A7C))
        repeat(42) { index ->
            val p = progress.value
            val x = size.width * ((index * 37 % 101) / 100f) + sin(index + p * 8f) * 24.dp.toPx()
            val y = -50.dp.toPx() + size.height * p * (0.7f + index % 4 * .12f)
            drawCircle(colors[index % colors.size].copy(alpha = 1f - p), 3.dp.toPx(), Offset(x, y))
        }
    }
}
