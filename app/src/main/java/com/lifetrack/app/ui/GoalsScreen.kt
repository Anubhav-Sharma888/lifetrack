package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.GoalStatus
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Goals hub: every roadmap, one honest progress line each, and the filters people actually
 * use. The primary reading path is title → status → progress; everything else is secondary text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalsScreen(vm: GoalViewModel, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val hub by vm.hub.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshToday() }
    val dateFormatter = rememberRowDateFormatter()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Goals") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New goal") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow(hub.filter, vm::setFilter)
            when {
                !hub.loaded -> Unit
                hub.databaseEmpty -> GoalsEmptyState(onAdd)
                hub.cards.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (hub.filter) {
                            GoalFilter.ACTIVE -> "No active goals. Create one, or check the other filters."
                            GoalFilter.PLANNED -> "No planned goals. Create one, or check the other filters."
                            GoalFilter.COMPLETED -> "No completed goals yet."
                            GoalFilter.ARCHIVED -> "Nothing is archived."
                            GoalFilter.ALL -> "No goals yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(hub.cards, key = { it.goal.id }) { card ->
                        GoalCard(card, dateFormatter, Modifier.animateItem(), onClick = { onOpen(card.goal.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: GoalFilter, onSelect: (GoalFilter) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(listOf(
            GoalFilter.ACTIVE to "Active", GoalFilter.PLANNED to "Planned",
            GoalFilter.COMPLETED to "Completed", GoalFilter.ARCHIVED to "Archived", GoalFilter.ALL to "All"
        )) { (filter, label) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun GoalCard(card: GoalCardState, dateFormatter: DateTimeFormatter, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val goal = card.goal
    val statusLabel = when (GoalStatus.fromStored(goal.status)) {
        GoalStatus.PLANNED -> "Planned"
        GoalStatus.ACTIVE -> "Active"
        GoalStatus.COMPLETED -> "Completed"
        GoalStatus.PAUSED -> "Paused"
        GoalStatus.CANCELLED -> "Cancelled"
    }
    val summary = when {
        card.totalTasks == 0 -> "No tasks yet"
        else -> "${card.completedTasks} of ${card.totalTasks} tasks"
    }
    val overdue = if (card.overdueTasks > 0) "${card.overdueTasks} overdue" else null
    val cardDescription = "${goal.title}. $statusLabel${if (goal.isArchived) ", archived" else ""}. " +
        "$summary${overdue?.let { ", $it" } ?: ""}"
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = cardDescription },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(goal.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(statusLabel, archived = goal.isArchived)
                Text(goal.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                goal.targetDate?.let {
                    Text("By ${it.format(dateFormatter)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            card.currentPhaseName?.let {
                Text("Current phase: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } ?: card.nextPhaseName?.let {
                Text("Next phase: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (overdue != null) {
                Text(overdue, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun StatusBadge(statusLabel: String, archived: Boolean) {
    val colors = when {
        archived -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        statusLabel == "Active" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        statusLabel == "Completed" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        statusLabel + if (archived) " · archived" else "",
        style = MaterialTheme.typography.labelSmall,
        color = colors.second,
        modifier = Modifier.heightIn(min = 24.dp).background(color = colors.first, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun GoalsEmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.heightIn(min = 48.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Plan something meaningful", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Break a long-term goal into phases and tasks, then track what you actually finish — " +
                "not just how much time has passed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        SuggestionChip(onClick = onAdd, label = { Text("Create your first goal") })
    }
}

@Composable
internal fun rememberRowDateFormatter(): DateTimeFormatter =
    remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()) }
