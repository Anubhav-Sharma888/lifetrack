package com.lifetrack.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lifetrack.app.MainActivity
import com.lifetrack.app.widget.backgroundRepository
import com.lifetrack.app.widget.refreshWidgets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class HabitReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { return try {
        if (!notificationsAllowed(applicationContext)) return Result.success()
        val repository = backgroundRepository(applicationContext)
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val snapshot = repository.readSnapshot(date)
        val minute = now.hour * 60 + now.minute
        val logs = snapshot.logs.filter { it.date == date }.associateBy { it.habitId }
        snapshot.habits.filter { ReminderPolicy.isDue(it, logs[it.id], date, minute, snapshot.pauses) }.forEach { habit ->
            if (repository.claimReminder(habit.id, date, expectedCreatedAt = habit.createdAt)) {
                if (!notificationsAllowed(applicationContext)) {
                    repository.releaseReminderClaim(habit.id, date, habit.createdAt)
                    return@forEach
                }
                try {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(applicationContext, 0,
                    Intent(applicationContext, MainActivity::class.java).setData(Uri.parse("lifetrack://habit/${habit.id}"))
                        .putExtra("open_habit", habit.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val complete = PendingIntent.getBroadcast(applicationContext, 0,
                    Intent(applicationContext, CompleteReminderReceiver::class.java)
                        .setData(Uri.parse("lifetrack://complete/${habit.id}/$date"))
                        .putExtra("habit_id", habit.id).putExtra("created_at", habit.createdAt)
                        .putExtra("date", date.toEpochDay()),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val publicVersion = NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("LifeTrack")
                    .setContentText("A little progress awaits.").build()
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_today)
                    .setContentTitle("${habit.icon} ${habit.name}")
                    .setContentText("A small step, on your own terms.")
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                    .addAction(android.R.drawable.checkbox_on_background, "Complete", complete).build()
                // Keep the revocable permission check adjacent to the platform call as well as
                // the earlier policy check, so both runtime races and static lint are covered.
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(applicationContext).notify("habit-${habit.id}", 1, notification)
                } else {
                    repository.releaseReminderClaim(habit.id, date, habit.createdAt)
                }
                } catch (error: Exception) {
                    repository.releaseReminderClaim(habit.id, date, habit.createdAt)
                    throw error
                }
            }
        }
        Result.success()
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { Result.retry() }
    }
}

/** A stale notification cannot complete yesterday or a replacement habit from a restored backup. */
class CompleteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("habit_id", -1)
        val created = intent.getLongExtra("created_at", -1)
        val epochDay = intent.getLongExtra("date", Long.MIN_VALUE)
        if (id <= 0 || created < 0 || epochDay != LocalDate.now().toEpochDay()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                backgroundRepository(context).completeHabit(id, LocalDate.ofEpochDay(epochDay), expectedCreatedAt = created)
                NotificationManagerCompat.from(context).cancel("habit-$id", 1)
                refreshWidgets(context)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The app remains the recovery path when a habit changes. */ }
            finally { pending.finish() }
        }
    }
}

object HabitReminderScheduler {
    fun sync(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HabitReminderWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(1, TimeUnit.MINUTES).build())
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
private const val CHANNEL = "lifetrack-habit-reminders"
private const val WORK = "lifetrack-habit-reminder-scan"
