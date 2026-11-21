package com.lifetrack.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.GoalTask
import com.lifetrack.app.data.GoalTaskStatus
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * The Goal Roadmaps screens against a real ViewModel and the real Room database: hub states,
 * editor validation and cancel safety, detail hierarchy, dialog-driven creation, and the honest
 * blocked/overdue markers. DatePicker day grids never compose under Robolectric (the documented
 * device-QA limitation), so no test drives a picker — date logic is pinned at the engine and
 * repository layers instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class GoalScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: GoalViewModel
    private lateinit var repository: GoalRepository
    private val today = LocalDate.now()

    @Before
    fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = GoalRepository(db)
        vm = GoalViewModel(repository)
    }

    @After
    fun close() {
        vm.viewModelScope.cancel()
        // Let in-flight Room queries observe the cancellation before the pool closes, or a leaked
        // collection surfaces as the NEXT test-s failure.
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    /** VM coroutines and Room flows run on Robolectric-s paused main looper: pump it explicitly
     *  until the condition settles, letting Compose resync and the Room executor threads land
     *  their results between pumps. */
    private fun awaitSettled(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            // Executor threads (Room queries) land their results between pumps.
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertTrue("$what did not settle in ${timeoutMs}ms", condition())
    }

    private fun waitUntilIdle() = awaitSettled("a running operation") { !vm.busy.value }

    private fun setHubContent(onAdd: () -> Unit = {}, onOpen: (Long) -> Unit = {}) {
        compose.setContent { MaterialTheme { GoalsScreen(vm, onAdd = onAdd, onOpen = onOpen) } }
        awaitSettled("the hub to load") { vm.hub.value.loaded }
    }

    private fun setDetailContent(goalId: Long, title: String) {
        compose.setContent { MaterialTheme { GoalDetailScreen(goalId, vm, onBack = {}, onEdit = {}) } }
        // Wait on the STATE, not the semantics tree: the hot detail StateFlow is the source of
        // truth, so a slow runner can only delay the emission, never hide it behind recomposition
        // timing. One idle sync then guarantees the UI mirrors the settled state (the top bar
        // carries the goal title once detail is non-null).
        awaitSettled("the goal detail to load") { vm.detail.value?.goal?.id == goalId }
        compose.waitForIdle()
        awaitSettled("the goal title to render") { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String) {
        // The loaded detail screen has exactly one scrollable (the roadmap list); a missing or
        // doubled scrollable is a state bug this assertion names instead of failing opaquely.
        val scrollables = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        assertTrue("Expected exactly one scrollable roadmap list, found ${scrollables.size} " +
            "(detail loaded: ${vm.detail.value?.goal?.id != null}; a goal dialog adds a second)", scrollables.size == 1)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    @Test
    fun anEmptyDatabaseShowsTheInvitationWithoutInventingData() {
        setHubContent()
        compose.onNodeWithText("Plan something meaningful").assertIsDisplayed()
        compose.onNodeWithText("Create your first goal").performClick()
        compose.onNodeWithText("Goals").assertIsDisplayed()
    }

    @Test
    fun createdGoalsShowAsCardsWithHonestCountsAndCurrentPhase() {
        val goalId = runBlocking {
            val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Learn ML", plannedStartDate = today.minusWeeks(2)))
            repository.saveGoal(goal.copy(status = com.lifetrack.app.data.GoalStatus.ACTIVE.name))
            repository.savePhase(com.lifetrack.app.data.GoalPhase(uuid = "", goalId = goal.id, name = "Mathematical foundations",
                plannedStartDate = today.minusDays(3), plannedEndDate = today.plusDays(3)))
            val done = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, phaseId = 1L, title = "Vectors"))
            repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Calculus", sortOrder = 1)).let { }
            repository.saveTask(done.copy(status = GoalTaskStatus.COMPLETED.name))
            goal.id
        }
        var opened = 0L
        setHubContent(onOpen = { opened = it })
        compose.onNodeWithText("Learn ML").assertIsDisplayed()
        compose.onNodeWithText("1 of 2 tasks").assertIsDisplayed()
        compose.onNodeWithText("Current phase: Mathematical foundations").assertIsDisplayed()
        compose.onNodeWithText("Learn ML").performClick()
        waitUntilIdle()
        assertEquals(goalId, opened)
    }

    @Test
    fun filtersSeparateBusinessStatesWithoutHidingData() {
        runBlocking {
            val active = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Active goal"))
            repository.saveGoal(active.copy(status = com.lifetrack.app.data.GoalStatus.ACTIVE.name))
            repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Planned goal", sortOrder = 1))
            val filing = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Filing", sortOrder = 2))
            repository.archiveGoal(filing.id, true)
        }
        setHubContent()
        // The default filter is ACTIVE: only the active goal shows, and the word "Active" itself
        // appears on both the chip and the card badge, so it is asserted as displayed, not clicked.
        compose.onNodeWithText("Active goal").assertIsDisplayed()
        compose.onNodeWithText("Planned goal").assertDoesNotExist()
        compose.onNodeWithText("Planned").performClick()
        compose.onNodeWithText("Planned goal").assertIsDisplayed()
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithText("Filing").assertIsDisplayed()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Planned goal").assertIsDisplayed()
    }

    @Test
    fun theEditorBlocksBlankTitlesAndExplainsTheRule() {
        compose.setContent { MaterialTheme { GoalEditorScreen(0, vm, today = today, onBack = {}) } }
        compose.onNodeWithText("Give the goal a title.").assertIsDisplayed()
        compose.onNodeWithText("Save goal").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Learn ML")
        compose.onNodeWithText("Save goal").assertIsDisplayed()
    }

    @Test
    fun cancellingTheEditorWritesNothing() {
        var exited = false
        compose.setContent { MaterialTheme { GoalEditorScreen(0, vm, today = today, onBack = { exited = true }) } }
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Never saved")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(exited)
        assertEquals(0, runBlocking { db.goalDao().allGoals().size })
    }

    @Test
    fun savingTheEditorPersistsTheGoalThroughTheRepository() {
        var back = false
        compose.setContent { MaterialTheme { GoalEditorScreen(0, vm, today = today, onBack = { back = true }) } }
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Persisted goal")
        compose.onNodeWithText("Save goal").performClick()
        waitUntilIdle()
        assertTrue(back)
        assertEquals("Persisted goal", runBlocking { db.goalDao().allGoals().single().title })
    }

    @Test
    fun theDetailScreenShowsTheWholeHierarchy() {
        val goalId = runBlocking {
            val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Learn ML"))
            repository.savePhase(com.lifetrack.app.data.GoalPhase(uuid = "", goalId = goal.id, name = "Mathematical foundations"))
            repository.saveMilestone(com.lifetrack.app.data.GoalMilestone(uuid = "", goalId = goal.id, title = "Algebra done"))
            repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study vectors"))
            goal.id
        }
        setDetailContent(goalId, "Learn ML")
        compose.onNodeWithText("Timeline").assertIsDisplayed()
        // LazyColumn disposes items scrolled out of the buffer, so every section is scrolled into
        // view by its own header, then its content is asserted where it lands.
        scrollTo("Phases")
        compose.onNodeWithText("Phases").assertIsDisplayed()
        scrollTo("Mathematical foundations")
        compose.onAllNodesWithText("Mathematical foundations")[0].assertExists()
        scrollTo("Milestones")
        compose.onNodeWithText("Milestones").assertIsDisplayed()
        scrollTo("Algebra done")
        // The milestone title appears in the timeline and the section row.
        compose.onAllNodesWithText("Algebra done")[0].assertExists()
        scrollTo("Tasks")
        compose.onNodeWithText("Tasks").assertIsDisplayed()
        scrollTo("Study vectors")
        compose.onNodeWithText("Study vectors").assertIsDisplayed()
    }

    @Test
    fun aDeletedGoalExplainsItselfInsteadOfCrashing() {
        compose.setContent { MaterialTheme { GoalDetailScreen(999, vm, onBack = {}, onEdit = {}) } }
        compose.onNodeWithText("This goal no longer exists.").assertIsDisplayed()
        compose.onNodeWithText("Back to Goals").performClick()
    }

    @Test
    fun dialogsCreatePhasesAndTasksThroughTheRepository() {
        val goalId = runBlocking {
            repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Learn ML")).id
        }
        setDetailContent(goalId, "Learn ML")
        scrollTo("Add phase")
        compose.onNodeWithText("Add phase").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Programming foundations")
        compose.onNodeWithText("Save phase").performClick()
        waitUntilIdle()
        awaitSettled("the phase card to appear") { compose.onAllNodesWithText("Programming foundations").fetchSemanticsNodes().isNotEmpty() }

        scrollTo("Add task")
        compose.onNodeWithText("Add task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Install Python")
        compose.onNodeWithText("Save task").performClick()
        waitUntilIdle()
        // Gate the freshly-written row BEFORE scrolling: performScrollToNode can exhaust a short
        // list while the Room re-emission has not landed yet (the CI Failure A signature).
        awaitSettled("the task row to appear") { compose.onAllNodesWithText("Install Python").fetchSemanticsNodes().isNotEmpty() }
        scrollTo("Install Python")
        assertEquals(1, runBlocking { db.goalDao().tasksForGoal(goalId).size })
    }

    @Test
    fun blockedAndOverdueTasksShowHonestMarkers() {
        val goalId = runBlocking {
            val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Learn ML"))
            val prerequisite = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Learn Python"))
            val blocked = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Train model", sortOrder = 1,
                deadline = today.minusDays(3)))
            repository.addDependency(blocked.id, prerequisite.id)
            goal.id
        }
        setDetailContent(goalId, "Learn ML")
        scrollTo("Train model")
        compose.onNodeWithText("Blocked by Learn Python", substring = true).assertIsDisplayed()
        compose.onNodeWithText("3d overdue", substring = true).assertIsDisplayed()
    }

    @Test
    fun theMoveDialogShowsTheOriginalPlanAndRefusesNoOpMoves() {
        val goalId = runBlocking {
            val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = "", title = "Learn ML"))
            val task = repository.saveTask(GoalTask(uuid = "", goalId = goal.id, title = "Study",
                plannedDate = today.minusDays(10)))
            repository.rescheduleTask(task.id, today.minusDays(4), null)
            goal.id
        }
        compose.setContent { MaterialTheme { GoalDetailScreen(goalId, vm, onBack = {}, onEdit = {}) } }
        awaitSettled("the goal detail to load") { compose.onAllNodesWithText("Originally planned", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Originally planned ${today.minusDays(10)}", substring = true).assertIsDisplayed()
        scrollTo("Move")
        compose.onNodeWithText("Move").performClick()
        awaitSettled("the move dialog to open") { compose.onAllNodesWithText("Originally planned:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The dialog shows the recovered original plan and refuses an unchanged move.
        compose.onNodeWithText("Originally planned: ${today.minusDays(10)}", substring = true).assertIsDisplayed()
        // Two Move buttons exist (row + dialog): the dialog-s is the disabled one.
        val moveNodes = compose.onAllNodesWithText("Move").fetchSemanticsNodes()
        assertTrue("The dialog Move is disabled for an unchanged move",
            moveNodes.any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) })
        compose.onNodeWithText("Cancel").performClick()
    }
}
