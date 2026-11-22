package com.lifetrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalSessionStatus
import java.time.ZoneId

/** The session detail screen: planned vs actual, explicit start/complete/cancel actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailScreen(uuid: String, vm: SessionViewModel = hiltViewModel(),
                                 onBack: () -> Unit, onEdit: (String) -> Unit) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    LaunchedEffect(uuid) { vm.selectSession(uuid) }
    val session = detail
    val dateFormatter = rememberRowDateFormatter()
    val timeFormatter = rememberTimeFormatter()
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Session") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (session != null) {
                    IconButton(enabled = !busy, onClick = { onEdit(session.uuid) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit session")
                    }
                }
            }
        )
    }) { padding ->
        when {
            session == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("This session is gone", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "It may have been deleted or restored away. Your other sessions are unaffected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onBack) { Text("Back") }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(session.title, style = MaterialTheme.typography.titleLarge)
                session.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val state = GoalSessionStatus.fromStored(session.status)
                val stateLabel = when (state) {
                    GoalSessionStatus.PLANNED -> "Planned"
                    GoalSessionStatus.IN_PROGRESS -> "In progress"
                    GoalSessionStatus.COMPLETED -> "Completed"
                    GoalSessionStatus.PARTIAL -> "Partially completed"
                    GoalSessionStatus.SKIPPED -> "Skipped"
                    GoalSessionStatus.CANCELED -> "Canceled"
                }
                SessionStatusBadge(stateLabel, vm.isAwaitingReview(session))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Planned window", style = MaterialTheme.typography.titleSmall)
                        Text("${session.plannedStartDate.format(dateFormatter)} · " +
                            "${session.plannedStartTime.format(timeFormatter)} – " +
                            "${session.plannedEndDate.format(dateFormatter)} " +
                            "${session.plannedEndTime.format(timeFormatter)}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Time zone: ${session.timezoneId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (session.originalPlannedStartDate != null && session.originalPlannedStartTime != null) {
                            HorizontalDivider()
                            Text("Original planned window", style = MaterialTheme.typography.titleSmall)
                            Text("${session.originalPlannedStartDate.format(dateFormatter)} · " +
                                "${session.originalPlannedStartTime.format(timeFormatter)}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                        Text("Recorded actual times", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                session.reportedMinutes != null ->
                                    "Reported time: ${session.reportedMinutes} minutes" +
                                        (session.actualEndAt?.let { "\nConfirmed at: ${formatInstant(it)}" } ?: "")
                                session.actualStartAt == null && session.actualEndAt == null ->
                                    "No recorded start or end yet — evidence appears only after your own actions."
                                else -> "Started: ${session.actualStartAt?.let { formatInstant(it) } ?: "not recorded"}\n" +
                                    "Ended: ${session.actualEndAt?.let { formatInstant(it) } ?: "not recorded"}"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (session.snoozeCount > 0) {
                            Text("Check-in snoozed: ${session.snoozeCount} times",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val plannedMinutes = runCatching {
                    java.time.Duration.between(
                        session.plannedStartDate.atTime(session.plannedStartTime),
                        session.plannedEndDate.atTime(session.plannedEndTime)
                    ).toMinutes().toInt()
                }.getOrNull()
                val partialMinutes = ((plannedMinutes ?: 60) / 2).coerceAtLeast(1)
                operationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                // Explicit lifecycle and recovery actions only — nothing here completes work
                // automatically, and every control maps to a persisted state transition.
                when (state) {
                    GoalSessionStatus.PLANNED, GoalSessionStatus.IN_PROGRESS -> {
                        if (state == GoalSessionStatus.PLANNED) {
                            Button(enabled = !busy, onClick = { vm.startSession(session.uuid) },
                                modifier = Modifier.fillMaxWidth()) { Text("Start session") }
                        }
                        Button(enabled = !busy, onClick = { vm.confirmCompletion(session.uuid) },
                            modifier = Modifier.fillMaxWidth()) { Text("Confirm completed") }
                        OutlinedButton(enabled = !busy, onClick = { vm.recordPartial(session.uuid, reportedMinutes = partialMinutes) },
                            modifier = Modifier.fillMaxWidth()) { Text("Record partial ($partialMinutes min)") }
                        OutlinedButton(enabled = !busy, onClick = { vm.skipSession(session.uuid) },
                            modifier = Modifier.fillMaxWidth()) { Text("Skip session") }
                        OutlinedButton(enabled = !busy, onClick = { vm.snoozeCheckIn(session.uuid, minutes = 10) },
                            modifier = Modifier.fillMaxWidth()) { Text("Snooze check-in 10 min") }
                        OutlinedButton(enabled = !busy, onClick = {
                            vm.rescheduleSession(session.uuid, session.plannedStartDate.plusDays(1),
                                session.plannedStartTime, session.plannedEndDate.plusDays(1), session.plannedEndTime)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Reschedule +1 day") }
                        OutlinedButton(enabled = !busy, onClick = { showCancelConfirm = true },
                            modifier = Modifier.fillMaxWidth()) { Text("Cancel session") }
                    }
                    else -> Unit
                }
                TextButton(enabled = !busy, onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state == GoalSessionStatus.COMPLETED) "Delete recorded session" else "Delete session")
                }
                if (state == GoalSessionStatus.COMPLETED) {
                    Text(
                        "Deleting a completed session erases its recorded history. Canceling would " +
                            "not be reversible for completed work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (showCancelConfirm && session != null) {
        ConfirmDialog(
            title = "Cancel this session?",
            body = "Canceling marks the session as canceled. Its planned window is preserved in history.",
            confirmLabel = "Cancel session",
            busy = busy,
            onConfirm = {
                vm.cancelSession(session.uuid)
                showCancelConfirm = false
            },
            onDismiss = { showCancelConfirm = false }
        )
    }
    if (showDeleteConfirm && session != null) {
        val isCompleted = GoalSessionStatus.fromStored(session.status) == GoalSessionStatus.COMPLETED
        ConfirmDialog(
            title = if (isCompleted) "Delete recorded session?" else "Delete this session?",
            body = if (isCompleted)
                "Deleting a completed session permanently erases its recorded history and minutes."
            else "Deleting permanently removes this scheduled session.",
            confirmLabel = "Delete",
            busy = busy,
            onConfirm = {
                vm.deleteSession(session.uuid)
                showDeleteConfirm = false
                onBack()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

/** Renders a UTC epoch-millis instant in the session's own zone context, in local device time. */
private fun formatInstant(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
