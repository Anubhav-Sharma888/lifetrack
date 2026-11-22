package com.lifetrack.app.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prompt 6 recovery operations on top of the session lifecycle (Part A): explicit partial and
 * skipped outcomes, manual check-ins without fabricating history, auditable rescheduling, and
 * bounded reminder snoozes. Every rule from Prompt 5 still holds — nothing auto-completes, a
 * missed notification is never proof of a skip, and reported figures are user-owned facts.
 */
@Singleton
class SessionRecoveryRepository @Inject constructor(private val db: AppDatabase) {
    private val sessionDao = db.sessionDao()

    /**
     * Explicit outcome confirmation from the recovery flow.
     * - COMPLETED with instrumented start: records the end at the action time (Prompt 5 path).
     * - COMPLETED without a start (manual check-in): records the end at the action time and
     *   optionally the user-reported minutes — no actualStart is ever fabricated.
     * - PARTIAL: records the end (or reported minutes) that genuinely fell short of the planned
     *   window; the repository enforces the honest "shorter than planned" rule when both are known.
     * - SKIPPED: records a deliberate decision; no work evidence is accepted.
     */
    suspend fun confirmOutcome(
        sessionUuid: String,
        outcome: GoalSessionStatus,
        reportedMinutes: Int? = null,
        completedAt: Long = System.currentTimeMillis()
    ): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        require(outcome in setOf(GoalSessionStatus.COMPLETED, GoalSessionStatus.PARTIAL, GoalSessionStatus.SKIPPED)) {
            "Unsupported recovery outcome."
        }
        require(GoalSessionTransitions.legal(state, outcome) && state != outcome) {
            "A ${state.name.lowercase().replace('_', ' ')} session cannot become ${outcome.name.lowercase()}."
        }
        val plannedMinutes = plannedMinutes(session)
        if (outcome == GoalSessionStatus.PARTIAL) {
            val actual = reportedMinutes
                ?: session.actualStartAt?.let { ((completedAt - it) / 60_000L).toInt() }
            require(actual == null || plannedMinutes == null || actual < plannedMinutes) {
                "A session that ran its full planned window is Completed, not Partial."
            }
        }
        if (outcome != GoalSessionStatus.SKIPPED) {
            require(reportedMinutes != null || session.actualStartAt != null || outcome != GoalSessionStatus.COMPLETED ||
                session.actualEndAt != null) {
                "Record what you actually did — reported minutes or a start — before confirming."
            }
        }
        val updated = session.copy(
            status = outcome.name,
            actualEndAt = if (outcome == GoalSessionStatus.SKIPPED) null else completedAt,
            reportedMinutes = if (outcome == GoalSessionStatus.SKIPPED) null else reportedMinutes ?: session.reportedMinutes,
            // A recovery decision also resolves any pending snooze chain.
            snoozedUntilAt = null,
            updatedAt = nextTimestamp(completedAt, session.createdAt, session.updatedAt)
        )
        GoalSessionValidator.validateStatusPair(updated.status, updated.actualStartAt, updated.actualEndAt, updated.reportedMinutes)
        sessionDao.updateSession(updated)
        updated
    }

    /**
     * Reschedules an incomplete session: the CURRENT planned window is copied into the row's
     * originalPlanned* audit fields on the FIRST move only (later moves keep the earliest plan),
     * the planned fields carry the new intent, and reminders are reconciled by the caller.
     */
    suspend fun reschedule(
        sessionUuid: String,
        newStartDate: LocalDate, newStartTime: LocalTime,
        newEndDate: LocalDate, newEndTime: LocalTime
    ): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        require(state in setOf(GoalSessionStatus.PLANNED, GoalSessionStatus.IN_PROGRESS)) {
            "Only planned or in-progress sessions can be rescheduled."
        }
        require(!newEndDate.atTime(newEndTime).isBefore(newStartDate.atTime(newStartTime)) &&
            java.time.Duration.between(newStartDate.atTime(newStartTime), newEndDate.atTime(newEndTime)).toMinutes() >= 1) {
            "A session cannot end before or exactly when it starts."
        }
        val today = LocalDate.now()
        val originals = if (session.originalPlannedStartDate == null) {
            // First move: preserve the original plan verbatim. Later moves keep the earliest.
            session.copy(
                originalPlannedStartDate = session.plannedStartDate,
                originalPlannedStartTime = session.plannedStartTime,
                originalPlannedEndDate = session.plannedEndDate,
                originalPlannedEndTime = session.plannedEndTime
            )
        } else session
        val updated = originals.copy(
            plannedStartDate = newStartDate, plannedStartTime = newStartTime,
            plannedEndDate = newEndDate, plannedEndTime = newEndTime,
            snoozedUntilAt = null,
            updatedAt = nextTimestamp(System.currentTimeMillis(), originals.createdAt, originals.updatedAt)
        )
        GoalSessionValidator.validatePlannedWindow(updated, today)
        GoalSessionValidator.validateStatusPair(updated.status, updated.actualStartAt, updated.actualEndAt, updated.reportedMinutes)
        sessionDao.updateSession(updated)
        updated
    }

    /**
     * Snoozes the end check-in reminder for a session: bounded count (≤ [MAX_SESSION_SNOOZES]),
     * reminder-only (the planned window is untouched), cleared by any outcome or reschedule.
     */
    suspend fun snoozeCheckIn(sessionUuid: String, untilEpochMillis: Long): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        require(state in setOf(GoalSessionStatus.PLANNED, GoalSessionStatus.IN_PROGRESS)) {
            "Only planned or in-progress sessions can snooze their check-in."
        }
        require(session.snoozeCount < MAX_SESSION_SNOOZES) {
            "This session has reached its snooze limit. Review it now or let it stay in the recovery list."
        }
        require(untilEpochMillis > System.currentTimeMillis()) {
            "The snoozed reminder must be in the future."
        }
        val updated = session.copy(
            snoozedUntilAt = untilEpochMillis,
            snoozeCount = session.snoozeCount + 1,
            updatedAt = nextTimestamp(System.currentTimeMillis(), session.createdAt, session.updatedAt)
        )
        sessionDao.updateSession(updated)
        updated
    }

    /** Bounded recovery candidates for the UI: unreviewed past sessions and partial outcomes. */
    suspend fun recoveryCandidates(today: LocalDate, limit: Int = 100): List<GoalSession> =
        sessionDao.recoveryCandidates(today.toEpochDay(), limit)

    /** Honest actual-minutes helper: reported minutes win when no instrumented pair exists. */
    fun actualMinutes(session: GoalSession): Int? {
        if (session.reportedMinutes != null) return session.reportedMinutes
        val start = session.actualStartAt
        val end = session.actualEndAt
        return if (start != null && end != null && end >= start) ((end - start) / 60_000L).toInt() else null
    }

    private fun plannedMinutes(session: GoalSession): Int? = runCatching {
        java.time.Duration.between(
            session.plannedStartDate.atTime(session.plannedStartTime),
            session.plannedEndDate.atTime(session.plannedEndTime)
        ).toMinutes().toInt()
    }.getOrNull()
}
