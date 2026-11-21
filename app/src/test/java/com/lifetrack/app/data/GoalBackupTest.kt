package com.lifetrack.app.data

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup format version 10: Goal Roadmaps ride in full backups and restore transactionally,
 * every older format still decodes key-free, and malformed or unsafe goal graphs are rejected
 * before any write. Mirrors the reflection/acknowledgment/scheduling suites' structure.
 */
class GoalBackupTest {
    private val today = LocalDate.of(2026, 11, 21)
    private val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10), createdAt = 100, updatedAt = 200)

    private val goal = Goal(
        id = 1, uuid = "11111111-1111-4111-8111-111111111111", title = "Learn machine learning",
        description = "A 24-week plan", plannedStartDate = today, targetDate = today.plusWeeks(24),
        status = GoalStatus.ACTIVE.name, sortOrder = 0, createdAt = 100, updatedAt = 200
    )
    private val phase = GoalPhase(
        id = 2, uuid = "22222222-2222-4222-8222-222222222222", goalId = 1, name = "Mathematical foundations",
        sortOrder = 0, plannedStartDate = today, plannedEndDate = today.plusWeeks(6), createdAt = 100, updatedAt = 200
    )
    private val milestone = GoalMilestone(
        id = 3, uuid = "33333333-3333-4333-8333-333333333333", goalId = 1, phaseId = 2,
        title = "Comfortable with linear algebra", targetDate = today.plusWeeks(6), createdAt = 100, updatedAt = 200
    )
    private val task = GoalTask(
        id = 4, uuid = "44444444-4444-4444-8444-444444444444", goalId = 1, phaseId = 2, milestoneId = 3,
        title = "Work through vector spaces", plannedDate = today.plusDays(1), deadline = today.plusDays(7),
        estimatedMinutes = 90, sortOrder = 0, createdAt = 100, updatedAt = 200
    )
    private val dependency = GoalTaskDependency(dependentTaskId = 4, prerequisiteTaskId = 5)
    private val prerequisite = GoalTask(
        id = 5, uuid = "55555555-5555-4555-8555-555555555555", goalId = 1, phaseId = 2,
        title = "Read the textbook chapter", sortOrder = 1, createdAt = 100, updatedAt = 200
    )

    private val habitLink = GoalHabitLink(goalId = 1, habitId = 1, createdAt = 250)

    private fun backup(
        goals: List<Goal> = listOf(goal),
        phases: List<GoalPhase> = listOf(phase),
        milestones: List<GoalMilestone> = listOf(milestone),
        tasks: List<GoalTask> = listOf(task, prerequisite),
        dependencies: List<GoalTaskDependency> = listOf(dependency),
        links: List<GoalHabitLink> = emptyList(),
        sessions: List<GoalSession> = emptyList(),
        formatVersion: Int = 10
    ) = LifeTrackBackup(
        formatVersion = formatVersion, exportedAt = 300, habits = listOf(habit), logs = emptyList(),
        settings = AppSettings(), freezes = emptyList(),
        goals = goals, goalPhases = phases, goalMilestones = milestones, goalTasks = tasks,
        goalTaskDependencies = dependencies, goalHabitLinks = links, goalSessions = sessions
    )

    private fun invalid(value: LifeTrackBackup, messagePart: String? = null) {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(value, today)
        }
        if (messagePart != null) assertTrue("expected '$messagePart' in: ${exception.message}", exception.message!!.contains(messagePart))
    }

    // ---- Round trip ----

    @Test fun `version ten round trip preserves the whole goal tree exactly`() {
        val original = backup()
        BackupValidator.validate(original, today)
        val restored = BackupValidator.json.decodeFromString<LifeTrackBackup>(BackupValidator.json.encodeToString(original))
        assertEquals(original, restored)
        BackupValidator.validate(restored, today)
        assertEquals(10, restored.formatVersion)
    }

    @Test fun `plain exports without goal data stay key-free so older apps still decode them`() {
        listOf(3, 4, 5, 6, 7, 8, 9).forEach { version ->
            val plain = LifeTrackBackup(formatVersion = version, exportedAt = 300, habits = listOf(habit), logs = emptyList(), settings = AppSettings(), freezes = emptyList())
            BackupValidator.validate(plain, today)
            val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(plain)).jsonObject
            listOf("goals", "goalPhases", "goalMilestones", "goalTasks", "goalTaskDependencies").forEach { key ->
                assertFalse("version $version must not carry $key", key in document)
            }
        }
    }

    @Test fun `version ten export carries every goal collection`() {
        val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(backup())).jsonObject
        listOf("goals", "goalPhases", "goalMilestones", "goalTasks", "goalTaskDependencies").forEach { key ->
            assertTrue("version 10 must carry $key", key in document)
        }
    }

    @Test fun `a v10 backup still carries no app lock choice`() {
        val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(backup())).jsonObject
        val settings = document.getValue("settings").jsonObject
        assertFalse("appLockEnabled must stay out of backups", "appLockEnabled" in settings)
    }

    // ---- Version gates ----

    @Test fun `goal data in an older format is rejected by the version gate`() {
        invalid(backup(formatVersion = 9), "Goal roadmaps require backup version 10")
        invalid(backup(formatVersion = 3), "Goal roadmaps require backup version 10")
    }

    @Test fun `a v9 file carrying a goals key is rejected before any write`() {
        val text = BackupValidator.json.encodeToString(backup()).replace("\"formatVersion\": 10", "\"formatVersion\": 9")
        val exception = assertThrows(Exception::class.java) { BackupValidator.decode(text, today) }
        assertTrue(exception is SerializationException || exception is IllegalArgumentException)
    }

    @Test fun `decode accepts supported versions including the current one`() {
        val restored = BackupValidator.decode(BackupValidator.json.encodeToString(backup()), today)
        assertEquals(10, restored.formatVersion)
        assertEquals(listOf(goal), restored.goals)
    }

    // ---- Referential integrity ----

    @Test fun `a task pointing at a missing goal is rejected`() =
        invalid(backup(tasks = listOf(task.copy(goalId = 99), prerequisite)), "missing goal")

    @Test fun `a phase pointing at a missing goal is rejected`() =
        invalid(backup(phases = listOf(phase.copy(goalId = 99))), "missing goal")

    @Test fun `a task's phase from another goal is rejected`() {
        val otherGoal = goal.copy(id = 7, uuid = "77777777-7777-4777-8777-777777777777", title = "Second goal")
        val foreignPhase = phase.copy(id = 8, uuid = "88888888-8888-4888-8888-888888888888", goalId = 7)
        invalid(backup(goals = listOf(goal, otherGoal), phases = listOf(phase, foreignPhase),
            tasks = listOf(task.copy(phaseId = 8), prerequisite)), "own goal")
    }

    @Test fun `a task's milestone from another goal is rejected`() {
        val otherGoal = goal.copy(id = 7, uuid = "77777777-7777-4777-8777-777777777777", title = "Second goal")
        val foreignMilestone = milestone.copy(id = 9, uuid = "99999999-9999-4999-8999-999999999999", goalId = 7, phaseId = null)
        invalid(backup(goals = listOf(goal, otherGoal), phases = listOf(phase),
            milestones = listOf(milestone, foreignMilestone),
            tasks = listOf(task.copy(milestoneId = 9), prerequisite)), "own goal")
    }

    @Test fun `a milestone's phase from another goal is rejected`() {
        val otherGoal = goal.copy(id = 7, uuid = "77777777-7777-4777-8777-777777777777", title = "Second goal")
        val foreignPhase = phase.copy(id = 8, uuid = "88888888-8888-4888-8888-888888888888", goalId = 7)
        invalid(backup(goals = listOf(goal, otherGoal), phases = listOf(phase, foreignPhase),
            milestones = listOf(milestone.copy(phaseId = 8))), "own goal")
    }

    // ---- Dependencies ----

    @Test fun `cross-goal dependencies are rejected`() {
        val otherGoal = goal.copy(id = 7, uuid = "77777777-7777-4777-8777-777777777777", title = "Second goal")
        val foreignTask = task.copy(id = 6, uuid = "66666666-6666-4666-8666-666666666666", goalId = 7, phaseId = null, milestoneId = null)
        invalid(backup(goals = listOf(goal, otherGoal), tasks = listOf(task, prerequisite, foreignTask),
            dependencies = listOf(dependency, GoalTaskDependency(dependentTaskId = 6, prerequisiteTaskId = 5))),
            "within one goal")
    }

    @Test fun `circular dependencies are rejected across any chain length`() {
        val a = task.copy(id = 4, uuid = "44444444-4444-4444-8444-444444444444")
        val b = prerequisite
        val c = task.copy(id = 6, uuid = "66666666-6666-4666-8666-666666666666", title = "Third", sortOrder = 2)
        invalid(backup(tasks = listOf(a, b, c), dependencies = listOf(
            GoalTaskDependency(4, 5), GoalTaskDependency(5, 6), GoalTaskDependency(6, 4)
        )), "cannot form a cycle")
    }

    @Test fun `self dependencies are rejected`() =
        invalid(backup(tasks = listOf(task, prerequisite.copy(sortOrder = 1)), dependencies = listOf(GoalTaskDependency(4, 4))), "cannot form a cycle")

    @Test fun `diamond dependency graphs without cycles are accepted`() {
        val a = task.copy(id = 4, uuid = "44444444-4444-4444-8444-444444444444")
        val b = prerequisite
        val c = task.copy(id = 6, uuid = "66666666-6666-4666-8666-666666666666", title = "Third", sortOrder = 2)
        val d = task.copy(id = 7, uuid = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", title = "Fourth", sortOrder = 3)
        BackupValidator.validate(backup(tasks = listOf(a, b, c, d), dependencies = listOf(
            GoalTaskDependency(4, 5), GoalTaskDependency(4, 6), GoalTaskDependency(5, 7), GoalTaskDependency(6, 7)
        )), today)
    }

    @Test fun `a dependency pointing at a missing task is rejected`() =
        invalid(backup(tasks = listOf(task), dependencies = listOf(dependency)), "missing task")

    @Test fun `duplicate dependency edges are rejected`() =
        invalid(backup(dependencies = listOf(dependency, dependency)), "Duplicate task dependencies")

    // ---- Identity ----

    @Test fun `duplicate uuids across goal records are rejected`() =
        invalid(backup(phases = listOf(phase.copy(uuid = goal.uuid))), "Duplicate goal record identifiers")

    @Test fun `non-canonical uuids are rejected`() =
        invalid(backup(goals = listOf(goal.copy(uuid = "not-a-uuid"))), "canonical UUIDs")

    @Test fun `duplicate goal row ids are rejected`() =
        invalid(backup(goals = listOf(goal, goal.copy(uuid = "abababab-abab-4bab-8bab-abababababab"))), "Duplicate goal IDs")

    @Test fun `unsaved goal rows are rejected`() =
        invalid(backup(goals = listOf(goal.copy(id = 0))), "saved IDs")

    // ---- Field validation ----

    @Test fun `completed status without its timestamp is rejected`() =
        invalid(backup(goals = listOf(goal.copy(status = GoalStatus.COMPLETED.name))), "record when it was completed")

    @Test fun `completion timestamp without completed status is rejected`() =
        invalid(backup(goals = listOf(goal.copy(completedAt = 500))), "only then")

    @Test fun `unknown statuses are rejected`() =
        invalid(backup(goals = listOf(goal.copy(status = "WEIRD"))), "Unknown goal status")

    @Test fun `a goal ending before it starts is rejected`() =
        invalid(backup(goals = listOf(goal.copy(targetDate = today.minusDays(1)))), "end before it starts")

    @Test fun `a phase ending before it starts is rejected`() =
        invalid(backup(phases = listOf(phase.copy(plannedEndDate = today.minusDays(1)))), "end before it starts")

    @Test fun `a task planned after its deadline is rejected`() =
        invalid(backup(tasks = listOf(task.copy(plannedDate = today.plusDays(9)), prerequisite)), "planned after its deadline")

    @Test fun `archived goals must carry their archive date`() =
        invalid(backup(goals = listOf(goal.copy(isArchived = true))), "archive date")

    @Test fun `excessive text lengths are rejected`() {
        invalid(backup(goals = listOf(goal.copy(title = "x".repeat(121)))), "1–120")
        invalid(backup(goals = listOf(goal.copy(description = "x".repeat(2_001)))), "2,000")
        invalid(backup(tasks = listOf(task.copy(title = "x".repeat(121)), prerequisite)), "1–120")
    }

    @Test fun `excessive estimated minutes are rejected`() =
        invalid(backup(tasks = listOf(task.copy(estimatedMinutes = 10_081), prerequisite)), "10,080")

    @Test fun `calendar dates outside the supported range are rejected`() {
        invalid(backup(goals = listOf(goal.copy(plannedStartDate = LocalDate.of(1899, 12, 31)))), "1900")
        invalid(backup(goals = listOf(goal.copy(targetDate = today.plusYears(100).plusDays(1)))), "1900")
    }

    // ---- Count caps ----

    @Test fun `count caps bound an import's graph size`() {
        val moreGoals = (2..101).map { index ->
            goal.copy(id = index.toLong(), uuid = uuid(index), title = "Goal $index")
        }
        invalid(backup(goals = listOf(goal) + moreGoals), "too many goals")

        val morePhases = (10..510).map { index ->
            phase.copy(id = index.toLong(), uuid = uuid(index), name = "Phase $index")
        }
        invalid(backup(phases = listOf(phase) + morePhases), "too many goal phases")

        val moreMilestones = (10..1010).map { index ->
            milestone.copy(id = index.toLong(), uuid = uuid(index), title = "Milestone $index", phaseId = null)
        }
        invalid(backup(milestones = listOf(milestone) + moreMilestones), "too many goal milestones")

        val moreTasks = (10..5010).map { index ->
            task.copy(id = index.toLong(), uuid = uuid(index), title = "Task $index", phaseId = null, milestoneId = null)
        }
        invalid(backup(tasks = listOf(task, prerequisite) + moreTasks, dependencies = emptyList()), "too many goal tasks")
    }

    @Test fun `the dependency count cap bounds an import's edge list`() {
        val manyTasks = (10..111).map { index ->
            task.copy(id = index.toLong(), uuid = uuid(index), title = "Task $index", phaseId = null, milestoneId = null)
        }
        val edges = buildList {
            for (a in manyTasks) for (b in manyTasks) {
                if (a.id != b.id && size < GoalValidator.MAX_DEPENDENCIES + 1) add(GoalTaskDependency(a.id, b.id))
            }
        }
        invalid(backup(tasks = listOf(task, prerequisite) + manyTasks, dependencies = edges), "too many task dependencies")
    }

    @Test fun `completed phase without its timestamp is rejected`() =
        invalid(backup(phases = listOf(phase.copy(status = PhaseStatus.COMPLETED.name))), "record when it was completed")

    @Test fun `achieved milestone without its instant is rejected`() =
        invalid(backup(milestones = listOf(milestone.copy(status = MilestoneStatus.ACHIEVED.name))), "record when it was achieved")

    @Test fun `completed task without its timestamp is rejected`() =
        invalid(backup(tasks = listOf(task.copy(status = GoalTaskStatus.COMPLETED.name), prerequisite)), "record when it was completed")

    // ---- Import normalization contract ----

    @Test fun `the normalizer trims goal text exactly like saves do`() {
        val normalized = GoalValidator.normalizeForImport(goal.copy(title = "  Trimmed goal  ", description = "  words  ", category = " Study "))
        assertEquals("Trimmed goal", normalized.title)
        assertEquals("words", normalized.description)
        assertEquals("Study", normalized.category)
        assertEquals(null, GoalValidator.normalizeForImport(goal.copy(description = "   ")).description)
        assertEquals("Trimmed", GoalValidator.normalizeForImport(phase.copy(name = " Trimmed ")).name)
        assertEquals("Trimmed", GoalValidator.normalizeForImport(task.copy(title = " Trimmed ")).title)
        assertEquals("Trimmed", GoalValidator.normalizeForImport(milestone.copy(title = " Trimmed ")).title)
    }

    // ---- Habit-goal links (v12) ----

    @Test fun `habit-goal links ride version twelve exports and round trip`() {
        val original = backup(links = listOf(habitLink), formatVersion = 12)
        BackupValidator.validate(original, today)
        val restored = BackupValidator.json.decodeFromString<LifeTrackBackup>(BackupValidator.json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(listOf(habitLink), restored.goalHabitLinks)
    }

    @Test fun `links require backup version twelve`() =
        invalid(backup(links = listOf(habitLink), formatVersion = 11), "Habit-goal links require backup version 12")

    @Test fun `a link to a missing habit is rejected`() =
        invalid(backup(links = listOf(habitLink.copy(habitId = 99)), formatVersion = 12), "missing habit")

    @Test fun `a link to a missing goal is rejected`() =
        invalid(backup(links = listOf(habitLink.copy(goalId = 99)), formatVersion = 12), "missing goal")

    @Test fun `duplicate habit-goal links are rejected`() =
        invalid(backup(links = listOf(habitLink, habitLink.copy(createdAt = 999)), formatVersion = 12), "Duplicate habit-goal links")

    @Test fun `a negative link timestamp is rejected`() =
        invalid(backup(links = listOf(habitLink.copy(createdAt = -1)), formatVersion = 12), "Invalid habit-link timestamp")

    @Test fun `plain exports without links stay key-free`() {
        val document = Json.parseToJsonElement(BackupValidator.json.encodeToString(backup())).jsonObject
        assertFalse("Habit-goal links must stay out of link-free exports", "goalHabitLinks" in document)
    }

    /** Deterministic canonical UUID per index, unique across any test list. */
    private fun uuid(index: Int): String = String.format("aaaaaaaa-bbbb-4ccc-%04x-%012x", index, index)

    // ---- Study sessions (v13, Prompt 5) ----

    private val session = GoalSession(
        id = 1, uuid = "f1000000-0000-4000-8000-000000000001", title = "Study vectors",
        plannedStartDate = LocalDate.of(2026, 11, 23), plannedStartTime = LocalTime.of(17, 0),
        plannedEndDate = LocalDate.of(2026, 11, 23), plannedEndTime = LocalTime.of(18, 30),
        timezoneId = "Europe/Berlin", status = GoalSessionStatus.PLANNED.name,
        goalId = 1, createdAt = 400, updatedAt = 400
    )

    @Test fun `a v13 backup round trips sessions and preserves actual history`() {
        val completed = session.copy(
            id = 2, uuid = "f1000000-0000-4000-8000-000000000002", title = "Past session",
            status = GoalSessionStatus.COMPLETED.name, actualStartAt = 1_000L, actualEndAt = 5_000_000L,
            goalId = null)
        val original = backup(sessions = listOf(session, completed), formatVersion = 13)
        BackupValidator.validate(original, today)
        val restored = BackupValidator.decode(BackupValidator.json.encodeToString(original), today)
        assertEquals(13, restored.formatVersion)
        assertEquals(listOf(session, completed), restored.goalSessions)
        assertEquals(1_000L, restored.goalSessions[1].actualStartAt)
        assertEquals(5_000_000L, restored.goalSessions[1].actualEndAt)
    }

    @Test fun `sessions require backup version 13`() {
        invalid(backup(sessions = listOf(session), formatVersion = 12), "Study sessions require backup version 13")
    }

    @Test fun `a v12 backup decodes without session keys and a session-free v13 export stays empty`() {
        val plain = BackupValidator.decode(BackupValidator.json.encodeToString(backup(formatVersion = 12)), today)
        assertTrue(plain.goalSessions.isEmpty())
        val v13 = BackupValidator.decode(BackupValidator.json.encodeToString(backup(formatVersion = 13)), today)
        assertTrue(v13.goalSessions.isEmpty())
    }

    @Test fun `invalid sessions are rejected by the backup validator`() {
        invalid(backup(sessions = listOf(session.copy(uuid = "not-a-uuid")), formatVersion = 13), "canonical UUID")
        invalid(backup(sessions = listOf(session.copy(timezoneId = "Not/AZone")), formatVersion = 13), "time zone")
        invalid(backup(sessions = listOf(session.copy(goalId = 99)), formatVersion = 13), "missing goal")
        invalid(backup(sessions = listOf(session.copy(
            plannedStartTime = LocalTime.of(19, 0), plannedEndTime = LocalTime.of(17, 0))), formatVersion = 13),
            "cannot end before or exactly")
        invalid(backup(sessions = listOf(session.copy(
            status = GoalSessionStatus.COMPLETED.name)), formatVersion = 13), "must record when it ended")
        invalid(backup(sessions = listOf(
            session, session.copy(id = 1,
                uuid = "f1000000-0000-4000-8000-000000000009")), formatVersion = 13), "Duplicate session IDs")
    }

    @Test fun `a session referencing a phase or task missing from the backup is rejected`() {
        invalid(backup(sessions = listOf(session.copy(phaseId = 99)), formatVersion = 13), "missing phase")
        invalid(backup(sessions = listOf(session.copy(taskId = 99)), formatVersion = 13), "missing task")
    }

    @Test fun `a session referencing a task of another goal is rejected`() {
        val otherTask = task.copy(id = 3, goalId = 2, phaseId = null, milestoneId = null, uuid = "f1000000-0000-4000-8000-000000000008")
        val otherGoal = goal.copy(id = 2, uuid = "f1000000-0000-4000-8000-000000000007")
        invalid(backup(
            goals = listOf(goal, otherGoal),
            tasks = listOf(task, prerequisite, otherTask),
            sessions = listOf(session.copy(taskId = 3, goalId = 1)), formatVersion = 13),
            "same goal")
    }
}
