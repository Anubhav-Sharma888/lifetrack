package com.lifetrack.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitRepository
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import com.lifetrack.app.data.WorkoutActivity
import com.lifetrack.app.domain.Achievements
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class AnalyticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 26)
    // Same vm fixture idiom as EditorRecoveryTest, for the screens that take a HabitViewModel
    // parameter (ActivitiesScreen, SchedulesScreen). They render from the static state; the vm
    // supplies busy/derived flows and the database used by the clear-entry confirmation flow.
    private lateinit var db: AppDatabase
    private lateinit var vm: HabitViewModel
    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        vm = HabitViewModel(HabitRepository(db, context), com.lifetrack.app.reminder.DailyReminderStore(context), context)
    }
    @After fun close() { vm.viewModelScope.cancel(); db.close() }

    @Test fun emptyNarrowScreenShowsUsefulStatesAndCanReachEverySection() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        AnalyticsScreen(emptyList(), emptyList(), today)
                    }
                }
            }
        }

        compose.onNodeWithText("Analytics").assertIsDisplayed()
        compose.onNodeWithText("No habits scheduled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All-time rate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("You vs. you").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your first check-in starts the picture.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your next check-in can start a new streak.").performScrollTo().assertIsDisplayed()
        // Prompt 9 renders the achievements summary row unconditionally: while the flow is still
        // empty it truthfully shows the catalogue fallback ("0 / 13" milestones, "0%" earned)
        // instead of hiding. The rings themselves still render "—" for 0/0, so the old global
        // "no 0% anywhere" assertion is scoped to the ring boxes: each ring's contentDescription
        // carries "no unskipped habits due" exactly when its rate has no opportunities, so this
        // scope can never pass vacuously.
        compose.onAllNodes(hasText("0%").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("—").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(3)
        compose.onNodeWithText("0 / ${Achievements.catalogue.size}").assertExists()
        compose.onNodeWithText("0%").assertExists()
    }

    @Test fun futureHabitDoesNotShowAsMissedInTodayRing() {
        val planned = Habit(id = 1, name = "Learn piano", startDate = today.plusDays(7))
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        AnalyticsScreen(listOf(planned), emptyList(), today)
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This week: no unskipped habits due").assertExists()
        // Future days never count: all three rings read "—", and only the truthful summary
        // carries "0%" (the unconditional Prompt 9 achievements row with its catalogue fallback).
        compose.onAllNodes(hasText("0%").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("—").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(3)
        compose.onNodeWithText("0 / ${Achievements.catalogue.size}").assertExists()
        compose.onNodeWithText("0%").assertExists()
    }

    @Test fun quotaHabitsStayOutOfDailyRatesAndRenderWithoutCrash() {
        val quota = Habit(id = 1, name = "Gym", scheduleMode = ScheduleMode.WEEKLY_QUOTA.name,
            weeklyTarget = 3, startDate = today.minusDays(10))
        val logs = listOf(HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED))
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) { AnalyticsScreen(listOf(quota), logs, today) }
                }
            }
        }

        compose.onNodeWithText("Analytics").assertIsDisplayed()
        // The quota completion never enters a daily ring: nothing is due on any fixed day.
        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This week: no unskipped habits due").assertExists()
        // Quota completions stay out of daily rates entirely, so the rings still read "—";
        // the only "0%" on screen is the truthful unconditional achievements summary.
        compose.onAllNodes(hasText("0%").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("—").and(hasAnyAncestor(hasContentDescription("no unskipped habits due", substring = true))))
            .assertCountEquals(3)
        compose.onNodeWithText("0 / ${Achievements.catalogue.size}").assertExists()
        compose.onNodeWithText("0%").assertExists()
    }

    @Test fun activitiesRecentDaysRenderAsLazyItems() {
        val activity = WorkoutActivity(id = 1, name = "Push-ups", unit = "reps", sortOrder = 0)
        val entry = ActivityEntry(id = 1, activityId = 1, date = today, quantity = 30.0)
        val state = LifeTrackState(activities = listOf(activity), activityEntries = listOf(entry), today = today, loaded = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(600.dp)) { ActivitiesScreen(state, vm) }
            }
        }

        // The C2 LazyColumn conversion must not have dropped the dated recent section: the
        // heading renders and the entry's quantity line is reachable inside the lazy list.
        compose.onNodeWithText("Recent activity by date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Push-ups · 30 reps").performScrollTo().assertIsDisplayed()
    }

    @Test fun schedulesScreenUsesTopAppBarAndStateToday() {
        val quota = Habit(id = 1, name = "Gym", scheduleMode = ScheduleMode.WEEKLY_QUOTA.name,
            weeklyTarget = 3, startDate = today.minusDays(10))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(600.dp)) {
                    SchedulesScreen(LifeTrackState(habits = listOf(quota), today = today, loaded = true),
                        vm, onBack = {}, onEdit = {})
                }
            }
        }

        // The screen title lives only in the TopAppBar now; a second body occurrence would make
        // this lookup ambiguous. The body keeps just the explaining subline.
        compose.onNodeWithText("Schedules & Reminders").assertExists()
        compose.onNodeWithText("Choose when your habits are due and when you get a notification.").assertExists()
        compose.onNodeWithText("Daily reminder").assertExists()
        // The quota card's weekly summary is computed from state.today, not from a stored label.
        compose.onNodeWithText("3 sessions per week · Monday through Sunday").performScrollTo().assertExists()
        compose.onNodeWithText("This week: 0 of 3 · 3 sessions to go").performScrollTo().assertExists()
    }

    @Test fun clearActivityEntryRequiresConfirmation() {
        val activity = WorkoutActivity(id = 1, name = "Push-ups", unit = "reps", sortOrder = 0)
        val entry = ActivityEntry(id = 1, activityId = 1, date = today, quantity = 30.0)
        runBlocking { db.dao().upsertActivity(activity); db.dao().upsertActivityEntry(entry) }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(600.dp)) {
                    ActivitiesScreen(LifeTrackState(activities = listOf(activity), activityEntries = listOf(entry),
                        today = today, loaded = true), vm)
                }
            }
        }

        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Clear entry").performClick()
        // Arming happens in place inside the same dialog window: an error-colored warning
        // appears above the note field and the trigger swaps to "Confirm clear". No second
        // window is opened — any second window stacked on a platform AlertDialog wedges the
        // Robolectric recomposer (both the platform-overlay and custom-overlay variants did).
        compose.onNodeWithText("removed. This cannot be undone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Confirm clear").assertExists()
        compose.onNodeWithText("Clear entry").assertDoesNotExist() // the trigger swapped in place
        // Nothing is erased before the confirmation: the seeded entry is still on disk.
        assertTrue(runBlocking { db.dao().activityEntry(1L, today) != null })
        compose.onNodeWithText("Confirm clear").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            // Room resumes its suspended caller through Android's main Handler; PAUSED mode
            // deliberately needs that queue driven while waiting for an external database task.
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { db.dao().activityEntry(1L, today) == null }
        }
        compose.onNodeWithText("Confirm clear").assertDoesNotExist()
        compose.onNodeWithText("Clear entry").assertDoesNotExist() // the log dialog closed with the save
        assertNull(runBlocking { db.dao().activityEntry(1L, today) })
    }

    @Test fun recordingStripCellsExposeButtonRoleAndTheirDay() {
        // Component-contract render (same isolation as EditorRecoveryTest's NextMilestoneCard):
        // the strip's day cells are Role.Button toggles that announce the full date, the
        // selection and the recording state — today is the fixture's selected-and-empty day.
        var chosen: LocalDate? = null
        compose.setContent {
            MaterialTheme {
                RecordingStrip(selected = today, today = today, completedDates = emptySet(),
                    onSelect = { chosen = it })
            }
        }
        val selectedCell = compose.onNodeWithContentDescription("Monday, 26 October · selected day · no recordings yet")
        selectedCell.assertExists().assertHasClickAction()
        assertEquals(Role.Button, selectedCell.fetchSemanticsNode().config[SemanticsProperties.Role])
        // The earliest cell announces the same contract without the selection.
        val earliestCell = compose.onNodeWithContentDescription("Tuesday, 20 October · no recordings yet")
        earliestCell.assertExists().assertHasClickAction()
        assertEquals(Role.Button, earliestCell.fetchSemanticsNode().config[SemanticsProperties.Role])
        // Tapping a day switches the selected date (TodayScreen's date-selection contract).
        earliestCell.performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 20), chosen) }
    }
}
