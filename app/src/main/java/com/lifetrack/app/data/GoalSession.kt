package com.lifetrack.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * A planned block of focused work (Version 2, Prompt 5): "study mathematics tomorrow 17:00–18:30".
 * Sessions optionally associate with one goal, phase or task; they never complete work by
 * themselves — completing a session is not completing a task (PRD §6.G).
 *
 * Planned and actual time are deliberately different kinds of fact:
 * - **Planned** is local wall-clock intent: start/end date+time in the session's own IANA
 *   [timezoneId], recorded at creation and never reinterpreted when the device clock or timezone
 *   changes. The wall-clock fields survive DST because the recorded zone resolves them
 *   deterministically (see [SessionTimePolicy]).
 * - **Actual** is UTC epoch-millis evidence of user-confirmed actions, never predicted schedule
 *   time and never fabricated: [actualStartAt] exists only because the user tapped Start, and
 *   [actualEndAt] only because the user explicitly confirmed completion. A COMPLETED session may
 *   legitimately have a null [actualStartAt] (the user confirmed work they never formally started).
 *
 * Lifecycle ([GoalSessionStatus], [GoalSessionTransitions]): PLANNED → IN_PROGRESS → COMPLETED,
 * PLANNED/IN_PROGRESS → COMPLETED (direct explicit confirmation), PLANNED/IN_PROGRESS → CANCELED.
 * A session past its end time stays PLANNED — "awaiting review" is derived at read time, never a
 * stored state, and nothing ever auto-completes. States reserved for Prompt 6 (missed, partial,
 * snoozed, recovery) are documented in [GoalSessionTransitions] but intentionally absent here.
 *
 * Parent deletion detaches links (declared SET NULL, runtime-enforced): a deleted goal, phase or
 * task leaves the session row — historical user activity — in place as a standalone record.
 */
@Serializable
enum class GoalSessionStatus {
    PLANNED, IN_PROGRESS, COMPLETED, CANCELED;
    companion object {
        /** Unknown stored values fall back to PLANNED so corrupt local rows still render. */
        fun fromStored(value: String): GoalSessionStatus = entries.firstOrNull { it.name == value } ?: PLANNED
    }
}

object GoalSessionTransitions {
    /** Implemented in Prompt 5. Prompt 6 reserves: PLANNED→(MISSED, SNOOZED), recovery re-planning,
     *  and partial-completion splits — none of which exist as states in this schema. */
    val sessions = mapOf(
        GoalSessionStatus.PLANNED to setOf(
            GoalSessionStatus.IN_PROGRESS, GoalSessionStatus.COMPLETED, GoalSessionStatus.CANCELED),
        GoalSessionStatus.IN_PROGRESS to setOf(GoalSessionStatus.COMPLETED, GoalSessionStatus.CANCELED),
        // Terminal for Prompt 5: an explicit reopen/re-plan arrives with Prompt 6's recovery engine.
        GoalSessionStatus.COMPLETED to emptySet(),
        GoalSessionStatus.CANCELED to emptySet()
    )

    fun legal(from: GoalSessionStatus, to: GoalSessionStatus): Boolean = from == to || sessions[from]?.contains(to) == true
}

/** Minutes of wall-clock time one planned session may span (a full day, cross-midnight allowed). */
const val MAX_PLANNED_DURATION_MINUTES = 1_440

/** Bounded total session count — mirrors the goal-domain caps so backups stay bounded. */
const val MAX_SESSIONS = 5_000

/** Bounded number of future sessions the reminder scheduler will track at once. */
const val MAX_UPCOMING_SESSIONS = 500

/** How far ahead a reminder may be scheduled, in days — bounds WorkManager work far in the future. */
const val SCHEDULING_HORIZON_DAYS = 366

