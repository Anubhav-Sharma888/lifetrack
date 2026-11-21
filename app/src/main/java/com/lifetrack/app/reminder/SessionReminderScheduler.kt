package com.lifetrack.app.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lifetrack.app.data.GoalSession
import com.lifetrack.app.data.GoalSessionStatus
import com.lifetrack.app.data.SCHEDULING_HORIZON_DAYS
import com.lifetrack.app.data.SessionRepository
import com.lifetrack.app.data.SessionTimePolicy
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Scheduling facade for session reminders. Room commits are the canonical state; WorkManager work
 * is a best-effort projection reconciled idempotently after every relevant lifecycle event — the
 * two cannot be one atomic transaction, so every worker re-validates its session at delivery time
 * (see [SessionReminderWorker]). Unique work names (`session-start-<uuid>` / `session-end-<uuid>`)
 * make upserts and cancellations collision-safe: editing a session replaces its obsolete work
 * under the same name, and no edit can ever leave two live jobs for one event.
 */
object SessionReminderScheduler {
    private fun startWorkName(uuid: String) = "lifetrack-session-start-$uuid"
    private fun endWorkName(uuid: String) = "lifetrack-session-end-$uuid"

    /** Schedules (or replaces) both reminder jobs for one session. Elapsed and horizon-distant
     *  events are simply not scheduled — no unbounded job growth. */
    fun upsert(context: Context, session: GoalSession) {
        val work = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        work.cancelUniqueWork(startWorkName(session.uuid))
        work.cancelUniqueWork(endWorkName(session.uuid))
        val horizon = now + TimeUnit.DAYS.toMillis(SCHEDULING_HORIZON_DAYS.toLong())
        val state = GoalSessionStatus.fromStored(session.status)
        val startAt = runCatching { SessionTimePolicy.startInstant(session).toEpochMilli() }.getOrNull()
        if (session.startReminder && state == GoalSessionStatus.PLANNED && startAt != null &&
            startAt > now && startAt <= horizon
        ) {
            work.enqueueUniqueWork(
                startWorkName(session.uuid), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SessionReminderWorker>()
                    .setInitialDelay(startAt - now, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(
                        SessionReminderWorker.INPUT_UUID to session.uuid,
                        SessionReminderWorker.INPUT_EVENT to SessionReminderWorker.EVENT_START,
                        SessionReminderWorker.INPUT_EXPECTED_AT to startAt
                    ))
                    .build()
            )
        }
        val endAt = runCatching { SessionTimePolicy.endInstant(session).toEpochMilli() }.getOrNull()
        if (session.endCheckIn && (state == GoalSessionStatus.PLANNED || state == GoalSessionStatus.IN_PROGRESS) &&
            endAt != null && endAt > now && endAt <= horizon
        ) {
            work.enqueueUniqueWork(
                endWorkName(session.uuid), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SessionReminderWorker>()
                    .setInitialDelay(endAt - now, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(
                        SessionReminderWorker.INPUT_UUID to session.uuid,
                        SessionReminderWorker.INPUT_EVENT to SessionReminderWorker.EVENT_END,
                        SessionReminderWorker.INPUT_EXPECTED_AT to endAt
                    ))
                    .build()
            )
        }
    }

    /** Cancels both reminder jobs and any posted notifications for one session. */
    fun cancel(context: Context, sessionUuid: String) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(startWorkName(sessionUuid))
        work.cancelUniqueWork(endWorkName(sessionUuid))
        SessionReminderWorker.cancel(context, sessionUuid)
    }

    /** Full reconciliation after restore, reset or app start: the bounded upcoming list is the
     *  schedule's source of truth — upserting every eligible session is idempotent because the
     *  unique names replace, never duplicate, existing jobs. */
    suspend fun reconcileAll(context: Context, repository: SessionRepository) {
        repository.upcomingList(LocalDate.now()).forEach { session -> upsert(context, session) }
    }

    /** Nothing about a worker's identity depends on wall-clock instants, but tests need the same
     *  delay computation the scheduler uses — exposed here as the single source. */
    fun delayMillis(session: GoalSession, at: Instant, isStart: Boolean): Long? {
        val planned = if (isStart) SessionTimePolicy.startInstant(session) else SessionTimePolicy.endInstant(session)
        val delay = planned.toEpochMilli() - at.toEpochMilli()
        return if (delay > 0) delay else null
    }
}
