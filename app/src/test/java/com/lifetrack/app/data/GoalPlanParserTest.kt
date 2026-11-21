package com.lifetrack.app.data

import android.app.Application
import androidx.room.Room
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Goal Plan JSON contract: strict parsing of a standalone, versioned, AI-generated format
 * that is distinct from full backups — stable identities, bounded input, no fabricated progress
 * history, no database writes, and identical identities when the same file is parsed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GoalPlanParserTest {
    private lateinit var database: AppDatabase
    private lateinit var goals: GoalRepository
    private val today = LocalDate.of(2026, 11, 21)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        goals = GoalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun validPlan(): String = """
        {
          "kind": "lifetrack.goal-plan",
          "schemaVersion": 1,
          "planId": "a5b8e100-1331-4d11-9550-88391c0e1234",
          "goal": {
            "uuid": "b5b8e100-1331-4d11-9550-88391c0e1234",
            "title": "Learn machine learning",
            "description": "A 24-week plan",
            "category": "Study",
            "priority": "HIGH",
            "plannedStartDate": "2026-11-23",
            "targetDate": "2027-05-10"
          },
          "phases": [
            {
              "uuid": "c5b8e100-1331-4d11-9550-88391c0e1234",
              "name": "Mathematical foundations",
              "order": 0,
              "plannedStartDate": "2026-11-23",
              "plannedEndDate": "2027-01-04"
            },
            {
              "uuid": "d5b8e100-1331-4d11-9550-88391c0e1234",
              "name": "Python and programming",
              "order": 1,
              "plannedStartDate": "2026-12-21",
              "plannedEndDate": "2027-02-15"
            }
          ],
          "milestones": [
            {
              "uuid": "e5b8e100-1331-4d11-9550-88391c0e1234",
              "title": "Comfortable with linear algebra",
              "phaseUuid": "c5b8e100-1331-4d11-9550-88391c0e1234",
              "targetDate": "2027-01-04"
            }
          ],
          "tasks": [
            {
              "uuid": "f5b8e100-1331-4d11-9550-88391c0e1234",
              "title": "Work through vector spaces",
              "phaseUuid": "c5b8e100-1331-4d11-9550-88391c0e1234",
              "milestoneUuid": "e5b8e100-1331-4d11-9550-88391c0e1234",
              "order": 0,
              "plannedDate": "2026-11-24",
              "deadline": "2026-11-30",
              "estimatedMinutes": 120
            },
            {
              "uuid": "0a5b8e10-1331-4d11-9550-88391c0e1234",
              "title": "Install Python",
              "phaseUuid": "d5b8e100-1331-4d11-9550-88391c0e1234",
              "order": 1
            }
          ],
          "dependencies": [
            { "taskUuid": "f5b8e100-1331-4d11-9550-88391c0e1234", "prerequisiteUuid": "0a5b8e10-1331-4d11-9550-88391c0e1234" }
          ]
        }
    """.trimIndent()

    // ---- Valid documents ----

    @Test fun `a valid plan parses without database writes`() = runBlocking {
        val before = database.goalDao().allGoals()
        val document = GoalPlanParser.parse(validPlan())
        assertEquals(GoalPlanContract.KIND, document.kind)
        assertEquals(1, document.schemaVersion)
        assertEquals(2, document.phases.size)
        assertEquals(1, document.milestones.size)
        assertEquals(2, document.tasks.size)
        assertEquals(1, document.dependencies.size)
        // Pure parsing boundary: nothing was written.
        assertTrue(before == database.goalDao().allGoals())
        assertEquals(0, database.goalDao().allTasks().size)
    }

    @Test fun `parsing the same file twice yields identical identities`() {
        val first = GoalPlanParser.parse(validPlan())
        val second = GoalPlanParser.parse(validPlan())
        assertEquals(first, second)
        assertEquals(first.goal.uuid, second.goal.uuid)
        assertEquals(first.tasks.map { it.uuid }, second.tasks.map { it.uuid })
    }

    @Test fun `the 24-week example fixture parses through the real parser`() {
        val text = readFixture("ml-24-week.json")
        val document = GoalPlanParser.parse(text)
        assertEquals("Learn machine learning", document.goal.title)
        assertTrue(document.phases.size >= 4)
        assertTrue(document.tasks.size >= 18)
    }

    @Test fun `the simple and overlapping fixtures parse through the real parser`() {
        val simple = GoalPlanParser.parse(readFixture("simple-goal.json"))
        assertEquals(1, simple.phases.size)
        assertEquals(3, simple.tasks.size)
        val overlapping = GoalPlanParser.parse(readFixture("overlapping-phases.json"))
        assertTrue(overlapping.phases.size >= 2)
    }

    // ---- Rejections ----

    @Test fun `a full LifeTrack backup is not accepted as a Goal Plan`() {
        val backup = BackupValidator.json.encodeToString(LifeTrackBackup(
            formatVersion = 12, exportedAt = 1,
            habits = listOf(Habit(id = 1, name = "Read", startDate = today, createdAt = 10, updatedAt = 20)),
            logs = emptyList(), settings = AppSettings(), freezes = emptyList()
        ))
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(backup) }
        assertTrue(exception.message!!.contains("Goal Plan"))
    }

    @Test fun `an unknown document kind is rejected`() {
        val text = validPlan().replace("\"lifetrack.goal-plan\"", "\"some.other.kind\"")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("Goal Plan"))
    }

    @Test fun `an unsupported schema version is rejected`() {
        val text = validPlan().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("does not support"))
    }

    @Test fun `a missing goal title is rejected`() {
        val text = validPlan().replace("\"title\": \"Learn machine learning\",", "")
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
    }

    @Test fun `duplicate record identifiers are rejected`() {
        val text = validPlan().replace("0a5b8e10-1331-4d11-9550-88391c0e1234", "f5b8e100-1331-4d11-9550-88391c0e1234")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("Duplicate record identifiers"))
    }

    @Test fun `an unknown phase reference is rejected`() {
        // Replace the phase DECLARATION uuid: the task and milestone referencing it become
        // orphans and must be rejected. (The attribute-only form cannot be used because the
        // trimIndent-ed runtime text has unknown indentation.)
        val text = validPlan().replace(
            "\"uuid\": \"d5b8e100-1331-4d11-9550-88391c0e1234\"",
            "\"uuid\": \"99999999-9999-4999-8999-999999999999\"")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("unknown phase"))
    }

    @Test fun `cyclic dependencies are rejected`() {
        val text = validPlan().replace(
            """{ "taskUuid": "f5b8e100-1331-4d11-9550-88391c0e1234", "prerequisiteUuid": "0a5b8e10-1331-4d11-9550-88391c0e1234" }""",
            """{ "taskUuid": "f5b8e100-1331-4d11-9550-88391c0e1234", "prerequisiteUuid": "0a5b8e10-1331-4d11-9550-88391c0e1234" },
            { "taskUuid": "0a5b8e10-1331-4d11-9550-88391c0e1234", "prerequisiteUuid": "f5b8e100-1331-4d11-9550-88391c0e1234" }""")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("cycle"))
    }

    @Test fun `a self dependency is rejected`() {
        val text = validPlan().replace(
            "\"prerequisiteUuid\": \"0a5b8e10-1331-4d11-9550-88391c0e1234\"",
            "\"prerequisiteUuid\": \"f5b8e100-1331-4d11-9550-88391c0e1234\"")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("depend on itself"))
    }

    @Test fun `unknown keys are rejected by strict parsing`() {
        val text = validPlan().replace(
            "\"title\": \"Work through vector spaces\",",
            "\"title\": \"Work through vector spaces\",\n              \"completedAt\": \"2026-12-01\",")
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
    }

    @Test fun `a fabricated completion history field is rejected, not silently discarded`() {
        val text = validPlan().replace(
            "\"estimatedMinutes\": 120",
            "\"estimatedMinutes\": 120,\n              \"completed\": true")
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
    }

    @Test fun `an invalid calendar date is rejected`() {
        val text = validPlan().replace("2026-11-24", "2026-13-41")
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
    }

    @Test fun `an invalid uuid is rejected`() {
        val text = validPlan().replace("a5b8e100-1331-4d11-9550-88391c0e1234", "not-a-uuid")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("canonical UUID"))
    }

    @Test fun `oversized input is rejected before parsing`() {
        val padding = " ".repeat(GoalPlanContract.MAX_FILE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(validPlan() + padding) }
    }

    @Test fun `excessive nesting is rejected before parsing`() {
        val deep = "{\"kind\":".repeat(GoalPlanContract.MAX_JSON_DEPTH + 1)
        assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(deep) }
    }

    @Test fun `an estimated duration outside the supported range is rejected`() {
        val text = validPlan().replace("\"estimatedMinutes\": 120", "\"estimatedMinutes\": 20000")
        val exception = assertThrows(IllegalArgumentException::class.java) { GoalPlanParser.parse(text) }
        assertTrue(exception.message!!.contains("Estimated durations"))
    }

    @Test fun `invalid fixtures in the docs directory are all rejected`() {
        val directory = listOf(
            java.io.File("docs/examples/goal-plans/invalid"),
            java.io.File("../docs/examples/goal-plans/invalid")
        ).firstOrNull { it.exists() } ?: error("The invalid fixtures directory is required for parser tests")
        val files = directory.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("Expected at least five invalid fixtures, found ${files.size}", files.size >= 5)
        files.forEach { file ->
            val exception = assertThrows(IllegalArgumentException::class.java) {
                GoalPlanParser.parse(file.readText())
            }
            assertTrue("Fixture ${file.name} failed without an actionable message: ${exception.message}",
                exception.message!!.isNotBlank())
        }
    }

    // ---- Exporter round trip ----

    @Test fun `an exported goal plan round trips identity through the parser`() = runBlocking {
        val goal = goals.saveGoal(Goal(uuid = "", title = "Learn ML", plannedStartDate = today, targetDate = today.plusWeeks(24)))
        val phase = goals.savePhase(GoalPhase(uuid = "", goalId = goal.id, name = "Foundations", sortOrder = 0))
        val milestone = goals.saveMilestone(GoalMilestone(uuid = "", goalId = goal.id, phaseId = phase.id, title = "Basics down"))
        val prerequisite = goals.saveTask(GoalTask(uuid = "", goalId = goal.id, phaseId = phase.id, title = "Read chapter"))
        val dependent = goals.saveTask(GoalTask(uuid = "", goalId = goal.id, phaseId = phase.id, milestoneId = milestone.id,
            title = "Solve exercises", sortOrder = 1))
        goals.addDependency(dependent.id, prerequisite.id)
        val document = GoalPlanExporter.export(goal.id, goals)
        assertEquals(goal.uuid, document.goal.uuid)
        assertEquals(1, document.phases.size)
        assertEquals(1, document.milestones.size)
        assertEquals(2, document.tasks.size)
        assertEquals(1, document.dependencies.size)
        // Round trip: the exported plan re-parses and carries identical identities.
        val parsed = GoalPlanParser.parse(GoalPlanParser.write(document))
        assertEquals(document, parsed)
        assertEquals(goal.uuid, parsed.goal.uuid)
        assertEquals(phase.uuid, parsed.phases.single().uuid)
        assertEquals(milestone.uuid, parsed.milestones.single().uuid)
        assertEquals(listOf(prerequisite.uuid, dependent.uuid), parsed.tasks.map { it.uuid })
        assertNull(parsed.tasks.first { it.uuid == dependent.uuid }.plannedDate)
    }

    private fun readFixture(name: String): String {
        val candidates = listOf(
            java.io.File("docs/examples/goal-plans/$name"),
            java.io.File("../docs/examples/goal-plans/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("The $name fixture is required for Goal Plan parser tests")
        return file.readText()
    }
}