@Serializable
@Entity(
    tableName = "goal_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = GoalPhase::class, parentColumns = ["id"], childColumns = ["phaseId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = GoalTask::class, parentColumns = ["id"], childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index("plannedStartDate"),
        Index("status"),
        Index("goalId"),
        Index("taskId")
    ]
)
@TypeConverters(GoalSessionTimeConverters::class)
data class GoalSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @Required val title: String,
    @Required val description: String? = null,
    // Planned wall-clock intent in [timezoneId] — never reinterpreted by device clock changes.
    @Required @Serializable(with = LocalDateSerializer::class) val plannedStartDate: LocalDate,
    @Required @Serializable(with = LocalTimeSerializer::class) val plannedStartTime: LocalTime,
    @Required @Serializable(with = LocalDateSerializer::class) val plannedEndDate: LocalDate,
    @Required @Serializable(with = LocalTimeSerializer::class) val plannedEndTime: LocalTime,
    @Required val timezoneId: String,
    // Actual UTC evidence of user-confirmed actions (epoch millis); null until the user acts.
    @Required val actualStartAt: Long? = null,
    @Required val actualEndAt: Long? = null,
    @Required val status: String = GoalSessionStatus.PLANNED.name,
    @Required val goalId: Long? = null,
    @Required val phaseId: Long? = null,
    @Required val taskId: Long? = null,
    @Required val startReminder: Boolean = true,
    @Required val endCheckIn: Boolean = true,
    @Required val createdAt: Long = System.currentTimeMillis(),
    @Required val updatedAt: Long = createdAt
)

/** Room column mapping for wall-clock times: LocalTime stored as nano-of-day. */
class GoalSessionTimeConverters {
    @TypeConverter fun timeToLong(value: LocalTime?): Long? = value?.toNanoOfDay()
    @TypeConverter fun longToTime(value: Long?): LocalTime? = value?.let(LocalTime::ofNanoOfDay)
}

/**
 * Deterministic DST resolution for planned wall-clock times, using java.time's documented
 * [java.time.LocalDateTime.atZone] behaviour:
 * - **Ordinary local time** resolves to exactly one instant.
 * - **DST gap** (a local time that does not exist, e.g. 02:30 on a spring-forward night) resolves
 *   to the instant AFTER the gap — the wall clock jumps forward, so the session starts when the
 *   clock next shows (or passes) the requested time.
 * - **DST overlap** (a local time that happens twice, e.g. 01:30 on a fall-back night) resolves to
 *   the EARLIER of the two instants — the first occurrence of that wall-clock time.
 * The policy is deterministic, standard-library-backed and pinned by tests against real IANA
 * zones; no ambiguous interpretation is ever persisted, because the session keeps its own
 * timezoneId and wall-clock fields rather than a stored instant.
 */
object SessionTimePolicy {
    fun startInstant(session: GoalSession): java.time.Instant =
        session.plannedStartDate.atTime(session.plannedStartTime).atZone(java.time.ZoneId.of(session.timezoneId)).toInstant()

    fun endInstant(session: GoalSession): java.time.Instant =
        session.plannedEndDate.atTime(session.plannedEndTime).atZone(java.time.ZoneId.of(session.timezoneId)).toInstant()
}

/** Shared validation for sessions, used by [SessionRepository] and the backup validator. */
object GoalSessionValidator {
    fun validateTitle(title: String) {
        require(title.isNotBlank() && title.length <= GoalValidator.MAX_TITLE_LENGTH) {
            "Session titles must contain 1–${GoalValidator.MAX_TITLE_LENGTH} characters."
        }
    }

    fun validateDescription(description: String?) {
        require(description == null || description.length <= GoalValidator.MAX_DESCRIPTION_LENGTH) {
            "Session descriptions are limited to %,d characters.".format(java.util.Locale.ROOT, GoalValidator.MAX_DESCRIPTION_LENGTH)
        }
    }

