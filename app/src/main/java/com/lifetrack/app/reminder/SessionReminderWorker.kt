package com.lifetrack.app.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifetrack.app.MainActivity
import com.lifetrack.app.data.GoalSessionStatus
import com.lifetrack.app.data.SessionTimePolicy
import com.lifetrack.app.widget.backgroundSessionRepository
import kotlinx.coroutines.CancellationException

/**
 * Best-effort session reminders (Prompt 5): one one-time WorkManager job per scheduled event,
 * identified by the session's stable UUID plus the event type. At execution the worker re-loads
 * the session and validates everything against the CURRENT database — a stale job (deleted,
 * rescheduled, completed, canceled session, changed preference or revoked permission) exits
 * without posting. Notifications never complete sessions and never start them; they only open
 * the session detail screen, behind the app-lock gate like every other entry point.
 */
class SessionReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uuid = inputData.getString(INPUT_UUID) ?: return Result.success()
        val eventType = inputData.getString(INPUT_EVENT) ?: return Result.success()
        val expectedAt = inputData.getLong(INPUT_EXPECTED_AT, -1L)
        return try {
            if (!notificationsAllowed(applicationContext)) return Result.success()
            val repository = backgroundSessionRepository(applicationContext)
            val session = repository.sessionByUuid(uuid) ?: return Result.success()
            val state = GoalSessionStatus.fromStored(session.status)
            // Eligibility at delivery time: only planned (and, for the end check-in, started)
            // sessions remind. Completed/canceled sessions never post stale reminders.
            val eligible = when (eventType) {
                EVENT_START -> state == GoalSessionStatus.PLANNED && session.startReminder
                EVENT_END -> (state == GoalSessionStatus.PLANNED || state == GoalSessionStatus.IN_PROGRESS) && session.endCheckIn
                else -> false
            }
            if (!eligible) return Result.success()
            // Schedule-match: the work carries the planned instant it was scheduled for; an edit
            // or restore that moved the session makes this job obsolete (its replacement was
            // enqueued under the same unique name).
            val plannedAt = when (eventType) {
                EVENT_START -> SessionTimePolicy.startInstant(session).toEpochMilli()
                EVENT_END -> SessionTimePolicy.endInstant(session).toEpochMilli()
                else -> return Result.success()
            }
            if (expectedAt > 0 && plannedAt != expectedAt) return Result.success()
            postNotification(session.uuid, session.title, eventType)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postNotification(sessionUuid: String, title: String, eventType: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Study sessions", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            applicationContext, (sessionUuid + eventType).hashCode(),
            Intent(applicationContext, MainActivity::class.java)
                .setData(Uri.parse("lifetrack://session/$sessionUuid"))
                .putExtra("open_session", sessionUuid)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Lock-screen privacy: the public variant carries no session title.
        val publicVersion = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle("LifeTrack")
            .setContentText("A study session reminder.").build()
        val message = when (eventType) {
            EVENT_START -> "Planned to start now. Open it when you're ready."
            else -> "The planned study session has ended. Review your progress."
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(applicationContext)
            .notify(tag(sessionUuid, eventType), NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL = "lifetrack-session-reminders"
        const val EVENT_START = "start"
        const val EVENT_END = "end"
        const val INPUT_UUID = "session_uuid"
        const val INPUT_EVENT = "session_event"
        const val INPUT_EXPECTED_AT = "session_expected_at"
        private const val NOTIFICATION_ID = 2

        fun tag(sessionUuid: String, eventType: String) = "session-$sessionUuid-$eventType"

        fun cancel(context: Context, sessionUuid: String) {
            val manager = NotificationManagerCompat.from(context)
            listOf(EVENT_START, EVENT_END).forEach { event ->
                manager.cancel(tag(sessionUuid, event), NOTIFICATION_ID)
            }
        }

        fun notificationsAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
