package com.lifetrack.app.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * All session mutations pass through here with the same discipline as the goal domain: require-
 * based validation, monotonic [nextTimestamp] writes, and one Room transaction per multi-step
 * operation. The repository is intentionally Android-framework-free — WorkManager scheduling is a
 * separate reconciliation concern owned by [com.lifetrack.app.reminder.SessionReminderScheduler],
 * invoked by the UI layer after successful repository operations.
 *
 * Nothing in this file ever completes work automatically: sessions move PLANNED → IN_PROGRESS →
 * COMPLETED only on explicit user actions, and a session past its end time stays unconfirmed —
 * "awaiting review" is derived at read time, never stored.
 */
@Singleton
class SessionRepository @Inject constructor(private val db: AppDatabase) {
    private val sessionDao = db.sessionDao()
    private val goalDao = db.goalDao()

    // ---- Reads ----

    val sessions: Flow<List<GoalSession>> = sessionDao.observeSessions()
    fun upcoming(fromDay: Long): Flow<List<GoalSession>> = sessionDao.observeUpcomingSessions(fromDay)
    fun sessionsForGoal(goalId: Long): Flow<List<GoalSession>> = sessionDao.observeSessionsForGoal(goalId)
    fun observeSession(uuid: String): Flow<GoalSession?> = sessionDao.observeSessionByUuid(uuid)

    suspend fun sessionByUuid(uuid: String): GoalSession? = sessionDao.sessionByUuid(uuid)
    suspend fun sessionById(id: Long): GoalSession? = sessionDao.session(id)
    suspend fun upcomingList(today: LocalDate): List<GoalSession> =
        sessionDao.upcomingSessions(today.minusDays(1).toEpochDay()).take(MAX_UPCOMING_SESSIONS)
    suspend fun recentHistory(limit: Int): List<GoalSession> = sessionDao.recentSessions(limit)

    /** Bounded snapshot of every session — the overview's bucketing input (UI-scale read). */
    suspend fun overviewSnapshot(limit: Int = 500): List<GoalSession> = sessionDao.recentSessions(limit)
    suspend fun sessionsInProgress(): List<GoalSession> = sessionDao.sessionsInProgress()
    suspend fun sessionCount(): Int = sessionDao.sessionCount()

    // ---- Create / edit ----

    /**
     * Creates or edits a session. Creation mints the uuid and starts PLANNED; edits keep the
     * stable identity and preserve every actual historical instant — only planning fields
     * (title, description, planned window, timezone, associations, reminder preferences) move.
     * The caller reconciles reminders after success.
     */
    suspend fun save(session: GoalSession): GoalSession = db.withTransaction {
        val today = LocalDate.now()
        val existing = if (session.id == 0L) null else sessionDao.session(session.id)
        require(session.id == 0L || existing != null) { "This session no longer exists." }
        val uuid = existing?.uuid ?: session.uuid.ifBlank { UUID.randomUUID().toString() }
        val uuidOwner = sessionDao.sessionByUuid(uuid)
        require(uuidOwner == null || uuidOwner.id == session.id) { "Another session already uses this identifier." }

        // Resolve associations: a task or phase fixes the goal when none was chosen explicitly.
        val task = session.taskId?.let { goalDao.task(it) ?: throw IllegalArgumentException("The linked task no longer exists.") }
        val phase = session.phaseId?.let { goalDao.phase(it) ?: throw IllegalArgumentException("The linked phase no longer exists.") }
        val resolvedGoalId = session.goalId ?: task?.goalId ?: phase?.goalId
        resolvedGoalId?.let { goalId ->
            goalDao.goal(goalId) ?: throw IllegalArgumentException("The linked goal no longer exists.")
        }
        val goalId = resolvedGoalId
        val phaseId = phase?.let {
            require(goalId == null || it.goalId == goalId) { "A session's phase and goal must belong to the same goal." }
            it.id
        }
        val taskId = task?.let {
            require(goalId == null || it.goalId == goalId) { "A session's task and goal must belong to the same goal." }
            if (phase != null) require(it.goalId == phase.goalId) { "A session's phase and task must belong to the same goal." }
            it.id
        }

        val candidate = session.copy(
            uuid = uuid,
            goalId = goalId, phaseId = phaseId, taskId = taskId,
            // Actual evidence is structurally preserved on edit: an import, an edit or a form
            // resubmission can never fabricate or rewrite when work actually happened.
            actualStartAt = existing?.actualStartAt,
            actualEndAt = existing?.actualEndAt,
            status = existing?.status ?: GoalSessionStatus.PLANNED.name,
            createdAt = existing?.createdAt ?: nextTimestamp(System.currentTimeMillis()),
            updatedAt = nextTimestamp(System.currentTimeMillis(), existing?.createdAt ?: 0, existing?.updatedAt ?: 0)
        )
        if (existing == null) {
            require(sessionDao.sessionCount() < MAX_SESSIONS) {
                "LifeTrack cannot hold more than %,d sessions.".format(java.util.Locale.ROOT, MAX_SESSIONS)
            }
            require(candidate.status == GoalSessionStatus.PLANNED.name) { "New sessions start as PLANNED." }
        }
        GoalSessionValidator.validateTitle(candidate.title)
        GoalSessionValidator.validateDescription(candidate.description)
        GoalSessionValidator.validatePlannedWindow(candidate, today)
        GoalSessionValidator.validateTimezone(candidate.timezoneId)
        GoalSessionValidator.validateStatusPair(candidate.status, candidate.actualStartAt, candidate.actualEndAt)
        if (existing == null) {
            val rowId = sessionDao.insertSession(candidate)
            candidate.copy(id = rowId)
        } else {
            sessionDao.updateSession(candidate)
            candidate
        }
    }

