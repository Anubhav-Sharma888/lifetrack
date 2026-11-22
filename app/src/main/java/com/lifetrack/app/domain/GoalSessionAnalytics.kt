package com.lifetrack.app.domain

import com.lifetrack.app.data.GoalSession
import com.lifetrack.app.data.GoalSessionStatus
import java.time.LocalDate

/**
 * Pure, deterministic goal-session analytics (Prompt 6, Part A): no Compose, no Room, no system
 * clock — every function takes its inputs and a `today` anchor so results are reproducible.
 *
 * Honesty rules (PRD §6.H):
 * - Only genuinely recorded or explicitly reported minutes count as actual effort. Planned time
 *   is never treated as studied time.
 * - Completion rates are null ("not enough data") when no planned sessions exist in the window —
 *   an empty plan is not 100% adherence.
 * - Skipped sessions stay in the planned denominator; they are deliberate non-work, and silently
 *   excluding them would inflate adherence.
 * - A session's minutes count toward its linked goal once. A session linked to a task counts
 *   toward the task's goal through that task alone — the task→goal roll-up never adds it twice.
 * - Habit streaks are a different unit of work; this engine never reads habit logs.
 */
object GoalSessionAnalytics {

    /** One outcome bucket per session row, resolved once and reused by every metric. */
    enum class Outcome { COMPLETED, PARTIAL, SKIPPED, AWAITING_REVIEW, UPCOMING, IN_PROGRESS, CANCELED }

    fun outcomeOf(session: GoalSession, today: LocalDate): Outcome {
        val state = GoalSessionStatus.fromStored(session.status)
        return when {
            state == GoalSessionStatus.COMPLETED -> Outcome.COMPLETED
            state == GoalSessionStatus.PARTIAL -> Outcome.PARTIAL
            state == GoalSessionStatus.SKIPPED -> Outcome.SKIPPED
            state == GoalSessionStatus.CANCELED -> Outcome.CANCELED
            state == GoalSessionStatus.IN_PROGRESS -> Outcome.IN_PROGRESS
            // Derived: planned end passed without confirmation.
            session.plannedEndDate.isBefore(today) -> Outcome.AWAITING_REVIEW
            else -> Outcome.UPCOMING
        }
    }

    /** Actual minutes: user-reported minutes win when no instrumented pair exists; instrumented
     *  start/end otherwise; a session with neither contributes 0 (planned time is not study time). */
    fun actualMinutes(session: GoalSession): Int {
        if (session.reportedMinutes != null) return session.reportedMinutes
        val start = session.actualStartAt
        val end = session.actualEndAt
        if (start != null && end != null && end >= start) return ((end - start) / 60_000L).toInt()
        return 0
    }

    fun plannedMinutes(session: GoalSession): Int = runCatching {
        java.time.Duration.between(
            session.plannedStartDate.atTime(session.plannedStartTime),
            session.plannedEndDate.atTime(session.plannedEndTime)
        ).toMinutes().toInt().coerceAtLeast(0)
    }.getOrDefault(0)

    /** Sessions whose planned window intersects [from, to] inclusive (calendar-date overlap). */
    private fun inRange(sessions: List<GoalSession>, from: LocalDate, to: LocalDate): List<GoalSession> =
        sessions.filter { it.plannedStartDate <= to && it.plannedEndDate >= from }

    /** Aggregate metrics for one window. Every rate is null when its denominator is empty. */
    data class WindowReport(
        val plannedSessions: Int,
        val completed: Int,
        val partial: Int,
        val skipped: Int,
        val awaitingReview: Int,
        val plannedMinutes: Int,
        val actualMinutes: Int,
        /** Completed ÷ (planned non-canceled sessions); null when nothing was planned. */
        val completionRate: Int?,
        /** Actual ÷ planned minutes; null when no planned minutes exist. */
        val adherenceRate: Int?
    )

