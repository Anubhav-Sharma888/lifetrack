package com.lifetrack.app.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.GoalPlanContract
import com.lifetrack.app.data.GoalPlanDecision
import com.lifetrack.app.data.GoalPlanDependency
import com.lifetrack.app.data.GoalPlanDocument
import com.lifetrack.app.data.GoalPlanGoal
import com.lifetrack.app.data.GoalPlanImportService
import com.lifetrack.app.data.GoalPlanMilestone
import com.lifetrack.app.data.GoalPlanParser
import com.lifetrack.app.data.GoalPlanPhase
import com.lifetrack.app.data.GoalPlanTask
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.GoalTaskStatus
import java.io.File
import java.time.LocalDate
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
 * The Goal Plan import UI against the real ViewModel, service and Room database: file selection
 * (through the same contentResolver path the system picker feeds), the read-only preview,
 * decision changes, blocking conflicts, confirmation, navigation and failure states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class GoalPlanImportScreensTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: GoalPlanImportViewModel
    private lateinit var repository: GoalRepository
    private val today get() = LocalDate.now()

    private val goalUuid = "a1000000-0000-4000-8000-000000000001"
    private val phaseUuid = "a1000000-0000-4000-8000-000000000002"
    private val taskUuid = "a1000000-0000-4000-8000-000000000004"

    private var viewedGoalId: Long? = null
    private var backPressed = false

    @Before
    fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = GoalRepository(db)
        vm = GoalPlanImportViewModel(GoalPlanImportService(db), context)
    }

    @After
    fun close() {
        vm.reset()
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                GoalPlanImportScreen(vm, onBack = { backPressed = true }, onViewGoal = { viewedGoalId = it })
            }
        }
        compose.waitForIdle()
    }

    /** Drives the same path the document-picker callback uses, through the real contentResolver. */
    private fun load(text: String, fileName: String = "plan.json") {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "import-${System.nanoTime()}.json")
        file.writeText(text)
        file.deleteOnExit()
        setContent()
        vm.load(Uri.fromFile(file), fileName)
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.READY || vm.state.value.phase == GoalPlanImportPhase.FAILED }
    }

    /** Lazy-list content below the fold is not composed: scroll to it before asserting. */
    private fun scrollTo(text: String) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun awaitPhase(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try { compose.waitForIdle() } catch (error: AssertionError) { /* keep pumping */ }
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertTrue("The import phase did not settle in 30s", condition())
    }

    private fun planDocument(
        goalTitle: String = "Learn ML", withChildren: Boolean = true,
        phases: List<GoalPlanPhase> = listOf(GoalPlanPhase(phaseUuid, "Foundations", null, 0, null, null)),
        tasks: List<GoalPlanTask> = if (withChildren) listOf(
            GoalPlanTask(taskUuid, "Study vectors", null, 0, phaseUuid, null, null, null, null)
        ) else emptyList(),
        milestones: List<GoalPlanMilestone> = if (withChildren) listOf(
            GoalPlanMilestone("a1000000-0000-4000-8000-000000000003", "Basics down", null, phaseUuid, null)
        ) else emptyList()
    ) = GoalPlanDocument(GoalPlanContract.KIND, 1, goalUuid,
        GoalPlanGoal(goalUuid, goalTitle), phases, milestones, tasks, emptyList())

    // ---- States ----

    @Test fun `the idle screen explains the flow and offers the picker`() {
        setContent()
        compose.onNodeWithText("Import a Goal Plan").assertIsDisplayed()
        compose.onNodeWithText("Choose a Goal Plan file").assertIsDisplayed()
        compose.onNodeWithText("Non-destructive by design").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test fun `a valid plan previews with honest counts and writes nothing`() {
        val beforeGoals = runBlocking { db.goalDao().allGoals().size }
        load(GoalPlanParser.write(planDocument()))
        assertEquals(GoalPlanImportPhase.READY, vm.state.value.phase)
        compose.onNodeWithText("Learn ML").assertIsDisplayed()
        compose.onNodeWithText("Phases (1)").assertExists()
        scrollTo("Tasks (1)")
        compose.onNodeWithText("Tasks (1)").assertExists()
        scrollTo("Milestones (1)")
        compose.onNodeWithText("Milestones (1)").assertExists()
        // Four records are new: goal + phase + milestone + task.
        compose.onNodeWithText("4 to add · 0 to update · 0 to copy · 0 already imported · 0 skipped")
            .assertExists()
        assertEquals(beforeGoals, runBlocking { db.goalDao().allGoals().size })
        assertEquals(0, runBlocking { db.goalDao().allTasks().size })
    }

    @Test fun `a conflicting goal shows the difference and lets the user choose`() = runBlocking {
        repository.saveGoal(com.lifetrack.app.data.Goal(uuid = goalUuid, title = "Learn ML", targetDate = today.plusWeeks(12)))
        load(GoalPlanParser.write(planDocument(goalTitle = "Learn ML faster", withChildren = false, phases = emptyList())))
        compose.onNodeWithText("Has changes").assertExists()
        compose.onNodeWithText("Title: was \"Learn ML\" → plan says \"Learn ML faster\"", substring = true)
            .assertExists()
        // The default keeps the existing record: confirming is an honest no-op.
        compose.onNodeWithText("Confirm import").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.DONE }
        compose.onNodeWithText("Nothing needed to change").assertExists()
        assertEquals(today.plusWeeks(12), runBlocking { db.goalDao().goalByUuid(goalUuid)!!.targetDate })
    }

    @Test fun `choosing use imported updates the goal and navigates to it`() = runBlocking {
        val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = goalUuid, title = "Learn ML"))
        load(GoalPlanParser.write(planDocument(goalTitle = "Learn ML faster", withChildren = false, phases = emptyList())))
        // The goal row is the only record, so its decision chips are unambiguous.
        compose.onNodeWithText("Use imported").performClick()
        compose.onNodeWithText("0 to add · 1 to update · 0 to copy · 0 already imported · 0 skipped")
            .assertExists()
        compose.onNodeWithText("Confirm import").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.DONE }
        compose.onNodeWithText("Import complete").assertIsDisplayed()
        compose.onNodeWithText("View goal").performClick()
        assertEquals(goal.id, viewedGoalId)
        assertEquals("Learn ML faster", runBlocking { db.goalDao().goal(goal.id)!!.title })
    }

    @Test fun `a blocked preview names the blockers and disables the confirm action`() {
        load(GoalPlanParser.write(planDocument()))
        // Skip the phase through the ViewModel decision path (chip mechanics are covered above).
        vm.setDecision(phaseUuid, GoalPlanDecision.SKIP)
        compose.waitForIdle()
        compose.onNodeWithText("Resolve before importing").assertExists()
        compose.onNodeWithText("\"Study vectors\" needs its imported phase, which is set to Skip. " +
            "Skip this record too, or change the parent's decision.").assertExists()
        compose.onNodeWithText("Confirm import").assertIsNotEnabled()
    }

    @Test fun `an invalid file fails without writing anything`() {
        load("{ this is not json", fileName = "broken.json")
        assertEquals(GoalPlanImportPhase.FAILED, vm.state.value.phase)
        compose.onNodeWithText("The import did not happen").assertIsDisplayed()
        compose.onNodeWithText("Choose a different file").assertIsDisplayed()
        assertEquals(0, runBlocking { db.goalDao().allGoals().size })
    }

    @Test fun `a life-track backup is routed to the backup workflow with an explanation`() = runBlocking {
        val backup = com.lifetrack.app.data.BackupValidator.json.encodeToString(
            com.lifetrack.app.data.LifeTrackBackup(
                formatVersion = 12, exportedAt = 1,
                habits = listOf(com.lifetrack.app.data.Habit(id = 1, name = "Read", startDate = today, createdAt = 10, updatedAt = 20)),
                logs = emptyList(), settings = com.lifetrack.app.data.AppSettings(), freezes = emptyList()
            ))
        load(backup)
        assertEquals(GoalPlanImportPhase.FAILED, vm.state.value.phase)
        compose.onNodeWithText("The import did not happen").assertIsDisplayed()
        val error = vm.state.value.error.orEmpty()
        assertTrue("Expected the backup explanation, got: $error", error.contains("LifeTrack backup"))
        assertEquals(0, runBlocking { db.goalDao().allGoals().size }) // nothing written
    }

    @Test fun `a failed confirmation keeps the preview and returns to it`() = runBlocking {
        val goal = repository.saveGoal(com.lifetrack.app.data.Goal(uuid = goalUuid, title = "Learn ML"))
        load(GoalPlanParser.write(planDocument(goalTitle = "Renamed by plan", withChildren = false)))
        compose.onNodeWithText("Use imported").performClick()
        compose.waitForIdle()
        // Concurrent change elsewhere invalidates the preview between review and confirm.
        repository.saveGoal(goal.copy(title = "Edited elsewhere"))
        compose.onNodeWithText("Confirm import").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.FAILED }
        compose.onNodeWithText("Return to preview").assertIsDisplayed()
        compose.onNodeWithText("Return to preview").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.READY }
        // Refresh the preview and confirm again: this time it succeeds against live data.
        compose.onNodeWithContentDescription("Refresh preview").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.READY && vm.state.value.preview?.fingerprint?.isNotEmpty() == true }
        compose.onNodeWithText("Use imported").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Confirm import").performClick()
        awaitPhase { vm.state.value.phase == GoalPlanImportPhase.DONE }
        assertEquals("Renamed by plan", runBlocking { db.goalDao().goal(goal.id)!!.title })
    }

    @Test fun `a large plan collapses sections and always shows the confirmation`() {
        val plan = planDocument(withChildren = false).let { document ->
            document.copy(tasks = (1..200).map { index ->
                GoalPlanTask("b%07d-0000-4000-8000-000000000000".format(java.util.Locale.ROOT, index),
                    "Step $index", null, index, null, null, null, null, null)
            })
        }
        load(GoalPlanParser.write(plan))
        compose.onNodeWithText("Phases (1)").assertExists()
        scrollTo("Tasks (200)")
        compose.onNodeWithText("Tasks (200)").assertExists()
        // The fixed footer carries the honest count without scrolling: 200 tasks + phase + goal.
        compose.onNodeWithText("202 to add · 0 to update · 0 to copy · 0 already imported · 0 skipped")
            .assertExists()
        compose.onNodeWithText("Confirm import").assertExists()
    }

    @Test fun `canceling the preview leaves the database untouched`() {
        load(GoalPlanParser.write(planDocument()))
        vm.reset()
        compose.waitForIdle()
        assertEquals(GoalPlanImportPhase.IDLE, vm.state.value.phase)
        compose.onNodeWithText("Import a Goal Plan").assertIsDisplayed()
        assertEquals(0, runBlocking { db.goalDao().allGoals().size })
    }
}
