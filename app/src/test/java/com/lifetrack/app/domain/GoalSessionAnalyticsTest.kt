package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalSession
import com.lifetrack.app.data.GoalSessionStatus
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Honest-analytics rules (Prompt 6 Stage 7): planned vs actual separation, truthful outcomes,
 * null rates for empty windows, no skipped-session inflation, no double counting, and weekly
 * bucketing. Deterministic fixtures — no wall-clock dependence.
 */
class GoalSessionAnalyticsTest {
    private val today = LocalDate.of(2026, 11, 22)

    private fun session(
        title: String = "S",
        start: LocalDate = today,
        status: GoalSessionStatus = GoalSessionStatus.PLANNED,
        reportedMinutes: Int? = null,
        goalId: Long? = 1L,
        plannedMinutes: Int = 60
    ) = GoalSession(
        id = 0, uuid = "a0000000-0000-4000-8000-0000000000ff", title = title,
        plannedStartDate = start, plannedStartTime = LocalTime.of(17, 0),
        plannedEndDate = start, plannedEndTime = LocalTime.of(17, 0).plusMinutes(plannedMinutes.toLong()),
        timezoneId = "UTC", status = status.name, reportedMinutes = reportedMinutes, goalId = goalId
    )

    private val analytics = GoalSessionAnalytics

    // ---- Outcome derivation ----

    @Test fun `a completed session derives completed`() {
        assertEquals(GoalSessionAnalytics.Outcome.COMPLETED,
            analytics.outcomeOf(session(status = GoalSessionStatus.COMPLETED), today))
    }

    @Test fun `a planned session in the past is awaiting review, never completed`() {
        val past = session(start = today.minusDays(2))
        assertEquals(GoalSessionAnalytics.Outcome.AWAITING_REVIEW, analytics.outcomeOf(past, today))
        assertEquals(GoalSessionStatus.PLANNED.name, past.status)
    }

    @Test fun `a planned session today is upcoming`() {
        assertEquals(GoalSessionAnalytics.Outcome.UPCOMING, analytics.outcomeOf(session(start = today), today))
    }

    @Test fun `skipped sessions keep their own outcome`() {
        assertEquals(GoalSessionAnalytics.Outcome.SKIPPED,
            analytics.outcomeOf(session(status = GoalSessionStatus.SKIPPED), today))
    }

    // ---- Actual minutes: reported wins, instrumented next, planned never counts ----

    @Test fun `reported minutes win over instrumented evidence`() {
        val s = session(status = GoalSessionStatus.PARTIAL, reportedMinutes = 40).copy(
            actualStartAt = 0L, actualEndAt = 9_000_000L)
        assertEquals(40, analytics.actualMinutes(s))
    }

    @Test fun `a session without evidence contributes zero actual minutes`() {
        assertEquals(0, analytics.actualMinutes(session()))
    }

    // ---- Window report ----

    @Test fun `an empty window reports null rates instead of fake 100 percent`() {
        val report = analytics.windowReport(emptyList(), today, today, today)
        assertEquals(0, report.plannedSessions)
        assertNull(report.completionRate)
        assertNull(report.adherenceRate)
    }

    @Test fun `one completed session yields a full report`() {
        val report = analytics.windowReport(
            listOf(session(status = GoalSessionStatus.COMPLETED, reportedMinutes = 50)), today, today, today)
        assertEquals(1, report.completed)
        assertEquals(60, report.plannedMinutes)
        assertEquals(50, report.actualMinutes)
        assertEquals(100, report.completionRate)
        assertEquals(83, report.adherenceRate) // 50/60 honest rounding down
    }

    @Test fun `all-skipped windows do not inflate the completion rate and add no minutes`() {
        val report = analytics.windowReport(
            listOf(session(status = GoalSessionStatus.SKIPPED), session(status = GoalSessionStatus.SKIPPED, start = today)),
            today, today, today)
        assertEquals(2, report.skipped)
        assertEquals(0, report.actualMinutes)
        assertEquals(0, report.completionRate) // 0 of 2 — honest zero, not null
        assertEquals(0, report.adherenceRate)
    }

    @Test fun `partial minutes count as real work below the plan`() {
        val report = analytics.windowReport(
            listOf(session(status = GoalSessionStatus.PARTIAL, reportedMinutes = 40)), today, today, today)
        assertEquals(1, report.partial)
        assertEquals(40, report.actualMinutes)
    }

