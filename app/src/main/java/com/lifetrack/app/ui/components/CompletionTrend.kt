package com.lifetrack.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.domain.Analytics
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Interactive Vico chart: horizontal scroll and pinch zoom are provided by CartesianChartHost. */
@Composable
fun CompletionTrend(habit: Habit, logs: List<HabitLog>, today: LocalDate, modifier: Modifier = Modifier, pauses: List<HabitPause> = emptyList()) {
    val end = minOf(today, habit.archivedOn ?: today)
    val start = maxOf(habit.startDate, end.minusDays(89))
    val dates = remember(start, end) {
        if (start > end) emptyList() else (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
    }
    val rates = remember(habit, logs, dates, pauses) {
        val relevant = logs.filter { it.habitId == habit.id && it.date >= start.minusDays(6) && it.date <= end }
        dates.map { date -> Analytics.rate(listOf(habit), relevant, maxOf(habit.startDate, date.minusDays(6)), date, pauses) }
    }
    val values = rates.map { it.fraction * 100f }
    var selectedIndex by remember(dates) { mutableIntStateOf((dates.size - 1).coerceAtLeast(0)) }
    val preferences = LocalUiPreferences.current
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values, dates) {
        if (values.size >= 2) producer.runTransaction {
            lineSeries { series(x = dates.map { Analytics.dayNumber(habit, it) ?: 1L }, y = values) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Completion trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("7-day rolling rate · up to 90 days · drag or pinch to explore", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.size < 2) {
            Text(if (values.isEmpty()) {
                if (habit.isArchived) "Archived before any scheduled days." else "Your timeline begins on ${habit.startDate}."
            } else "${if (habit.isArchived) "Final day" else "Today"}: ${values.last().toInt()}%. Your trend appears after your second day.",
                modifier = Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            val line = LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(MaterialTheme.colorScheme.primary))
            )
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(line),
                        rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = 100.0)
                    ),
                    startAxis = VerticalAxis.rememberStart(valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}%" }),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = CartesianValueFormatter { _, value, _ -> "D${value.toInt()}" })
                ),
                modelProducer = producer,
                animationSpec = if (preferences.animationsEnabled) spring() else null,
                animateIn = preferences.animationsEnabled,
                modifier = Modifier.fillMaxWidth().height(220.dp).semantics {
                    contentDescription = "Completion trend from Day ${Analytics.dayNumber(habit, start)} to Day ${Analytics.dayNumber(habit, end)}. Latest seven-day completion rate ${values.last().toInt()} percent."
                }
            )
            val selectedDate = dates[selectedIndex]
            val selectedRate = rates[selectedIndex]
            Text("$selectedDate · Day ${Analytics.dayNumber(habit, selectedDate)} · " +
                if (selectedRate.opportunities == 0L) "No scheduled opportunities in this 7-day window"
                else "${selectedRate.percent}% · ${selectedRate.completed}/${selectedRate.opportunities} completed over 7 days",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = selectedIndex.toFloat(), onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, dates.lastIndex) },
                valueRange = 0f..dates.lastIndex.toFloat(), steps = (dates.size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Inspect completion trend day"
                    stateDescription = "$selectedDate, Day ${Analytics.dayNumber(habit, selectedDate)}, " +
                        if (selectedRate.opportunities == 0L) "no scheduled opportunities" else "${selectedRate.percent} percent"
                })
        }
    }
}
