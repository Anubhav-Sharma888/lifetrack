package com.lifetrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.ui.components.HeatmapWidget
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause> = emptyList(),
                    activities: List<com.lifetrack.app.data.WorkoutActivity> = emptyList(),
                    activityEntries: List<com.lifetrack.app.data.ActivityEntry> = emptyList()) {
    val active = habits.filterNot { it.isArchived }
    val first = habits.minOfOrNull { it.startDate } ?: today
    val allTime = remember(habits, logs, today, pauses) { Analytics.rate(habits, logs, first, today, pauses) }
    val leaderboard = remember(habits, logs, today, pauses) {
        active.map { it to Analytics.currentStreak(it, logs, today, pauses) }.filter { it.second > 0 }.sortedByDescending { it.second }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Analytics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("You are your only competition.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnalyticsPanel { HeatmapWidget(habits, logs, today, pauses = pauses) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Active habits", active.size.toString(), Modifier.weight(1f))
            MetricCard("All-time rate", if (allTime.opportunities == 0L) "—" else "${allTime.percent}%", Modifier.weight(1f))
        }
        AnalyticsPanel {
            Text("A little, often", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            val periods = listOf(
                "Today" to Analytics.rate(habits, logs, today, today, pauses),
                "This week" to Analytics.rate(habits, logs, Analytics.weekStart(today), today, pauses),
                "This month" to Analytics.rate(habits, logs, today.withDayOfMonth(1), today, pauses)
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.4f) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        periods.forEach { (label, rate) -> ProgressRing(label, rate) }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    periods.forEach { (label, rate) -> ProgressRing(label, rate) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Rates exclude skips, paused dates and unscheduled weekdays. Heatmap shows completed / all scheduled habits. Future days never count.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ComparisonCard(habits, logs, today, pauses)
        AnalyticsPanel { CategoryDonut(habits, logs, today, pauses) }
        AnalyticsPanel { WeeklyBars(habits, logs, today, pauses) }
        if (activities.isNotEmpty()) AnalyticsPanel {
            Text("Activities · last 28 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val window = today.minusDays(27)
            activities.sortedBy { it.sortOrder }.forEach { activity ->
                val inWindow = activityEntries.filter { it.activityId == activity.id && it.date in window..today }
                val total = inWindow.sumOf { it.quantity }
                val best = inWindow.maxOfOrNull { it.quantity } ?: 0.0
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(activity.icon)
                    Column(Modifier.weight(1f)) {
                        Text(activity.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${activity.unit}${activity.target?.let { " · target ${if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()}/day" } ?: ""} · ${inWindow.size} day${if (inWindow.size == 1) "" else "s"} logged",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (total == total.toLong().toDouble()) total.toLong().toString() else total.toString(),
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Actual quantities, recorded as performed — a best day above the target is a good day, never an overshoot.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnalyticsPanel {
            Text("Streak leaderboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (leaderboard.isEmpty()) Text("Your next check-in can start a new streak.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            leaderboard.forEachIndexed { index, (habit, streak) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(habit.icon)
                    Text(habit.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$streak ${if (streak == 1) "day" else "days"}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Streaks count completions; skips and freezes protect the chain without adding a day.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun AnalyticsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    AnalyticsPanel(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressRing(label: String, rate: Analytics.Rate) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(if (LocalDensity.current.fontScale > 1.4f) 104.dp else 74.dp).semantics { contentDescription = if (rate.opportunities == 0L) "$label: no unskipped habits due" else "$label: ${rate.percent} percent, ${rate.completed} of ${rate.opportunities} completed" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = 7.dp.toPx()
                drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                if (rate.fraction > 0f) drawArc(primary, -90f, 360f * rate.fraction, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(if (rate.opportunities == 0L) "—" else "${rate.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(if (rate.opportunities == 0L) (if (rate.skipped > 0L) "All skipped" else "None due") else "${rate.completed}/${rate.opportunities}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComparisonCard(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause>) {
    val week = Analytics.weekStart(today)
    val elapsed = ChronoUnit.DAYS.between(week, today)
    val lastMonth = today.minusMonths(1).withDayOfMonth(1)
    val lastMonthEnd = lastMonth.withDayOfMonth(minOf(today.dayOfMonth, lastMonth.lengthOfMonth()))
    val thisWeek = Analytics.rate(habits, logs, week, today, pauses)
    val previousWeek = Analytics.rate(habits, logs, week.minusWeeks(1), week.minusWeeks(1).plusDays(elapsed), pauses)
    val thisMonth = Analytics.rate(habits, logs, today.withDayOfMonth(1), today, pauses)
    val previousMonth = Analytics.rate(habits, logs, lastMonth, lastMonthEnd, pauses)
    AnalyticsPanel {
        Text("You vs. you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Same elapsed days in each period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        ComparisonRow("This week", thisWeek, previousWeek, "last week")
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        ComparisonRow("This month", thisMonth, previousMonth, "last month")
    }
}

@Composable
private fun ComparisonRow(label: String, current: Analytics.Rate, previous: Analytics.Rate, previousLabel: String) {
    val difference = current.percent - previous.percent
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (previous.opportunities == 0L) "No comparable $previousLabel history" else "${previous.percent}% $previousLabel",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (current.opportunities == 0L) "—" else "${current.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (previous.opportunities > 0L && current.opportunities > 0L) Text(
                when { difference > 0 -> "+$difference pp"; difference < 0 -> "−${abs(difference)} pp"; else -> "Steady" },
                style = MaterialTheme.typography.labelSmall, color = if (difference >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val categoryPalette = listOf(Color(0xFF477A60), Color(0xFF729DB2), Color(0xFFB482A8), Color(0xFFD5A55F), Color(0xFF8A83B5), Color(0xFF78A991))

@Composable
private fun CategoryDonut(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause>) {
    val categories = remember(habits, logs, today, pauses) {
        val byId = habits.associateBy { it.id }
        logs.filter { it.status == LogStatus.COMPLETED && it.date in today.minusDays(27)..today && byId[it.habitId]?.let { habit -> Analytics.eligible(habit, it.date, pauses) } == true }
            .groupingBy { byId.getValue(it.habitId).category }.eachCount().toList().sortedByDescending { it.second }
    }
    val total = categories.sumOf { it.second }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Text("Where your energy goes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("Completions by category · last 28 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.4f) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CategoryDonutGraphic(categories, total, track)
                CategoryLegend(categories, Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CategoryDonutGraphic(categories, total, track)
            CategoryLegend(categories, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoryDonutGraphic(categories: List<Pair<String, Int>>, total: Int, track: Color) {
    Box(Modifier.size(if (LocalDensity.current.fontScale > 1.4f) 170.dp else 132.dp).semantics {
        contentDescription = "Category chart, $total total completions in 28 days"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val stroke = Stroke(20.dp.toPx())
            if (total == 0) drawArc(track, 0f, 360f, false, style = stroke)
            var angle = -90f
            categories.forEachIndexed { index, (_, count) ->
                val sweep = 360f * count / total
                drawArc(categoryPalette[index % categoryPalette.size], angle,
                    (sweep - if (categories.size > 1) 3f else 0f).coerceAtLeast(0f), false, style = stroke)
                angle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(total.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("check-ins", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CategoryLegend(categories: List<Pair<String, Int>>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (categories.isEmpty()) Text("Your first check-in starts the picture.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        categories.forEachIndexed { index, (category, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(9.dp).background(categoryPalette[index % categoryPalette.size], RoundedCornerShape(3.dp)))
                Text(category, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WeeklyBars(habits: List<Habit>, logs: List<HabitLog>, today: LocalDate, pauses: List<HabitPause>) {
    val weeks = remember(habits, logs, today, pauses) { Analytics.weeklyVolumes(habits, logs, today, pauses) }
    var selected by remember(today) { mutableIntStateOf(7) }
    val maximum = (weeks.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    Text("Showing up, week by week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("Last 8 weeks · this week is in progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text("Week of ${weeks[selected].first.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}: ${weeks[selected].second} completions",
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
        weeks.forEachIndexed { index, (date, count) ->
            Column(Modifier.weight(1f).semantics { contentDescription = "Week of $date, $count completions" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth(.72f).height(maxOf(4f, 110f * count / maximum).dp)
                        .background(primary.copy(alpha = if (selected == index) 1f else .38f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                }
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
    Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt().coerceIn(0, weeks.lastIndex) },
        valueRange = 0f..weeks.lastIndex.toFloat(), steps = weeks.size - 2,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Inspect weekly completion volume"
            stateDescription = "Week of ${weeks[selected].first}, ${weeks[selected].second} completions"
        })
}
