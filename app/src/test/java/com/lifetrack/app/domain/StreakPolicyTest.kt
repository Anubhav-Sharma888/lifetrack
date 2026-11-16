package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalRevision
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.StreakProtection
import com.lifetrack.app.data.StreakPolicy as StoredStreakPolicy
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Smart Streaks decisions in isolation: explicit rows win, automatic thresholds use inclusive integer math. */
class StreakPolicyTest {
    private val start = LocalDate.of(2026, 3, 7)
    private fun habit(
        target: Int = 4,
        credit: Int = 75,
        policy: String = StoredStreakPolicy.AUTOMATIC.name,
        goals: List<GoalRevision> = emptyList()
    ) = Habit(
        id = 1, name = "Read", startDate = start, targetCount = target,
        partialCreditPercent = credit, streakPolicy = policy, goalHistory = goals
    )
    private fun log(date: LocalDate, progress: Int, status: LogStatus = LogStatus.IN_PROGRESS) = HabitLog(
        id = date.toEpochDay(), habitId = 1, date = date, status = status, progress = progress
    )
    private fun row(date: LocalDate, protect: Boolean, habitId: Long = 1) =
        StreakProtection(habitId = habitId, date = date, protectChain = protect)

    @Test fun `exactly the threshold bridges because the integer comparison is inclusive`() {
        assertTrue(StreakPolicy.bridges(habit(), log(start, 3), emptyList()) == true)               // 3*100 >= 75*4
        assertTrue(StreakPolicy.bridges(habit(target = 8), log(start, 6), emptyList()) == true)     // 600 >= 600
    }

    @Test fun `progress one unit below the threshold breaks the chain`() {
        assertTrue(StreakPolicy.bridges(habit(target = 50), log(start, 37), emptyList()) == false)  // 3700 < 3750
        assertTrue(StreakPolicy.bridges(habit(target = 3, credit = 67), log(start, 2), emptyList()) == false) // 200 < 201
    }

    @Test fun `threshold one bridges any non zero progress`() {
        assertTrue(StreakPolicy.bridges(habit(target = 100, credit = 1), log(start, 1), emptyList()) == true)
    }

    @Test fun `threshold hundred only bridges mathematically full progress`() {
        // The repository never persists an IN_PROGRESS log at its target (setProgress completes it,
        // and backup validation rejects it), but the pure rule documents the boundary exactly.
        assertTrue(StreakPolicy.bridges(habit(target = 100, credit = 100), log(start, 99), emptyList()) == false)
        assertTrue(StreakPolicy.bridges(habit(target = 100, credit = 100), log(start, 100), emptyList()) == true)
    }

    @Test fun `an explicit protect row overrides a below threshold automatic day`() {
        assertTrue(StreakPolicy.bridges(habit(), log(start, 1), listOf(row(start, true))) == true)
    }

    @Test fun `an explicit break row overrides an above threshold automatic day`() {
        assertTrue(StreakPolicy.bridges(habit(), log(start, 3), listOf(row(start, false))) == false)
    }

    @Test fun `explicit rows carry the decision even under strict and manual policies`() {
        val belowThreshold = log(start, 1)
        assertTrue(StreakPolicy.bridges(habit(policy = StoredStreakPolicy.STRICT.name), belowThreshold, listOf(row(start, true))) == true)
        assertTrue(StreakPolicy.bridges(habit(policy = StoredStreakPolicy.MANUAL.name), belowThreshold, listOf(row(start, true))) == true)
        assertTrue(StreakPolicy.bridges(habit(policy = StoredStreakPolicy.MANUAL.name), log(start, 3), listOf(row(start, false))) == false)
    }

    @Test fun `zero progress is inert even with a stale protect row`() {
        assertNull(StreakPolicy.bridges(habit(), log(start, 0), listOf(row(start, true))))
        // Defensive: the data model keeps progress non-negative, and the rule rejects anything below one.
        assertNull(StreakPolicy.bridges(habit(), log(start, -1), listOf(row(start, true))))
    }

    @Test fun `only in progress logs are eligible for bridging even with rows`() {
        LogStatus.entries.filter { it != LogStatus.IN_PROGRESS }.forEach { status ->
            assertNull("Status $status must stay outside the bridging rule",
                StreakPolicy.bridges(habit(), log(start, 3, status), listOf(row(start, true))))
        }
    }

    @Test fun `goal revisions retarget the automatic threshold as of each log date`() {
        val revised = habit(
            target = 8,
            goals = listOf(GoalRevision(start, 4, "times"), GoalRevision(start.plusDays(1), 8, "times"))
        )
        assertTrue(StreakPolicy.bridges(revised, log(start, 3), emptyList()) == true)               // 3/4 under the old goal
        assertTrue(StreakPolicy.bridges(revised, log(start.plusDays(1), 3), emptyList()) == false)  // 3/8 after the revision
        assertTrue(StreakPolicy.bridges(revised, log(start.plusDays(1), 6), emptyList()) == true)   // 6/8 meets the new goal
    }

    @Test fun `unknown stored policies fall back to strict and never bridge`() {
        assertEquals(StoredStreakPolicy.STRICT, StreakPolicy.fromStored("SIDEWAYS"))
        assertEquals(StoredStreakPolicy.STRICT, StreakPolicy.fromStored(""))
        assertEquals(StoredStreakPolicy.STRICT, StreakPolicy.fromStored("strict"))
        assertEquals(StoredStreakPolicy.AUTOMATIC, StreakPolicy.fromStored("AUTOMATIC"))
        assertEquals(StoredStreakPolicy.MANUAL, StreakPolicy.fromStored("MANUAL"))
        assertNull(StreakPolicy.bridges(habit(policy = "SIDEWAYS"), log(start, 3), emptyList()))
    }

    @Test fun `is configured tracks only the smart streaks settings`() {
        assertFalse(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.STRICT.name)))
        assertFalse(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.STRICT.name, credit = 75)))
        assertTrue(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.AUTOMATIC.name)))
        assertTrue(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.MANUAL.name)))
        assertTrue(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.STRICT.name, credit = 74)))
        assertTrue(StreakPolicy.isConfigured(habit(policy = StoredStreakPolicy.STRICT.name, credit = 76)))
    }
}
