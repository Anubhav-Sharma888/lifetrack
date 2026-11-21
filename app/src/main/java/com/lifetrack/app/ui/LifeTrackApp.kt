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
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.lifetrack.app.data.BackupFileKind
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun LifeTrackApp(settingsRequest: Int, habitRequest: Long? = null, sessionRequest: String? = null,
                 vm: HabitViewModel = hiltViewModel(),
                 goalsVm: GoalViewModel = hiltViewModel()) {
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
    val importKind by vm.importKind.collectAsState()
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    // A password is deliberately never put into saved instance state or preferences.
    var backupPassword by remember { mutableStateOf("") }
    // One import trigger shared by the Settings row and the Today restore action.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.clearImportInspection()
        backupPassword = ""
        pendingImport = uri?.toString()
        if (uri != null) vm.inspectImport(uri)
    }
    val launchImport = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { vm.refreshToday(); goalsVm.refreshToday() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settingsRequest, state.loaded) {
        if (settingsRequest > 0 && state.loaded) nav.navigate("settings") { launchSingleTop = true }
    }
    LaunchedEffect(habitRequest, state.loaded) {
        if (habitRequest != null && state.loaded) nav.navigate("habit/$habitRequest") { launchSingleTop = true }
    }
    LaunchedEffect(sessionRequest, state.loaded) {
        if (sessionRequest != null && state.loaded) nav.navigate("session/$sessionRequest") { launchSingleTop = true }
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
    // Goal roadmaps share the app-level snackbar for their save/confirm messages.
    LaunchedEffect(goalsVm) {
        goalsVm.events.collect { event ->
            if (event is UiEvent.Message) scope.launch { snackbar.showSnackbar(event.text) }
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
                    if (route in listOf("today", "activities", "analytics", "goals", "settings")) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(
                                Triple("today", "Today", Icons.Outlined.CheckCircle),
                                Triple("activities", "Activities", Icons.AutoMirrored.Outlined.DirectionsRun),
                                Triple("analytics", "Analytics", Icons.Outlined.Insights),
                                Triple("goals", "Goals", Icons.Outlined.Flag),
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
                    // ---- Goal Roadmaps (Version 2) ----
                    composable("goals") { GoalsScreen(goalsVm, onAdd = { nav.navigate("goalEdit/0") }, onOpen = { nav.navigate("goal/$it") }, onImport = { nav.navigate("goalImport") },
                        onSessions = { nav.navigate("sessions") }) }
                    composable("sessions") { SessionsScreen(onBack = { nav.popBackStack() }, onOpenSession = { nav.navigate("session/$it") }, onAdd = { nav.navigate("sessionEdit") }) }
                    composable("session/{uuid}") {
                        val uuid = it.arguments!!.getString("uuid") ?: ""
                        SessionDetailScreen(uuid, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("sessionEdit?uuid=$it") })
                    }
                    composable(
                        "sessionEdit?uuid={uuid}&goalId={goalId}&taskId={taskId}",
                        arguments = listOf(
                            navArgument("uuid") { type = NavType.StringType; nullable = true },
                            navArgument("goalId") { type = NavType.LongType; defaultValue = 0L },
                            navArgument("taskId") { type = NavType.LongType; defaultValue = 0L }
                        )
                    ) {
                        SessionEditorScreen(
                            editUuid = it.arguments!!.getString("uuid"),
                            prefillGoalId = it.arguments!!.getLong("goalId"),
                            prefillTaskId = it.arguments!!.getLong("taskId"),
                            onBack = { nav.popBackStack() },
                            onSaved = { nav.popBackStack() }
                        )
                    }
                    composable("goal/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        val id = it.arguments!!.getLong("id")
                        GoalDetailScreen(id, goalsVm, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("goalEdit/$id") })
                    }
                    composable("goalEdit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        GoalEditorScreen(it.arguments!!.getLong("id"), goalsVm, today = state.today, onBack = { nav.popBackStack() })
                    }
                    composable("goalImport") {
                        GoalPlanImportScreen(onBack = { nav.popBackStack() }, onViewGoal = { goalId ->
                            nav.navigate("goal/$goalId") { popUpTo("goals") }
                        })
                    }
                }
            }
            pendingImport?.let { path ->
                AlertDialog(
                    onDismissRequest = {
                        if (!busy) { pendingImport = null; backupPassword = ""; vm.clearImportInspection() }
                    },
                    title = { Text("Replace all LifeTrack data?") },
                    text = {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (importKind) {
                                BackupFileKind.PLAIN -> Text("Unencrypted LifeTrack backup — no password is required.")
                                BackupFileKind.ENCRYPTED -> {
                                    Text("This LifeTrack backup is password-protected. Enter its original password.")
                                    OutlinedTextField(
                                        backupPassword,
                                        { if (it.length <= 1024) backupPassword = it },
                                        label = { Text("Backup password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                                        ),
                                        singleLine = true
                                    )
                                }
                                null -> if (busy) Text("Checking the selected backup…")
                                    else Text("The selected file could not be verified. Choose a valid LifeTrack backup.")
                            }
                            Text("Restoring replaces your current data. Export your current data first if you want to keep it. Invalid files and incorrect passwords leave your existing data unchanged.")
                            operationError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !busy && importKind != null &&
                                (importKind != BackupFileKind.ENCRYPTED || backupPassword.isNotEmpty()),
                            onClick = {
                                val password = if (importKind == BackupFileKind.ENCRYPTED)
                                    backupPassword.toCharArray() else null
                                vm.import(Uri.parse(path), password) {
                                    pendingImport = null
                                    backupPassword = ""
                                    vm.clearImportInspection()
                                }
                            }
                        ) { Text(if (busy) "Restoring…" else "Replace and restore") }
                    },
                    dismissButton = {
                        TextButton(enabled = !busy, onClick = {
                            pendingImport = null
                            backupPassword = ""
                            vm.clearImportInspection()
                        }) { Text("Cancel") }
                    }
                )
            }
            if (state.settings.animationsEnabled) MilestoneConfetti(celebration)
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