    @Test fun `mixed outcomes keep every bucket honest`() {
        val report = analytics.windowReport(
            listOf(
                session(title = "done", status = GoalSessionStatus.COMPLETED, reportedMinutes = 60),
                session(title = "partial", status = GoalSessionStatus.PARTIAL, reportedMinutes = 30),
                session(title = "skipped", status = GoalSessionStatus.SKIPPED),
                session(title = "awaiting", start = today.minusDays(1))
            ), today.minusDays(1), today, today)
        assertEquals(4, report.plannedSessions)
        assertEquals(1, report.completed)
        assertEquals(1, report.partial)
        assertEquals(1, report.skipped)
        assertEquals(1, report.awaitingReview)
        assertEquals(90, report.actualMinutes) // 60 + 30; skipped contributes nothing
        assertEquals(25, report.completionRate) // 1 of 4
    }

    @Test fun `sessions outside the window are excluded`() {
        val report = analytics.windowReport(
            listOf(session(start = today.plusDays(10), status = GoalSessionStatus.COMPLETED, reportedMinutes = 60)),
            today, today, today)
        assertEquals(0, report.plannedSessions)
        assertNull(report.completionRate)
    }

    @Test fun `canceled sessions are excluded from the planned denominator`() {
        val report = analytics.windowReport(
            listOf(
                session(title = "done", status = GoalSessionStatus.COMPLETED, reportedMinutes = 60),
                session(title = "canceled", status = GoalSessionStatus.CANCELED)
            ), today, today, today)
        assertEquals(1, report.plannedSessions)
        assertEquals(100, report.completionRate)
    }

    // ---- Goal attribution without double counting ----

    @Test fun `goal effort attributes each session to its own goal once`() {
        val effort = analytics.goalEffort(
            listOf(
                session(title = "a", goalId = 1L, status = GoalSessionStatus.COMPLETED, reportedMinutes = 60),
                session(title = "b", goalId = 1L, status = GoalSessionStatus.PARTIAL, reportedMinutes = 30),
                session(title = "c", goalId = 2L, status = GoalSessionStatus.COMPLETED, reportedMinutes = 45)
            ), today, today, today)
        assertEquals(2, effort.size)
        val goal1 = effort.first { it.goalId == 1L }
        assertEquals(90, goal1.actualMinutes)
        assertEquals(1, goal1.completed)
        assertEquals(1, goal1.partial)
        val goal2 = effort.first { it.goalId == 2L }
        assertEquals(45, goal2.actualMinutes)
    }

    @Test fun `a standalone session without a goal is excluded from goal effort`() {
        val effort = analytics.goalEffort(
            listOf(session(goalId = null, status = GoalSessionStatus.COMPLETED, reportedMinutes = 60)),
            today, today, today)
        assertEquals(0, effort.size)
    }

    // ---- Weekly buckets ----

    @Test fun `weekly effort buckets sessions by ISO Monday`() {
        // 2026-11-22 is a Sunday; 2026-11-16 is its week's Monday.
        val effort = analytics.weeklyEffort(
            listOf(
                session(title = "wed", start = LocalDate.of(2026, 11, 18), status = GoalSessionStatus.COMPLETED, reportedMinutes = 60),
                session(title = "sun", start = LocalDate.of(2026, 11, 22), status = GoalSessionStatus.PARTIAL, reportedMinutes = 30)
            ), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22), today)
        assertEquals(1, effort.size)
        assertEquals(LocalDate.of(2026, 11, 16), effort.first().weekStart)
        assertEquals(90, effort.first().actualMinutes)
    }

    @Test fun `effort across a month boundary lands in separate weekly buckets`() {
        val effort = analytics.weeklyEffort(
            listOf(
                session(title = "oct", start = LocalDate.of(2026, 10, 31), status = GoalSessionStatus.COMPLETED, reportedMinutes = 60),
                session(title = "nov", start = LocalDate.of(2026, 11, 2), status = GoalSessionStatus.COMPLETED, reportedMinutes = 60)
            ), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 8), today)
        assertEquals(2, effort.size)
    }

    // ---- Upcoming workload and reschedule delay ----

    @Test fun `upcoming workload counts only planned future sessions`() {
        val load = analytics.upcomingPlannedMinutes(
            listOf(
                session(title = "future", start = today.plusDays(1)),
                session(title = "past", start = today.minusDays(1)),
                session(title = "done", start = today.plusDays(1), status = GoalSessionStatus.COMPLETED)
            ), today)
        assertEquals(60, load)
    }

    @Test fun `reschedule delay is measured against the original plan`() {
        val moved = session(start = today).copy(
            originalPlannedStartDate = today.minusDays(3),
            originalPlannedStartTime = LocalTime.of(17, 0),
            originalPlannedEndDate = today.minusDays(3),
            originalPlannedEndTime = LocalTime.of(18, 0)
        )
        assertEquals(3, analytics.rescheduleDelayDays(moved))
        assertNull(analytics.rescheduleDelayDays(session(start = today)))
    }
}
