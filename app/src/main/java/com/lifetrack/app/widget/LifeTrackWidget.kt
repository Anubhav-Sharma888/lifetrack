package com.lifetrack.app.widget

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lifetrack.app.MainActivity
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitRepository
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.domain.Analytics
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entry point for Android-created callbacks and workers, whose constructors Hilt does not own. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundRepositoryEntryPoint {
    fun habitRepository(): HabitRepository
}

fun backgroundRepository(context: Context): HabitRepository =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        BackgroundRepositoryEntryPoint::class.java,
    ).habitRepository()

/** No copy of the database is kept in widget preferences. Every update reads Room. */
class LifeTrackWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val snapshot = backgroundRepository(context).readSnapshot(today)
        val habits = snapshot.habits
            .filter { !it.isArchived && Analytics.eligible(it, today, snapshot.pauses) }
            .sortedBy { it.sortOrder }
        val statuses = snapshot.logs
            .filter { it.date == today }
            .associate { it.habitId to it.status }
        provideContent {
            GlanceTheme {
                WidgetContent(habits, statuses, today)
            }
        }
    }
}

@Composable
private fun WidgetContent(
    habits: List<Habit>,
    statuses: Map<Long, LogStatus>,
    today: LocalDate,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(16.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    "LifeTrack",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                Text(
                    "$today · ${habits.count { statuses[it.id] == LogStatus.COMPLETED }}/${habits.size} complete",
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
            Text(
                "↗",
                style = TextStyle(fontSize = 22.sp, color = GlanceTheme.colors.primary),
            )
        }
        Spacer(GlanceModifier.height(10.dp))
        if (habits.isEmpty()) {
            Text(
                "A little progress starts here. Open LifeTrack to add a habit.",
                modifier = GlanceModifier.fillMaxWidth()
                    .clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(habits, itemId = { it.id }) { habit ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = GlanceModifier.defaultWeight()
                                .clickable(actionStartActivity<MainActivity>()),
                        ) {
                            Text(
                                "${habit.icon}  ${habit.name}",
                                maxLines = 1,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = GlanceTheme.colors.onSurface,
                                ),
                            )
                            val day = ChronoUnit.DAYS.between(habit.startDate, today) + 1
                            Text(
                                "Day $day" + when (statuses[habit.id]) {
                                    LogStatus.SKIPPED -> " · skipped"
                                    LogStatus.FAILED -> " · missed"
                                    else -> ""
                                },
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                ),
                            )
                        }
                        if (statuses[habit.id] == LogStatus.COMPLETED) {
                            Text(
                                "✓ Done",
                                modifier = GlanceModifier.padding(12.dp),
                                style = TextStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = GlanceTheme.colors.primary,
                                ),
                            )
                        } else {
                            Button(
                                text = "Check in",
                                onClick = actionRunCallback<CompleteHabitAction>(
                                    actionParametersOf(
                                        CompleteHabitAction.HabitId to habit.id,
                                        CompleteHabitAction.HabitCreatedAt to habit.createdAt,
                                        CompleteHabitAction.DisplayedDate to today.toEpochDay(),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

class LifeTrackWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LifeTrackWidget()
}

class CompleteHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[HabitId] ?: return
        val habitCreatedAt = parameters[HabitCreatedAt] ?: return
        val displayedDate = parameters[DisplayedDate] ?: return
        val today = LocalDate.now()
        if (displayedDate != today.toEpochDay()) {
            // A stale button must never check in a different day from the one shown to the user.
            refreshWidgets(context)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "A new day has started. Your widget is refreshed.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            backgroundRepository(context).completeHabit(habitId, today, expectedCreatedAt = habitCreatedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Open LifeTrack to review this habit.", Toast.LENGTH_SHORT).show()
            }
        }
        refreshWidgets(context)
    }

    companion object {
        val HabitId = ActionParameters.Key<Long>("habit_id")
        val HabitCreatedAt = ActionParameters.Key<Long>("habit_created_at")
        val DisplayedDate = ActionParameters.Key<Long>("displayed_date")
    }
}

suspend fun refreshWidgets(context: Context) {
    LifeTrackWidget().updateAll(context.applicationContext)
}