    fun windowReport(sessions: List<GoalSession>, from: LocalDate, to: LocalDate, today: LocalDate): WindowReport {
        val window = inRange(sessions, from, to).filter {
            GoalSessionStatus.fromStored(it.status) != GoalSessionStatus.CANCELED
        }
        val outcomes = window.map { outcomeOf(it, today) }
        val plannedCount = window.size
        val completed = outcomes.count { it == Outcome.COMPLETED }
        val partial = outcomes.count { it == Outcome.PARTIAL }
        val skipped = outcomes.count { it == Outcome.SKIPPED }
        val awaiting = outcomes.count { it == Outcome.AWAITING_REVIEW }
        val plannedMinutes = window.sumOf { plannedMinutes(it) }
        val actualMinutes = window.filter { it.status != GoalSessionStatus.SKIPPED.name }.sumOf { actualMinutes(it) }
        return WindowReport(
            plannedSessions = plannedCount,
            completed = completed,
            partial = partial,
            skipped = skipped,
            awaitingReview = awaiting,
            plannedMinutes = plannedMinutes,
            actualMinutes = actualMinutes,
            completionRate = if (plannedCount == 0) null else (completed * 100 / plannedCount),
            adherenceRate = if (plannedMinutes == 0) null else (actualMinutes * 100 / plannedMinutes)
        )
    }

    /** Weekly effort buckets (ISO weeks, Monday-anchored) across the session history. */
    data class WeeklyEffort(val weekStart: LocalDate, val plannedMinutes: Int, val actualMinutes: Int)

    fun weeklyEffort(sessions: List<GoalSession>, from: LocalDate, to: LocalDate, today: LocalDate): List<WeeklyEffort> {
        val window = inRange(sessions, from, to).filter {
            GoalSessionStatus.fromStored(it.status) != GoalSessionStatus.CANCELED
        }
        val byWeek = window.groupBy { session ->
            // Anchor each session to the Monday of its planned start week.
            session.plannedStartDate.minusDays((session.plannedStartDate.dayOfWeek.value - 1).toLong())
        }
        return byWeek.keys.sorted().map { weekStart ->
            val rows = byWeek.getValue(weekStart)
            WeeklyEffort(
                weekStart = weekStart,
                plannedMinutes = rows.sumOf { plannedMinutes(it) },
                actualMinutes = rows.filter { it.status != GoalSessionStatus.SKIPPED.name }.sumOf { actualMinutes(it) }
            )
        }
    }

    /** Per-goal effort roll-up: minutes and outcome counts attributed to each linked goal.
     *  Sessions linked to a task count toward that task's goal through this map alone — the
     *  caller must not add them again from the task's own list. */
    data class GoalEffort(
        val goalId: Long,
        val plannedMinutes: Int,
        val actualMinutes: Int,
        val completed: Int,
        val partial: Int,
        val awaitingReview: Int
    )

    fun goalEffort(sessions: List<GoalSession>, from: LocalDate, to: LocalDate, today: LocalDate): List<GoalEffort> {
        val window = inRange(sessions, from, to).filter { it.goalId != null }
        return window.groupBy { it.goalId!! }.map { (goalId, rows) ->
            val outcomes = rows.map { outcomeOf(it, today) }
            GoalEffort(
                goalId = goalId,
                plannedMinutes = rows.sumOf { plannedMinutes(it) },
                actualMinutes = rows.filter { it.status != GoalSessionStatus.SKIPPED.name }.sumOf { actualMinutes(it) },
                completed = outcomes.count { it == Outcome.COMPLETED },
                partial = outcomes.count { it == Outcome.PARTIAL },
                awaitingReview = outcomes.count { it == Outcome.AWAITING_REVIEW }
            )
        }.sortedByDescending { it.actualMinutes }
    }

    /** Upcoming workload: planned minutes for sessions starting today or later. */
    fun upcomingPlannedMinutes(sessions: List<GoalSession>, today: LocalDate): Int =
        sessions.filter {
            it.plannedStartDate >= today &&
                GoalSessionStatus.fromStored(it.status) == GoalSessionStatus.PLANNED
        }.sumOf { plannedMinutes(it) }

    /** Delay against the ORIGINAL plan for a rescheduled session, in whole days (≥ 0 when moved
     *  later, < 0 when moved earlier). Null for sessions never moved. */
    fun rescheduleDelayDays(session: GoalSession): Int? {
        val original = session.originalPlannedStartDate ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(original, session.plannedStartDate).toInt()
    }
}