    // ---- Lifecycle ----

    /** PLANNED → IN_PROGRESS: records the actual start at the action time. Idempotent-guarded —
     *  a duplicate start or a stale notification can never create a second start. */
    suspend fun start(sessionUuid: String, startedAt: Long = System.currentTimeMillis()): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        if (state != GoalSessionStatus.PLANNED) {
            throw IllegalArgumentException("A ${state.name.lowercase().replace('_', ' ')} session cannot be started.")
        }
        val updated = session.copy(
            status = GoalSessionStatus.IN_PROGRESS.name,
            actualStartAt = startedAt,
            updatedAt = nextTimestamp(startedAt, session.createdAt, session.updatedAt)
        )
        GoalSessionValidator.validateStatusPair(updated.status, updated.actualStartAt, updated.actualEndAt)
        sessionDao.updateSession(updated)
        updated
    }

    /** PLANNED/IN_PROGRESS → COMPLETED on explicit user confirmation: records the actual end at
     *  the action time. A session never formally started keeps actualStartAt null — the history
     *  stays truthful instead of fabricating a start to satisfy an invariant. */
    suspend fun confirmCompletion(sessionUuid: String, completedAt: Long = System.currentTimeMillis()): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        if (state != GoalSessionStatus.PLANNED && state != GoalSessionStatus.IN_PROGRESS) {
            throw IllegalArgumentException("A ${state.name.lowercase().replace('_', ' ')} session cannot be completed.")
        }
        val updated = session.copy(
            status = GoalSessionStatus.COMPLETED.name,
            actualEndAt = completedAt,
            updatedAt = nextTimestamp(completedAt, session.createdAt, session.updatedAt)
        )
        GoalSessionValidator.validateStatusPair(updated.status, updated.actualStartAt, updated.actualEndAt)
        sessionDao.updateSession(updated)
        updated
    }

    /** PLANNED/IN_PROGRESS → CANCELED: the row is preserved with its identity; reminders are
     *  canceled by the caller. Completed history is never canceled. */
    suspend fun cancel(sessionUuid: String): GoalSession = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        val state = GoalSessionStatus.fromStored(session.status)
        if (state != GoalSessionStatus.PLANNED && state != GoalSessionStatus.IN_PROGRESS) {
            throw IllegalArgumentException("A ${state.name.lowercase().replace('_', ' ')} session cannot be canceled.")
        }
        val updated = session.copy(
            status = GoalSessionStatus.CANCELED.name,
            updatedAt = nextTimestamp(System.currentTimeMillis(), session.createdAt, session.updatedAt)
        )
        sessionDao.updateSession(updated)
        updated
    }

    /** Explicit deletion of one session row (planning or history). Reminders are canceled by the
     *  caller. Deleting a COMPLETED session erases recorded history — the UI asks first. */
    suspend fun delete(sessionUuid: String) = db.withTransaction {
        val session = sessionDao.sessionByUuid(sessionUuid)
            ?: throw IllegalArgumentException("This session no longer exists.")
        sessionDao.deleteSessionById(session.id)
    }

    // ---- Derived read-time views ----

    /** True when the planned end has passed without user confirmation — never a stored state. */
    fun awaitingReview(session: GoalSession, now: Long = System.currentTimeMillis()): Boolean {
        val state = GoalSessionStatus.fromStored(session.status)
        if (state != GoalSessionStatus.PLANNED && state != GoalSessionStatus.IN_PROGRESS) return false
        return runCatching { SessionTimePolicy.endInstant(session).toEpochMilli() < now }.getOrDefault(false)
    }

    /** The session's planned window as epoch millis under its own recorded timezone. */
    fun plannedInstants(session: GoalSession): Pair<Long, Long>? = runCatching {
        SessionTimePolicy.startInstant(session).toEpochMilli() to SessionTimePolicy.endInstant(session).toEpochMilli()
    }.getOrNull()
}
