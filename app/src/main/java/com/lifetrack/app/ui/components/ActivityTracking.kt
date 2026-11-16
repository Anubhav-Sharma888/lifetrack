package com.lifetrack.app.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.ActivityMode
import com.lifetrack.app.data.WorkoutActivity
import com.lifetrack.app.domain.ProgressTracking
import com.lifetrack.app.ui.AlmanacType
import com.lifetrack.app.ui.formatQuantityLabel
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
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Net change spoken plainly: signed quantity with a true minus sign, or a flat phrase. */
private fun netChangeLabel(change: Double, unit: String): String = when {
    change > 0 -> "+${formatQuantityLabel(change)} $unit"
    change < 0 -> "−${formatQuantityLabel(-change)} $unit"
    else -> "no change so far"
}

/** Why a present goal produces no fraction; states the fix instead of implying patience. */
private fun illDefinedGoalLabel(mode: ActivityMode): String = when (mode) {
    ActivityMode.INCREASING -> "the goal must be above the starting value to measure progress"
    ActivityMode.DECREASING -> "the goal must be below the starting value to measure progress"
    ActivityMode.PERSONAL_BEST -> "the goal must be above zero to measure progress"
    ActivityMode.LOGGED -> "this goal does not define progress"
}

/** Headline label for the current standing; LOGGED never reaches the directional summary. */
private fun headlineLabel(mode: ActivityMode): String = when (mode) {
    ActivityMode.PERSONAL_BEST -> "Personal best"
    else -> "Current"
}

/** Mode noun used in chart subtitles and accessibility text. */
private fun modeNoun(mode: ActivityMode): String = when (mode) {
    ActivityMode.INCREASING -> "Positions"
    ActivityMode.DECREASING -> "Measurements"
    ActivityMode.PERSONAL_BEST -> "Attempts"
    ActivityMode.LOGGED -> "Daily quantities"
}

