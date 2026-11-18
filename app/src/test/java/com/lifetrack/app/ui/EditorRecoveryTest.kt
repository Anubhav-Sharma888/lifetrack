package com.lifetrack.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.lifetrack.app.data.*
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class EditorRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: HabitViewModel
    private val today = LocalDate.now()
    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        vm = HabitViewModel(HabitRepository(db, context), com.lifetrack.app.reminder.DailyReminderStore(context), context)
    }
    @After fun close() { vm.viewModelScope.cancel(); db.close() }

    @Test fun aValidRestored120CharacterNameCanBeSavedWithoutRenaming() {
        val habit = Habit(id = 1, name = "A".repeat(120), startDate = today)
        val state = LifeTrackState(habits = listOf(habit), today = today, loaded = true)
        compose.setContent { MaterialTheme { HabitEditorScreen(1, state, vm) {} } }
        compose.onNodeWithText("120/120").assertExists()
        compose.onNodeWithText("Save habit").assertIsEnabled()
    }

    @Test fun dirtyBackNavigationOffersToKeepTheDraft() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        var exits = 0
        compose.setContent { MaterialTheme { HabitEditorScreen(1, LifeTrackState(habits = listOf(habit), loaded = true), vm) { exits++ } } }
        compose.onNode(hasSetTextAction() and hasText("Habit name")).performTextReplacement("Read another chapter")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Keep your changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Read another chapter").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun noteDraftSurvivesRestorationAndAFailedWriteDoesNotDismissIt() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(1), isArchived = true, archivedOn = today)
        runBlocking { db.dao().insertHabit(habit) }
        val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.SKIPPED, note = "Original")
        var dismissals = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LogEditorDialog(habit, today, log, vm, onDismiss = { dismissals++ }, initialStatus = log.status) } }
        compose.onNode(hasSetTextAction() and hasText("Note · optional")).performTextReplacement("Keep this draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            // Room resumes its suspended caller through Android's main Handler; PAUSED mode
            // deliberately needs that queue driven while waiting for an external database task.
            shadowOf(Looper.getMainLooper()).idle()
            vm.operationError.value != null
        }
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithText(vm.operationError.value!!).assertExists()
        compose.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test fun noteOnlyEditingDefaultsToTheExistingFailedStatus() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.FAILED, note = "Keep the status")
        compose.setContent { MaterialTheme { LogEditorDialog(habit, today, log, vm, onDismiss = {}) } }
        compose.onNodeWithText("Missed · breaks chain").assertIsSelected()
        compose.onNodeWithText("Keep the status").assertExists()
    }

    @Test fun goalEditsAreEnabledAndExplainWhenTheyStart() {
        val habit = Habit(id = 1, name = "Water", startDate = today, targetCount = 8, targetUnit = "glasses")
        val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED, progress = 8)
        compose.setContent { MaterialTheme { HabitEditorScreen(1, LifeTrackState(habits = listOf(habit), logs = listOf(log), loaded = true), vm) {} } }
        compose.onNodeWithText("Daily target").assertIsEnabled()
        compose.onNodeWithText("Goal changes start tomorrow. Existing check-ins keep their original goal.").assertExists()
    }

    @Test fun todayScrollsToTheReflectionInvitationOnAShortScreen() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(1))
        val state = LifeTrackState(habits = listOf(habit), today = today, loaded = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(320.dp)) {
                    TodayScreen(state, vm, onAdd = {}, onOpen = {})
                }
            }
        }
        // The reflection invitation sits below the header wells; scrolling must reach it.
        // Habit-list reachability is covered separately by reorderHasANonDragMenu's scroll.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("LifeTrack")))
            .performScrollToNode(hasText("How was the day?"))
        compose.onNodeWithText("How was the day?").assertIsDisplayed()
    }

    @Test fun reorderHasANonDragMenu() {
        val habits = listOf(Habit(id = 1, name = "Read", sortOrder = 0), Habit(id = 2, name = "Walk", sortOrder = 1))
        compose.setContent { MaterialTheme { TodayScreen(LifeTrackState(habits = habits, loaded = true), vm, onAdd = {}, onOpen = {}) } }
        // The reflection card sits above the habit list, so reorder controls may need scrolling into view.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("LifeTrack")))
            .performScrollToNode(hasContentDescription("Reorder Read"))
        compose.onNodeWithContentDescription("Reorder Read").performClick()
        compose.onNodeWithText("Move habit down").assertIsEnabled()
        compose.onNodeWithText("Move habit up").assertIsNotEnabled()
    }

    @Test fun settingsSwitchesHaveMeaningfulAccessibilityNames() {
        compose.setContent { MaterialTheme { SettingsScreen(LifeTrackState(loaded = true), vm) {} } }
        compose.onNodeWithContentDescription("Monthly backup reminder").assertExists()
        compose.onNodeWithContentDescription("Wallpaper colors").assertExists()
        compose.onNodeWithContentDescription("Haptic feedback").assertExists()
        compose.onNodeWithContentDescription("Animations and celebrations").assertExists()
    }

    @Test fun settingsOpensSchedulesAndReminders() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(LifeTrackState(loaded = true), vm, onSchedules = { opened = true }, onOpen = {})
            }
        }
        compose.onNodeWithText("Schedules & Reminders").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun habitScheduleRowShowsSavedReminderAndOpensEditor() {
        val habit = Habit(id = 7, name = "Read", scheduledDays = listOf(1, 3, 5), reminderMinutes = 480)
        var edited: Long? = null
        compose.setContent {
            MaterialTheme {
                SchedulesScreen(LifeTrackState(habits = listOf(habit), loaded = true), vm,
                    onBack = {}, onEdit = { edited = it })
            }
        }
        compose.onNodeWithText("Reminder at 08:00").assertExists()
        compose.onNodeWithContentDescription("Scheduled on Monday, Wednesday, Friday").assertExists()
        compose.onNodeWithText("Edit schedule").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(7L, edited) }
    }

    @Test fun scheduleModeSelectionsAndParameterDraftsSurviveRestoration() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { HabitEditorScreen(1, LifeTrackState(habits = listOf(habit), loaded = true), vm) {} }
        }
        compose.onNodeWithText("Every N days").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Interval · days (2–365)")).performScrollTo()
            .performTextReplacement("3")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Every N days").assertIsSelected()
        // The field's merged text carries the label plus the supporting sentence; the assertion
        // targets the editable draft ("3") among the merged values.
        compose.onNode(hasSetTextAction() and hasText("Interval · days (2–365)")).assertTextContains("3")

        compose.onNodeWithText("X times per week").performScrollTo().performClick()
        compose.onNodeWithText("Sessions per week (1–7)").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Sessions per week (1–7)")).performTextReplacement("5")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("X times per week").assertIsSelected()
        // Same merged-text caveat: the draft "5" sits among the label and supporting values.
        compose.onNode(hasSetTextAction() and hasText("Sessions per week (1–7)")).assertTextContains("5")
    }

    @Test fun quotaHabitMenuNeverOffersSkipOrMissed() {
        val now = LocalDate.of(2026, 10, 28) // Wednesday of a Monday-start week
        val quota = Habit(id = 1, name = "Gym", startDate = now.minusDays(10),
            scheduleMode = ScheduleMode.WEEKLY_QUOTA.name, weeklyTarget = 3)
        val weekday = Habit(id = 2, name = "Read", startDate = now.minusDays(10))
        val logs = listOf(
            HabitLog(id = 1, habitId = 1, date = now.minusDays(2), status = LogStatus.COMPLETED),
            HabitLog(id = 2, habitId = 1, date = now.minusDays(1), status = LogStatus.COMPLETED),
            // The weekday completion keeps yesterday's unmarked nudge quiet for this test.
            HabitLog(id = 3, habitId = 2, date = now.minusDays(1), status = LogStatus.COMPLETED)
        )
        compose.setContent {
            MaterialTheme {
                TodayScreen(LifeTrackState(habits = listOf(quota, weekday), logs = logs, today = now, loaded = true),
                    vm, onAdd = {}, onOpen = {})
            }
        }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Gym")))
            .performScrollToNode(hasText("This week: 2 of 3", substring = true))
        compose.onNodeWithText("This week: 2 of 3", substring = true).assertExists()
        // A quota habit never fabricates a daily overdue: no skip/missed quick actions, ever.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Gym")))
            .performScrollToNode(hasContentDescription("More actions for Gym"))
        compose.onNodeWithContentDescription("More actions for Gym").performClick()
        compose.onNodeWithText("Skip this day").assertDoesNotExist()
        compose.onNodeWithText("Mark as missed").assertDoesNotExist()
        compose.onNodeWithText("Edit check-in…").assertExists() // the menu really is open and rendered
    }

    @Test fun weekdayHabitMenuKeepsSkipAndMissedActions() {
        val now = LocalDate.of(2026, 10, 28) // Wednesday of a Monday-start week
        val weekday = Habit(id = 1, name = "Read", startDate = now.minusDays(10))
        compose.setContent {
            MaterialTheme {
                TodayScreen(LifeTrackState(habits = listOf(weekday), today = now, loaded = true),
                    vm, onAdd = {}, onOpen = {})
            }
        }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Read")))
            .performScrollToNode(hasContentDescription("More actions for Read"))
        compose.onNodeWithContentDescription("More actions for Read").performClick()
        // A weekday habit keeps its one-tap skip/missed quick actions.
        compose.onNodeWithText("Skip this day").assertExists()
        compose.onNodeWithText("Mark as missed").assertExists()
    }
}
