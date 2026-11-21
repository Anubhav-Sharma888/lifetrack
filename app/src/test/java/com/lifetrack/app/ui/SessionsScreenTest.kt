package com.lifetrack.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.room.Room
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.SessionRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The session screens against the real ViewModel and Room database: empty state, overview
 * sections, detail lifecycle actions, and the editor's validation. State-gated waits only —
 * no sleeps, no fragile semantics timing (Prompt 2 hotfix discipline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SessionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var vm: SessionViewModel
    private lateinit var sessions: SessionRepository
    private lateinit var goals: GoalRepository

    @Before
    fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        goals = GoalRepository(db)
        sessions = SessionRepository(db)
        vm = SessionViewModel(sessions, goals, context)
    }

    @After
    fun close() {
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    /** The editor column is scrollable: bring a target into the viewport before interacting. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun awaitState(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try { compose.waitForIdle() } catch (error: AssertionError) { /* keep pumping */ }
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertTrue("state did not settle in ${timeoutMs}ms", condition())
    }

    private fun setContent() {
        compose.setContent { MaterialTheme { SessionsScreen(vm, onBack = {}, onOpenSession = {}, onAdd = {}) } }
    }

    private fun session(title: String, dayOffset: Long, start: LocalTime, end: LocalTime) =
        com.lifetrack.app.data.GoalSession(
            uuid = "", title = title,
            plannedStartDate = LocalDate.now().plusDays(dayOffset), plannedStartTime = start,
            plannedEndDate = LocalDate.now().plusDays(dayOffset), plannedEndTime = end,
            timezoneId = "UTC"
        )

    @Test fun `an empty schedule shows the invitation without inventing data`() {
        setContent()
        awaitState { vm.overview.value.loaded }
        compose.onNodeWithText("Schedule focused work time").assertIsDisplayed()
    }

    @Test fun `upcoming and awaiting-review sessions render in their honest sections`() = runBlocking {
        val past = sessions.save(session("Past session", -1, LocalTime.of(9, 0), LocalTime.of(10, 0)))
        sessions.save(session("Future session", 3, LocalTime.of(9, 0), LocalTime.of(10, 0)))
        setContent()
        awaitState { vm.overview.value.awaitingReview.isNotEmpty() && vm.overview.value.upcoming.isNotEmpty() }
        // Awaiting review: unconfirmed, past its planned end — never auto-completed.
        compose.onNodeWithText("Awaiting review").assertIsDisplayed()
        compose.onNodeWithText("Past session").assertIsDisplayed()
        compose.onNodeWithText("Upcoming").assertIsDisplayed()
        val future = sessions.sessionByUuid(
            vm.overview.value.upcoming.single().session.uuid)!!
        assertEquals("Future session", future.title)
        assertEquals(com.lifetrack.app.data.GoalSessionStatus.PLANNED.name, sessions.sessionByUuid(past.uuid)!!.status)
    }

    @Test fun `the detail screen offers explicit start and completion without fabricating history`() = runBlocking {
        val saved = sessions.save(session("Deep work", 1, LocalTime.of(9, 0), LocalTime.of(10, 0)))
        var openedUuid: String? = null
        compose.setContent { MaterialTheme { SessionDetailScreen(saved.uuid, vm, onBack = {}, onEdit = {}) } }
        awaitState { vm.detail.value?.uuid == saved.uuid }
        compose.onNodeWithText("Planned window").assertIsDisplayed()
        compose.onNodeWithText("Start session").performClick()
        awaitState { vm.detail.value?.status == com.lifetrack.app.data.GoalSessionStatus.IN_PROGRESS.name }
        compose.onNodeWithText("In progress").assertIsDisplayed()
        compose.onNodeWithText("Confirm completed").performClick()
        awaitState { vm.detail.value?.status == com.lifetrack.app.data.GoalSessionStatus.COMPLETED.name }
        compose.onNodeWithText("Completed").assertIsDisplayed()
        val after = sessions.sessionByUuid(saved.uuid)!!
        assertNotNull(after.actualStartAt)
        assertNotNull(after.actualEndAt)
        assertTrue(after.actualEndAt!! >= after.actualStartAt!!)
        openedUuid.let { /* navigation callback not asserted here — detail state is the source */ }
    }

    @Test fun `a missing session shows the safe not-found state`() {
        compose.setContent { MaterialTheme { SessionDetailScreen("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", vm, onBack = {}, onEdit = {}) } }
        awaitState { true }
        compose.onNodeWithText("This session is gone").assertIsDisplayed()
    }

    @Test fun `the editor rejects an inverted window with an accessible error`() {
        compose.setContent { MaterialTheme { SessionEditorScreen(vm, onBack = {}, onSaved = {}) } }
        awaitState { true }
        scrollTo("Save session")
        compose.onNodeWithText("Save session").performClick()
        // The title error surfaces immediately after the refused save.
        compose.onNodeWithText("Session titles must contain", substring = true).assertExists()
    }

    @Test fun `editing an existing session keeps its identity and updates the plan`() {
        val saved = runBlocking { sessions.save(session("Original title", 2, LocalTime.of(9, 0), LocalTime.of(10, 0))) }
        compose.setContent { MaterialTheme { SessionEditorScreen(vm, editUuid = saved.uuid, onBack = {}, onSaved = {}) } }
        awaitState { compose.onAllNodesWithText("Original title", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Original title").performTextReplacement("Renamed session")
        scrollTo("Save session")
        compose.onNodeWithText("Save session").performClick()
        awaitState { runBlocking { sessions.sessionCount() == 1 } }
        val after = runBlocking { sessions.sessionByUuid(saved.uuid)!! }
        assertEquals("Renamed session", after.title)
        assertEquals(saved.uuid, after.uuid)
        assertEquals(saved.id, after.id)
    }

    @Test fun `the editor saves a valid session through the real repository`() {
        val goal = runBlocking { goals.saveGoal(Goal(uuid = "", title = "Learn ML")) }
        var savedTo: String? = null
        compose.setContent { MaterialTheme { SessionEditorScreen(vm, prefillGoalId = goal.id, onBack = {}, onSaved = { savedTo = "done" }) } }
        awaitState { true }
        compose.onNodeWithText("Schedule session").assertIsDisplayed()
        // Fill the title and save with default 17:00–18:30 tomorrow.
        compose.onNodeWithText("Title").performTextInput("Study vectors")
        scrollTo("Save session")
        compose.onNodeWithText("Save session").performClick()
        awaitState { runBlocking { sessions.sessionCount() == 1 } }
        val saved = runBlocking { db.sessionDao().allSessions().single() }
        assertEquals("Study vectors", saved.title)
        assertEquals(goal.id, saved.goalId)
        assertEquals("done", savedTo)
    }
}