/** Sunken parchment summary of a directional activity: headline standing, change and goal captions. */
@Composable
fun TrackingSummary(activity: WorkoutActivity, entries: List<ActivityEntry>, modifier: Modifier = Modifier) {
    val mode = ProgressTracking.mode(activity)
    // LOGGED keeps the existing per-day UI; the directional summary renders nothing for it.
    if (mode == ActivityMode.LOGGED) return

    val unit = activity.unit
    val startingValue = activity.startingValue
    val current = ProgressTracking.current(activity, entries)
    val baseline = ProgressTracking.baseline(activity, entries)
    val fraction = ProgressTracking.progressFraction(activity, entries)
    val pendingFirstEntry = entries.isEmpty() && startingValue != null

    val headline = when {
        pendingFirstEntry -> "Start ${formatQuantityLabel(startingValue ?: 0.0)} $unit"
        current != null -> "${headlineLabel(mode)} ${formatQuantityLabel(current)} $unit"
        else -> "—"
    }
    val captions = buildList {
        when {
            pendingFirstEntry -> add("First entry pending")
            current != null -> {
                if (mode != ActivityMode.PERSONAL_BEST && baseline != null) {
                    add("From ${formatQuantityLabel(baseline)} $unit · ${netChangeLabel(current - baseline, unit)}")
                }
                if (mode == ActivityMode.PERSONAL_BEST) {
                    add("${if (entries.size == 1) "1 attempt" else "${entries.size} attempts"} logged")
                }
            }
            else -> add("No entries yet")
        }
        val target = activity.target
        // The bar clamps to 0..1, so the caption clamps at zero too; overshoot above 100% stays truthful.
        when {
            fraction != null -> add("Goal ${formatQuantityLabel(target ?: 0.0)} $unit · ${(fraction * 100f).roundToInt().coerceAtLeast(0)}% reached")
            target != null -> add("Goal ${formatQuantityLabel(target)} $unit — ${illDefinedGoalLabel(mode)}")
            else -> add("No goal set")
        }
    }
    // Plain-language version of the well for TalkBack: standing, change, then goal.
    val summary = "${activity.name}: " + buildString {
        when {
            pendingFirstEntry -> append("start ${formatQuantityLabel(startingValue ?: 0.0)} $unit, first entry pending")
            current != null -> {
                append("${headlineLabel(mode)} ${formatQuantityLabel(current)} $unit")
                if (mode != ActivityMode.PERSONAL_BEST && baseline != null) {
                    append(", from ${formatQuantityLabel(baseline)} $unit, ${netChangeLabel(current - baseline, unit)}")
                }
                if (mode == ActivityMode.PERSONAL_BEST) {
                    append(", ${if (entries.size == 1) "1 attempt" else "${entries.size} attempts"} logged")
                }
            }
            else -> append("no entries yet")
        }
        val target = activity.target
        append(when {
            fraction != null -> ", goal ${formatQuantityLabel(target ?: 0.0)} $unit, ${(fraction * 100f).roundToInt().coerceAtLeast(0)} percent reached"
            target != null -> ", goal ${formatQuantityLabel(target)} $unit, ${illDefinedGoalLabel(mode)}"
            else -> ", no goal set"
        })
    }

    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
            captions.forEach { caption ->
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            fraction?.let { value ->
                LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Chronological value trend for an activity, with a slider to read each dated entry. */
@Composable
fun ActivityTrend(activity: WorkoutActivity, entries: List<ActivityEntry>, modifier: Modifier = Modifier) {
    val mode = ProgressTracking.mode(activity)
    // At most the 90 latest entries by date; entries are unique per date so the cut is unambiguous.
    val points = remember(activity.id, entries) { entries.sortedBy { it.date }.takeLast(90) }
    val preferences = LocalUiPreferences.current
    val axisDateFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val best = remember(activity.id, entries) { entries.maxOfOrNull { it.quantity } }
    // PERSONAL_BEST headlines its record in the subtitle; other modes have nothing extra to add.
    val subtitleSuffix = if (mode == ActivityMode.PERSONAL_BEST && best != null) {
        " · best ${formatQuantityLabel(best)} ${activity.unit}"
    } else ""

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Value trend", style = MaterialTheme.typography.titleMedium)
        Text("${modeNoun(mode)}, oldest to newest$subtitleSuffix · drag or pinch to explore",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (points.size < 2) {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your trend appears after your second entry.", style = MaterialTheme.typography.bodyMedium)
                points.lastOrNull()?.let { latest ->
                    Text("${latest.date} · ${formatQuantityLabel(latest.quantity)} ${activity.unit}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(points) {
                producer.runTransaction {
                    lineSeries { series(x = points.map { it.date.toEpochDay() }, y = points.map { it.quantity }) }
                }
            }
            var selectedIndex by remember(points) { mutableIntStateOf(points.lastIndex) }
            val line = LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(MaterialTheme.colorScheme.secondary))
            )
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(line)),
                    startAxis = VerticalAxis.rememberStart(
                        valueFormatter = CartesianValueFormatter { _, value, _ -> formatQuantityLabel(value) }
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = CartesianValueFormatter { _, value, _ ->
                            LocalDate.ofEpochDay(value.toLong()).format(axisDateFormat)
                        }
                    )
                ),
                modelProducer = producer,
                animationSpec = if (preferences.animationsEnabled) spring() else null,
                animateIn = preferences.animationsEnabled,
                modifier = Modifier.fillMaxWidth().height(180.dp).semantics {
                    contentDescription = "${modeNoun(mode)} trend for ${activity.name} from ${points.first().date} " +
                        "to ${points.last().date}. Latest value ${formatQuantityLabel(points.last().quantity)} ${activity.unit}."
                }
            )
            val selected = points[selectedIndex]
            Text("${selected.date} · ${formatQuantityLabel(selected.quantity)} ${activity.unit}",
                style = MaterialTheme.typography.bodySmall)
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, points.lastIndex) },
                valueRange = 0f..points.lastIndex.toFloat(),
                steps = (points.size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Inspect ${activity.name} trend"
                    stateDescription = "${selected.date}, ${formatQuantityLabel(selected.quantity)} ${activity.unit}"
                }
            )
        }
    }
}

/** The four tracking modes with example-led titles and supporting text, one selectable row each.
 *  Rows use selectable() with a RadioButton role so TalkBack reads one focusable, selected-aware stop per mode. */
@Composable
fun TrackingModeSelector(selected: ActivityMode, onSelect: (ActivityMode) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tracking mode", style = MaterialTheme.typography.titleSmall)
        listOf(
            Triple(ActivityMode.LOGGED, "Daily log", "Quantity per day · push-ups, glasses of water"),
            Triple(ActivityMode.INCREASING, "Rising position", "Cumulative position toward a goal · book pages, savings"),
            Triple(ActivityMode.DECREASING, "Falling measure", "Measurement counting down · body weight, tasks remaining"),
            Triple(ActivityMode.PERSONAL_BEST, "Personal best", "Records · the best attempt ever wins")
        ).forEach { (mode, title, supporting) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = selected == mode, role = Role.RadioButton, enabled = enabled) { onSelect(mode) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
