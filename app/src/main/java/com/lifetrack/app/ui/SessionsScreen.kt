package com.lifetrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.app.data.GoalSessionStatus
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun rememberTimeFormatter(): DateTimeFormatter =
    remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }

/**
 * The sessions overview (Prompt 5): upcoming, in-progress, awaiting-review (a derived read-time
 * state — an unconfirmed session past its end is never auto-completed) and a bounded history.
 * Reached from Goals; no sixth bottom destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(vm: SessionViewModel = hiltViewModel(), onBack: () -> Unit,
                            onOpenSession: (String) -> Unit, onAdd: () -> Unit) {
    val state by vm.overview.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val dateFormatter = rememberRowDateFormatter()
    val timeFormatter = rememberTimeFormatter()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Schedule session") }
            )
        }
    ) { padding ->
        when {
            !state.loaded -> Unit
            state.upcoming.isEmpty() && state.inProgress.isEmpty() &&
                state.awaitingReview.isEmpty() && state.history.isEmpty() ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.EventNote, contentDescription = null,
                        modifier = Modifier.heightIn(min = 48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Schedule focused work time", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Plan study sessions for your goals, get a reminder when they start, and " +
                            "confirm what you actually finished.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.inProgress.isNotEmpty()) {
                    item(key = "in-progress-header") { SectionHeader("In progress") }
                    items(state.inProgress, key = { it.session.uuid }) { SessionCard(it, dateFormatter, timeFormatter, onOpenSession) }
                }
                if (state.awaitingReview.isNotEmpty()) {
                    item(key = "review-header") { SectionHeader("Awaiting review") }
                    items(state.awaitingReview, key = { it.session.uuid }) { SessionCard(it, dateFormatter, timeFormatter, onOpenSession) }
                }
                if (state.upcoming.isNotEmpty()) {
                    item(key = "upcoming-header") { SectionHeader("Upcoming") }
                    items(state.upcoming, key = { it.session.uuid }) { SessionCard(it, dateFormatter, timeFormatter, onOpenSession) }
                }
                if (state.history.isNotEmpty()) {
                    item(key = "history-header") { SectionHeader("Completed history") }
                    items(state.history, key = { it.session.uuid }) { SessionCard(it, dateFormatter, timeFormatter, onOpenSession) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SessionCard(card: SessionCardState, dateFormatter: DateTimeFormatter,
                        timeFormatter: DateTimeFormatter, onOpen: (String) -> Unit) {
    val session = card.session
    val state = GoalSessionStatus.fromStored(session.status)
    val stateLabel = when {
        card.bucket == SessionBucket.AWAITING_REVIEW -> "Review needed"
        state == GoalSessionStatus.PARTIAL -> "Partial"
        state == GoalSessionStatus.SKIPPED -> "Skipped"
        state == GoalSessionStatus.COMPLETED -> "Completed"
        state == GoalSessionStatus.IN_PROGRESS -> "In progress"
        state == GoalSessionStatus.CANCELED -> "Canceled"
        else -> "Planned"
    }
    val description = "${session.title}. $stateLabel. Planned " +
        "${session.plannedStartDate.format(dateFormatter)} from " +
        "${session.plannedStartTime.format(timeFormatter)} to ${session.plannedEndTime.format(timeFormatter)}." +
        (card.goalTitle?.let { " Goal: $it." } ?: "") +
        (card.taskTitle?.let { " Task: $it." } ?: "")
    Card(
        onClick = { onOpen(session.uuid) },
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                SessionStatusBadge(stateLabel, card.bucket == SessionBucket.AWAITING_REVIEW)
            }
            Text(
                "${session.plannedStartDate.format(dateFormatter)} · " +
                    "${session.plannedStartTime.format(timeFormatter)}–${session.plannedEndTime.format(timeFormatter)}",
                style = MaterialTheme.typography.bodyMedium
            )
            card.goalTitle?.let {
                Text("Goal: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            card.taskTitle?.let {
                Text("Task: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state in setOf(GoalSessionStatus.COMPLETED, GoalSessionStatus.PARTIAL)) {
                val reportedText = session.reportedMinutes?.let { " · $it min reported" } ?: ""
                Text("Started ${session.actualStartAt?.let { "at a recorded time" } ?: "—"} · " +
                    "ended ${session.actualEndAt?.let { "at a recorded time" } ?: "—"}$reportedText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SessionStatusBadge(label: String, highlight: Boolean) {
    val colors = if (highlight) {
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.second,
        modifier = Modifier.heightIn(min = 24.dp).background(colors.first, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
