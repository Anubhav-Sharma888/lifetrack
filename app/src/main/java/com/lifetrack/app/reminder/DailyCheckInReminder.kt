package com.lifetrack.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.lifetrack.app.domain.Analytics
import com.lifetrack.app.widget.backgroundRepository
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A user-set daily time that nudges about still-open habits. Stored in its own
 * DataStore file, deliberately outside the Room backup state so the portable
 * backup format stays identical across platforms.
 */
private val Context.dailyReminderData by preferencesDataStore(name = "daily_reminder_prefs")

@Singleton
class DailyReminderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val minutesKey = intPreferencesKey("daily_reminder_minutes")

    val minutes: Flow<Int?> = context.dailyReminderData.data.map { preferences -> preferences[minutesKey] }

    suspend fun set(minutesOfDay: Int?) {
        context.dailyReminderData.edit { preferences ->
            if (minutesOfDay == null) preferences.remove(minutesKey) else preferences[minutesKey] = minutesOfDay
        }
    }

    suspend fun current(): Int? = context.dailyReminderData.data.map { it[minutesKey] }.first()
}

/** Fires once per day after the chosen time when habits are still open. */
class DailyCheckInReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val today = LocalDate.now()
        val repository = backgroundRepository(applicationContext)
        val habits = repository.habits.first()
        val logs = repository.logs.first()
        val pauses = repository.pauses.first()
        val open = habits.filter { habit ->
            !habit.isArchived && Analytics.eligible(habit, today, pauses) &&
                logs.none { log -> log.habitId == habit.id && log.date == today }
        }
        if (open.isEmpty()) {
            Result.success()
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Result.success()
        } else {
            notifyDailyReminder(applicationContext, open.size, open.take(3).map { it.name })
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

private fun notifyDailyReminder(context: Context, openCount: Int, names: List<String>) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            DAILY_CHANNEL,
            "Daily check-in reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A once-a-day nudge about habits that are still open at your chosen time."
        },
    )
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pendingIntent = PendingIntent.getActivity(
        context,
        DAILY_NOTIFICATION_ID,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val namesLine = names.joinToString(" · ")
    val notification = NotificationCompat.Builder(context, DAILY_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
        .setContentTitle("Time to check in")
        .setContentText("$openCount habit${if (openCount == 1) "" else "s"} still open: $namesLine")
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "$openCount habit${if (openCount == 1) " is" else "s are"} still open today: $namesLine. Open LifeTrack to mark them now.",
        ))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(DAILY_NOTIFICATION_ID, notification)
    }
}

object DailyCheckInReminderScheduler {
    /** REPLACE re-anchors the 24-hour period whenever the chosen time changes. */
    fun sync(context: Context, minutesOfDay: Int?) {
        val manager = WorkManager.getInstance(context)
        if (minutesOfDay == null) {
            manager.cancelUniqueWork(DAILY_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(DAILY_NOTIFICATION_ID)
            return
        }
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(minutesOfDay / 60, minutesOfDay % 60))
        if (!next.atZone(now.zone).isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<DailyCheckInReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(DAILY_WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }
}

private const val DAILY_WORK_NAME = "lifetrack-daily-check-in"
private const val DAILY_CHANNEL = "lifetrack-daily-reminders"
private const val DAILY_NOTIFICATION_ID = 4202
