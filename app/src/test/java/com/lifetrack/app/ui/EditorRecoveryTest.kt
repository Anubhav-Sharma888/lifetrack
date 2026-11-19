package com.lifetrack.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.lifetrack.app.data.*
import com.lifetrack.app.domain.Achievements
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

    @Test fun reflectionCardIsReachableBelowHabitCardsOnAShortScreen() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(1))
        val state = LifeTrackState(habits = listOf(habit), today = today, loaded = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(320.dp)) {
                    TodayScreen(state, vm, onAdd = {}, onOpen = {})
                }
            }
        }
        // Prompt 9 moved the reflection card BELOW the habit items (tasks before context), with
        // the milestone hint between them and the activity section last. Let the vm's derived
        // achievements flow settle first (it inserts the milestone item), then walk the card
        // into view with the bounded swipe-and-probe loop below.
        compose.waitUntil(timeoutMillis = 5000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.achievements.value.isNotEmpty()
        }
        compose.waitForIdle()
        // Bounded manual-scroll probe instead of performScrollToNode: on the 320dp viewport the
        // lazy list recomposes mid-scroll (items compose and dispose around the viewport while
        // the scroll-to holds a node reference), which kept invalidating the held node. Each
        // round probes the settled tree first and only swipes while the invitation's center is
        // still outside the list viewport. The swipe is short and slow on purpose: the default
        // swipeUp() flings a full viewport per gesture (~300ms of decay), so a single round
        // could scroll the target's center clean across the probed window.
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("LifeTrack")))
        val viewport = list.fetchSemanticsNode().boundsInRoot
        var reached = false
        repeat(10) {
            val invitation = compose.onAllNodes(hasText("How was the day?")).fetchSemanticsNodes().firstOrNull()
            val center = invitation?.boundsInRoot?.center
            if (center != null && center.y >= viewport.top && center.y <= viewport.bottom) {
                reached = true
            } else {
                // Node not resolvable (or out of view): scroll from the list viewport's midpoint.
                list.performTouchInput { swipeUp(centerY + 60f, centerY - 60f, 800L) } // 120dp, slow: no long fling
                compose.waitForIdle()
            }
        }
        assertTrue("The reflection invitation never came into view after 10 bounded swipes", reached)
        compose.onNodeWithText("How was the day?").assertExists()
    }

    @Test fun reorderHasANonDragMenu() {
        val habits = listOf(Habit(id = 1, name = "Read", sortOrder = 0), Habit(id = 2, name = "Walk", sortOrder = 1))
        compose.setContent { MaterialTheme { TodayScreen(LifeTrackState(habits = habits, loaded = true), vm, onAdd = {}, onOpen = {}) } }
        // Habit cards sit below the header wells (the reflection card and milestone hint come
        // after them), so reorder controls may need scrolling into view.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("LifeTrack")))
            .performScrollToNode(hasContentDescription("Reorder Read"))
        compose.onNodeWithContentDescription("Reorder Read").performClick()
        compose.onNodeWithText("Move habit down").assertIsEnabled()
        compose.onNodeWithText("Move habit up").assertIsNotEnabled()
    }

    @Test fun settingsSwitchesHaveMeaningfulAccessibilityNames() {
        compose.setContent { MaterialTheme { SettingsScreen(LifeTrackState(loaded = true), vm) {} } }
        // E4 replaced the per-switch contentDescription with the merged toggleable-row idiom:
        // the row merges its title text, Role.Switch and the toggle action into one node, so
        // TalkBack hears the title followed by "switch" from the row itself. There is no
        // per-switch contentDescription anymore — assert the merged row semantics instead.
        assertMergedSwitchRow("App lock")
        assertMergedSwitchRow("Wallpaper colors")
        assertMergedSwitchRow("Haptic feedback")
        assertMergedSwitchRow("Animations and celebrations")
        assertMergedSwitchRow("Monthly backup reminder")
        // The toggle state is exposed on the row too; the default settings start it off.
        assertEquals(ToggleableState.Off,
            compose.onNode(hasText("Monthly backup reminder")).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
    }

    /** One merged PreferenceSwitch row: title text present, switch role and toggle on the row. */
    private fun assertMergedSwitchRow(title: String) {
        val row = compose.onNode(hasText(title)).assertExists().fetchSemanticsNode()
        assertTrue("The '$title' row must carry Role.Switch on its merged node",
            row.config[SemanticsProperties.Role] == Role.Switch)
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

    @Test fun clearCheckInRequiresConfirmation() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED)
        runBlocking { db.dao().insertHabit(habit); db.dao().insertLog(log) }
        var dismissals = 0
        compose.setContent { MaterialTheme { LogEditorDialog(habit, today, log, vm, onDismiss = { dismissals++ }) } }
        compose.onNodeWithText("Clear check-in").performClick()
        compose.onNodeWithText("Clear this check-in?").assertIsDisplayed()
        compose.onNodeWithText("Keep it").assertExists()
        // Nothing is erased before the confirmation: the seeded day still holds its check-in.
        assertEquals(LogStatus.COMPLETED, runBlocking { db.dao().log(1L, today)?.status })
        // The confirm button reads "Clear it", distinct from the editor's trigger button.
        compose.onNodeWithText("Clear it").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            // Room resumes its suspended caller through Android's main Handler; PAUSED mode
            // deliberately needs that queue driven while waiting for an external database task.
            shadowOf(Looper.getMainLooper()).idle()
            dismissals == 1
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithText("Clear this check-in?").assertDoesNotExist()
        assertNull(runBlocking { db.dao().log(1L, today) })
    }

    @Test fun statusOverwriteRequiresExplicitSave() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        val log = HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED)
        runBlocking { db.dao().insertHabit(habit); db.dao().insertLog(log) }
        var dismissals = 0
        compose.setContent { MaterialTheme { LogEditorDialog(habit, today, log, vm, onDismiss = { dismissals++ }) } }
        compose.onNodeWithText("Missed · breaks chain").performClick()
        // Overwriting an existing day's status no longer saves on tap: the one-tap save only
        // creates NEW check-ins. The dialog stays open and the recorded day keeps its status.
        compose.onNodeWithText("Missed · breaks chain").assertIsSelected()
        compose.runOnIdle { assertEquals(0, dismissals) }
        assertEquals(LogStatus.COMPLETED, runBlocking { db.dao().log(1L, today)?.status })
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5000) {
            shadowOf(Looper.getMainLooper()).idle()
            dismissals == 1
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
        assertEquals(LogStatus.FAILED, runBlocking { db.dao().log(1L, today)?.status })
    }

    @Test fun nextMilestoneCardNavigatesToAchievements() {
        // Rendered in isolation: the Today integration (card placement inside the dashboard
        // list) is covered by the reflection-reachability test, and driving the real vm flow
        // through a scroll probe proved nondeterministic under Robolectric. This test pins the
        // component contract — the card announces itself and its tap invokes the callback.
        val definition = Achievements.catalogue.first()
        var navigated = false
        compose.setContent {
            MaterialTheme {
                NextMilestoneCard(
                    progress = Achievements.Progress(definition = definition, current = 0, achieved = false, acknowledged = false),
                    onClick = { navigated = true })
            }
        }
        compose.onNodeWithText("Next milestone · ${definition.title}").assertExists()
        compose.onNodeWithText("0 / ${definition.target}").assertExists()
        compose.runOnIdle { assertFalse(navigated) } // rendering alone must never navigate
        compose.onNodeWithText("Next milestone · ${definition.title}").performClick()
        compose.runOnIdle { assertTrue(navigated) }
    }

    @Test fun heatmapGridIsASingleAccessibilityNode() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(600.dp)) { AnalyticsScreen(emptyList(), emptyList(), today) }
            }
        }
        // E1: the 53x7 grid exposes ONE node — clearAndSetSemantics replaces the ~370 per-cell
        // click/description stops with a single readout, so TalkBack steps through days with
        // the buttons below instead of wading through the cells themselves.
        compose.onNodeWithContentDescription("Year of daily completion squares. Use the buttons below to step through days.")
            .assertExists().assertHasNoClickAction()
        assertEquals(1, compose.onAllNodes(hasContentDescription("Year of daily completion squares", substring = true))
            .fetchSemanticsNodes().size)
    }

    @Test fun editorReminderRowTogglesFromTheRow() {
        val habit = Habit(id = 1, name = "Read", startDate = today)
        compose.setContent { MaterialTheme { HabitEditorScreen(1, LifeTrackState(habits = listOf(habit), loaded = true), vm) {} } }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Habit name")))
            .performScrollToNode(hasText("Habit reminder"))
        // The draft starts without a reminder; the switch exposes its state as a stateDescription.
        assertEquals("Disabled", compose.onNode(hasContentDescription("Habit reminder")).fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
        // The whole row is the switch now (merged toggleable row): tapping the title text
        // toggles the reminder draft exactly like tapping the switch itself.
        compose.onNode(hasText("Habit reminder")).performClick()
        assertEquals("Enabled", compose.onNode(hasContentDescription("Habit reminder")).fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
        compose.onNodeWithText("Hour · 00–23").assertExists()
        compose.onNode(hasText("Habit reminder")).performClick()
        assertEquals("Disabled", compose.onNode(hasContentDescription("Habit reminder")).fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
        compose.onNodeWithText("Hour · 00–23").assertDoesNotExist()
    }
}