    /** Planned ordering, duration and calendar-range rules. Today anchors the range guard. */
    fun validatePlannedWindow(session: GoalSession, today: LocalDate = LocalDate.now()) {
        val rangeStart = BackupValidator.earliestDate
        val rangeEnd = today.plusYears(100)
        listOf(session.plannedStartDate, session.plannedEndDate).forEach { date ->
            require(!date.isBefore(rangeStart) && !date.isAfter(rangeEnd)) {
                "Session dates must be between 1900 and 100 years from today."
            }
        }
        require(session.plannedEndTime.isAfter(session.plannedStartTime) || session.plannedEndDate.isAfter(session.plannedStartDate)) {
            "A session cannot end before or exactly when it starts."
        }
        val durationMinutes = java.time.Duration.between(
            session.plannedStartDate.atTime(session.plannedStartTime),
            session.plannedEndDate.atTime(session.plannedEndTime)
        ).toMinutes()
        require(durationMinutes in 1..MAX_PLANNED_DURATION_MINUTES) {
            "A session must last between 1 minute and 24 hours."
        }
    }

    fun validateTimezone(timezoneId: String) {
        try {
            java.time.ZoneId.of(timezoneId)
        } catch (error: Exception) {
            throw IllegalArgumentException("The session's time zone is not recognized.")
        }
    }

    fun validateStatusPair(status: String, actualStartAt: Long?, actualEndAt: Long?) {
        val state = GoalSessionStatus.fromStored(status)
        // One-directional invariants: each state requires its own evidence and forbids
        // contradictory evidence, but a CANCELED session may keep the start instant the user
        // actually recorded before canceling — erasing it would falsify history.
        require(state != GoalSessionStatus.IN_PROGRESS || (actualStartAt != null && actualEndAt == null)) {
            "A started session must record when it started, and only then."
        }
        require(state != GoalSessionStatus.COMPLETED || actualEndAt != null) {
            "A completed session must record when it ended, and only then."
        }
        require(state != GoalSessionStatus.PLANNED || (actualStartAt == null && actualEndAt == null)) {
            "A planned session cannot carry actual times yet."
        }
        require(state != GoalSessionStatus.CANCELED || actualEndAt == null) {
            "A canceled session never ended."
        }
        require(actualStartAt == null || actualStartAt >= 0) { "Invalid session start timestamp." }
        require(actualEndAt == null || actualEndAt >= 0) { "Invalid session end timestamp." }
        require(actualStartAt == null || actualEndAt == null || actualStartAt <= actualEndAt) {
            "A session cannot end before it started."
        }
    }

    fun validateAssociations(goalId: Long?, phaseId: Long?, taskId: Long?,
                             phaseGoalIds: Map<Long, Long>, taskGoalIds: Map<Long, Long>) {
        // When a task is selected it fixes the goal; a conflicting explicit goal is rejected.
        val taskGoal = taskId?.let { taskGoalIds[it] }
        if (taskGoal != null && goalId != null) require(taskGoal == goalId) {
            "A session's task and goal must belong to the same goal."
        }
        val phaseGoal = phaseId?.let { phaseGoalIds[it] }
        if (phaseGoal != null && goalId != null) require(phaseGoal == goalId) {
            "A session's phase and goal must belong to the same goal."
        }
        if (phaseGoal != null && taskGoal != null) require(phaseGoal == taskGoal) {
            "A session's phase and task must belong to the same goal."
        }
    }

    fun validateForBackup(session: GoalSession, today: LocalDate = LocalDate.now(),
                          phaseGoalIds: Map<Long, Long> = emptyMap(),
                          taskGoalIds: Map<Long, Long> = emptyMap()) {
        require(isCanonicalUuid(session.uuid)) { "Session identifiers must be canonical UUIDs." }
        require(session.id >= 0 && session.id < Long.MAX_VALUE) { "Invalid session ID." }
        validateTitle(session.title)
        validateDescription(session.description)
        validatePlannedWindow(session, today)
        validateTimezone(session.timezoneId)
        validateStatusPair(session.status, session.actualStartAt, session.actualEndAt)
        require(session.createdAt >= 0 && session.updatedAt >= session.createdAt) { "Invalid session timestamps." }
        validateAssociations(session.goalId, session.phaseId, session.taskId, phaseGoalIds, taskGoalIds)
    }

    /** Trims imported text the same way saves do; blank descriptions become null. */
    fun normalizeForImport(session: GoalSession): GoalSession = session.copy(
        title = session.title.trim(), description = session.description?.trim()?.takeIf { it.isNotEmpty() }
    )
}
