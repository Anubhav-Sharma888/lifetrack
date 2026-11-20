package com.lifetrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.domain.Analytics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Density is across all eligible habits; single-habit check-ins are edited in the detail log. */
@Composable
fun HeatmapWidget(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, modifier: Modifier = Modifier, pauses: List<HabitPause> = emptyList()) {
    var selected by remember(today) { mutableStateOf(today) }
    val start = remember(today) { Analytics.weekStart(today).minusWeeks(52) }
    val daily = remember(habits, logs, start, today, pauses) {
        val logsByDate = logs.groupBy { it.date }
        (0..370).associate { offset -> start.plusDays(offset.toLong()).let { it to Analytics.density(habits, logsByDate[it].orEmpty(), it, pauses) } }
    }
    val scroll = rememberScrollState()
    // Make the step-through claim true: every selection change brings that day's week column
    // into view (18dp cell + 4dp column spacing = one column per week). One effect owns the
    // scroll: it snaps once on the first real layout (selected == today, the old end-state)
    // and animates on every later selection or range change — two competing effects on the
    // same scroll state flickered on relayout.
    val perWeekPx = with(LocalDensity.current) { 18.dp.toPx() + 4.dp.toPx() }
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(selected, scroll.maxValue) {
        if (scroll.maxValue == 0) return@LaunchedEffect
        val index = ((selected.toEpochDay() - start.toEpochDay()) / 7).toInt().coerceIn(0, 52)
        val target = (index * perWeekPx).toInt().coerceAtMost(scroll.maxValue)
        if (!positioned) {
            scroll.scrollTo(target)
            positioned = true
        } else {
            scroll.animateScrollTo(target)
        }
    }
    val primary = MaterialTheme.colorScheme.secondary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val selection = daily[selected] ?: Analytics.Rate(0, 0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your consistency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Every small square is a step forward.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // E1: one accessibility node for the whole 53x7 grid — the per-cell descriptions and
        // buttons flooded TalkBack with ~370 stops. Touch behaviour is unchanged; TalkBack users
        // step through days with the prev/next buttons and readout below.
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).clearAndSetSemantics {
            contentDescription = "Year of daily completion squares. Use the buttons below to step through days."
        }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(53) { week ->
                val monday = start.plusWeeks(week.toLong())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (week == 0 || monday.month != monday.minusWeeks(1).month) monday.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())) else "",
                        modifier = Modifier.width(18.dp).height(18.dp), style = MaterialTheme.typography.labelSmall, softWrap = false)
                    repeat(7) { day ->
                        val date = monday.plusDays(day.toLong())
                        val density = daily.getValue(date)
                        val alpha = when {
                            density.fraction <= 0 -> 0f
                            density.fraction <= .25f -> .28f
                            density.fraction <= .5f -> .48f
                            density.fraction <= .75f -> .72f
                            else -> 1f
                        }
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (date > today) Color.Transparent else if (alpha == 0f) empty else primary.copy(alpha = alpha))
                            .border(if (date == selected) 2.dp else 0.dp, if (date == selected) MaterialTheme.colorScheme.tertiary else Color.Transparent, RoundedCornerShape(2.dp))
                            .clickable(enabled = date <= today, role = Role.Button) { selected = date })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(selected.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.labelLarge)
                Text(if (selection.opportunities == 0L) "No habits scheduled" else "${selection.completed}/${selection.opportunities} completed · ${selection.percent}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = selected > start, onClick = { selected = selected.minusDays(1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Inspect previous heatmap day")
            }
            IconButton(enabled = selected < today, onClick = { selected = selected.plusDays(1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Inspect next heatmap day")
            }
        }
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Less", style = MaterialTheme.typography.labelSmall)
                listOf(0f, .28f, .48f, .72f, 1f).forEach { alpha ->
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (alpha == 0f) empty else primary.copy(alpha = alpha)))
                }
                Text("More", style = MaterialTheme.typography.labelSmall)
        }
    }
}
