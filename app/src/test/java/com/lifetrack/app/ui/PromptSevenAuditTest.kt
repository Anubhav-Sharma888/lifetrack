package com.lifetrack.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import com.lifetrack.app.MainActivity
import com.lifetrack.app.data.AppDatabase
import com.lifetrack.app.data.Goal
import com.lifetrack.app.data.GoalRepository
import com.lifetrack.app.data.GoalSession
import com.lifetrack.app.data.GoalSessionStatus
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitAlarm
import com.lifetrack.app.data.HabitAlarmRepository
import com.lifetrack.app.data.HabitRepository
import com.lifetrack.app.data.SessionRecoveryRepository
import com.lifetrack.app.data.SessionRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class PromptSevenAuditTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var sessionVm: SessionViewModel
    private lateinit var goalVm: GoalViewModel
    private lateinit var alarmVm: HabitAlarmViewModel
    private lateinit var sessions: SessionRepository
    private lateinit var goals: GoalRepository
    private lateinit var alarmRepo: HabitAlarmRepository
    private val today = LocalDate.now()

    @Before
    fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        goals = GoalRepository(db)
        sessions = SessionRepository(db)
        alarmRepo = HabitAlarmRepository(db)
        sessionVm = SessionViewModel(sessions, SessionRecoveryRepository(db), goals, context)
        goalVm = GoalViewModel(goals, HabitRepository(db, context))
        alarmVm = HabitAlarmViewModel(alarmRepo, object : AlarmSchedulerBridge {
            override fun upsert(alarm: HabitAlarm) {}
            override fun cancel(alarmUuid: String) {}
        })
    }

    @After
    fun close() {
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun awaitState(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try { compose.waitForIdle() } catch (error: AssertionError) {}
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertTrue("state did not settle in ${timeoutMs}ms", condition())
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    @Test
    fun sessionEditorCleanDraftAllowsImmediateCancel() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                SessionEditorScreen(sessionVm, onBack = { backCalled = true }, onSaved = {})
            }
        }
        awaitState { true }
        scrollTo("Cancel")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue("Clean session editor should exit immediately without prompt", backCalled)
    }

    @Test
    fun sessionEditorDirtyDraftPromptsForDiscard() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                SessionEditorScreen(sessionVm, onBack = { backCalled = true }, onSaved = {})
            }
        }
        awaitState { true }
        compose.onNodeWithText("Title").performTextInput("Focused work session")
        scrollTo("Cancel")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        // Discard confirmation dialog appears
        compose.onNodeWithText("Keep your changes?").assertIsDisplayed()
        compose.onNodeWithText("Your edits are still here. Save the session to keep them, or discard them to leave.").assertIsDisplayed()
        assertEquals("Back should not be called while dialog is open", false, backCalled)

        // Confirm discard
        compose.onNodeWithText("Discard changes").performClick()
        compose.waitForIdle()
        assertTrue("Confirming discard should exit", backCalled)
    }

    @Test
    fun goalEditorCleanDraftAllowsImmediateCancel() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                GoalEditorScreen(0L, goalVm, today, onBack = { backCalled = true })
            }
        }
        awaitState { true }
        compose.onNodeWithText("Cancel").performClick()
        assertTrue("Clean goal editor should exit immediately", backCalled)
    }

    @Test
    fun goalEditorDirtyDraftPromptsForDiscard() {
        var backCalled = false
        compose.setContent {
            MaterialTheme {
                GoalEditorScreen(0L, goalVm, today, onBack = { backCalled = true })
            }
        }
        awaitState { true }
        compose.onNodeWithText("Title").performTextInput("Master Kotlin Coroutines")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keep your changes?").assertIsDisplayed()
        assertEquals(false, backCalled)

        compose.onNodeWithText("Discard changes").performClick()
        compose.waitForIdle()
        assertTrue("Confirming discard should exit", backCalled)
    }

    @Test
    fun sessionDetailDeleteShowsConfirmationDialog() {
        runBlocking {
            val s = sessions.save(GoalSession(
                uuid = "", title = "Review PR",
                plannedStartDate = today, plannedStartTime = LocalTime.of(10, 0),
                plannedEndDate = today, plannedEndTime = LocalTime.of(11, 0),
                timezoneId = "UTC"
            ))
            var backCalled = false
            compose.setContent {
                MaterialTheme {
                    SessionDetailScreen(s.uuid, sessionVm, onBack = { backCalled = true }, onEdit = {})
                }
            }
            awaitState { sessionVm.detail.value?.uuid == s.uuid }
            scrollTo("Delete session")
            compose.onNodeWithText("Delete session").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Delete this session?").assertIsDisplayed()
            // Canceling leaves session intact
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertNotNull(sessions.sessionByUuid(s.uuid))
            assertEquals(false, backCalled)

            // Click delete again and confirm
            compose.onNodeWithText("Delete session").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Delete").performClick()
            awaitState { runBlocking { sessions.sessionByUuid(s.uuid) } == null }
            assertTrue("Deleting session should trigger back navigation", backCalled)
        }
    }

    @Test
    fun sessionDetailCancelShowsConfirmationDialog() {
        runBlocking {
            val s = sessions.save(GoalSession(
                uuid = "", title = "Cancelled Study",
                plannedStartDate = today, plannedStartTime = LocalTime.of(14, 0),
                plannedEndDate = today, plannedEndTime = LocalTime.of(15, 0),
                timezoneId = "UTC"
            ))
            compose.setContent {
                MaterialTheme {
                    SessionDetailScreen(s.uuid, sessionVm, onBack = {}, onEdit = {})
                }
            }
            awaitState { sessionVm.detail.value?.uuid == s.uuid }
            scrollTo("Cancel session")
            compose.onNodeWithText("Cancel session").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Cancel this session?").assertIsDisplayed()
            // Click confirm
            compose.onAllNodesWithText("Cancel session")[1].performClick()
            awaitState { runBlocking { sessions.sessionByUuid(s.uuid) }?.status == GoalSessionStatus.CANCELED.name }
            assertEquals(GoalSessionStatus.CANCELED.name, sessions.sessionByUuid(s.uuid)!!.status)
        }
    }

    @Test
    fun ringingAlarmsSectionRejectsDuplicateTimes() {
        runBlocking {
            val habit = Habit(name = "Morning Stretch", startDate = today)
            val habitId = db.dao().insertHabit(habit)
            alarmRepo.save(HabitAlarm(uuid = "", habitId = habitId, timeMinutes = 420)) // 07:00

            compose.setContent {
                MaterialTheme {
                    RingingAlarmsSection(habitId = habitId, vm = alarmVm)
                }
            }
            awaitState { true }
            // Attempting to add 07:00 when 07:00 is already present
            compose.onNodeWithText("Add").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("An alarm is already set for 07:00.").assertIsDisplayed()
        }
    }

    @Test
    fun mainActivityParsesDeepLinksAndExtras() {
        val activity = MainActivity()
        // Case 1: Extra
        val extraIntent = Intent().putExtra("open_session", "session-extra-123")
        assertEquals("session-extra-123", activity.parseSessionRequest(extraIntent))

        // Case 2: Deep link URI lifetrack://session/<uuid>
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("lifetrack://session/session-uri-456"))
        assertEquals("session-uri-456", activity.parseSessionRequest(uriIntent))

        // Case 3: Neither
        val plainIntent = Intent()
        assertNull(activity.parseSessionRequest(plainIntent))

        // Case 4: Different scheme or host
        val otherUriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lifetrack.app/other"))
        assertNull(activity.parseSessionRequest(otherUriIntent))
    }

    @Test
    fun sessionsScreenHeadersHaveHeadingSemantics() {
        runBlocking {
            val saved = sessions.save(GoalSession(
                uuid = "", title = "Active Work",
                plannedStartDate = today, plannedStartTime = LocalTime.of(9, 0),
                plannedEndDate = today, plannedEndTime = LocalTime.of(10, 0),
                timezoneId = "UTC"
            ))
            sessions.start(saved.uuid)
            compose.setContent {
                MaterialTheme {
                    SessionsScreen(sessionVm, onBack = {}, onOpenSession = {}, onAdd = {})
                }
            }
            awaitState { sessionVm.overview.value.inProgress.isNotEmpty() }
            // Verify heading semantics on section header
            compose.onNode(hasText("In progress") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        }
    }
}
