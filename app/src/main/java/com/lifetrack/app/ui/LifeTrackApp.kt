package com.lifetrack.app.ui

import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.navigation.NavType
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun LifeTrackApp(settingsRequest: Int, habitRequest: Long? = null, vm: HabitViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
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
    // A password is deliberately never put into saved instance state or preferences.
    var backupPassword by remember { mutableStateOf("") }
    // One import trigger shared by the Settings row and the Today restore action.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.clearOperationError(); backupPassword = ""; pendingImport = uri?.toString() }
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
    LifeTrackTheme(state.settings) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (route in listOf("today", "analytics", "settings")) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(
                                Triple("today", "Today", Icons.Outlined.CheckCircle),
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
                                    icon = { Icon(icon, label) }, label = { Text(label) }
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
                    composable("today") { TodayScreen(state, vm, onAdd = { nav.navigate("edit/0") }, onOpen = { nav.navigate("habit/$it") }, onRestoreBackup = launchImport) }
                    composable("analytics") { AnalyticsScreen(state.habits, state.logs, state.today, state.pauses, state.activities, state.activityEntries) }
                    composable("settings") { SettingsScreen(state, vm, onOpen = { nav.navigate("habit/$it") }, onImport = launchImport) }
                    composable("habit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        val id = it.arguments!!.getLong("id")
                        HabitDetailScreen(id, state, vm, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/$id") })
                    }
                    composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        HabitEditorScreen(it.arguments!!.getLong("id"), state, vm, onBack = { nav.popBackStack() })
                    }
                }
            }
            pendingImport?.let { path -> AlertDialog(onDismissRequest = { if (!busy) { pendingImport = null; backupPassword = "" } }, title = { Text("Replace all LifeTrack data?") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Restoring replaces current habits, history, pauses, freezes and settings. Export your current data first if you want to keep it. Invalid files or a wrong password leave existing data intact.")
                    OutlinedTextField(backupPassword, { if (it.length <= 1024) backupPassword = it }, label = { Text("Password · encrypted backups only") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
                    Text("Leave the password empty for a plain JSON backup. A failed restore stays open so you can retry.", style = MaterialTheme.typography.bodySmall)
                    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            }, confirmButton = { TextButton(enabled = !busy, onClick = {
                vm.import(Uri.parse(path), backupPassword.takeIf { it.isNotEmpty() }?.toCharArray()) { pendingImport = null; backupPassword = "" }
            }) { Text(if (busy) "Restoring…" else "Replace and restore") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pendingImport = null; backupPassword = "" }) { Text("Cancel") } }) }
            if (state.settings.animationsEnabled) MilestoneConfetti(celebration)
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
