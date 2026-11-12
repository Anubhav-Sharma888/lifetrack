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

    @Test fun todayCanScrollFromTheHeaderToActivitiesOnAShortScreen() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(1))
        val state = LifeTrackState(habits = listOf(habit), today = today, loaded = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(320.dp)) {
                    TodayScreen(state, vm, onAdd = {}, onOpen = {})
                }
            }
        }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("LifeTrack")))
            .performScrollToNode(hasText("Activities"))
        compose.onNodeWithText("Activities").assertIsDisplayed()
    }

    @Test fun reorderHasANonDragMenu() {
        val habits = listOf(Habit(id = 1, name = "Read", sortOrder = 0), Habit(id = 2, name = "Walk", sortOrder = 1))
        compose.setContent { MaterialTheme { TodayScreen(LifeTrackState(habits = habits, loaded = true), vm, onAdd = {}, onOpen = {}) } }
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
}
