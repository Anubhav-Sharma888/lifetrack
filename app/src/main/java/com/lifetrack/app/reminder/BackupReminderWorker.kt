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
import com.lifetrack.app.widget.backgroundRepository
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A local reminder only. It never reads or uploads the contents of a backup. */
class BackupReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val settings = backgroundRepository(applicationContext).settings.first()
        val lastExport = settings.lastExportAt
        val thirtyDays = Duration.ofDays(30).toMillis()
        val backupAge = lastExport?.let { System.currentTimeMillis() - it }
        if (!settings.backupReminder ||
            (backupAge != null && backupAge >= 0 && backupAge < thirtyDays)
        ) {
            Result.success()
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Result.success()
        } else {
            notifyBackupReminder(applicationContext)
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

private fun notifyBackupReminder(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            BACKUP_CHANNEL,
            "Backup reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A gentle monthly reminder to save a private copy of your data."
        },
    )
    val intent = Intent(context, MainActivity::class.java)
        .putExtra("open_settings", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pendingIntent = PendingIntent.getActivity(
        context,
        BACKUP_NOTIFICATION_ID,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, BACKUP_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setContentTitle("Keep your progress safe")
        .setContentText("Export a LifeTrack backup to your computer or a place you choose.")
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "Your habits live only on this device. Save a JSON backup to your computer or a folder you choose, such as Google Drive.",
        ))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    // Check again: permission can change between the worker's first check and posting.
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(BACKUP_NOTIFICATION_ID, notification)
    }
}

object BackupReminderScheduler {
    /** KEEP preserves the existing due date when the app process starts again. */
    fun sync(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(BACKUP_WORK_NAME)
            NotificationManagerCompat.from(context).cancel(BACKUP_NOTIFICATION_ID)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupReminderWorker>(30, TimeUnit.DAYS)
            .setInitialDelay(30, TimeUnit.DAYS)
            .build()
        manager.enqueueUniquePeriodicWork(BACKUP_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

private const val BACKUP_WORK_NAME = "lifetrack-monthly-backup"
private const val BACKUP_CHANNEL = "lifetrack-backup-reminders"
private const val BACKUP_NOTIFICATION_ID = 4201
