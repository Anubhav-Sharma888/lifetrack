package com.lifetrack.app.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifetrack.app.data.ALARM_RINGING_TIMEOUT_SECONDS
import com.lifetrack.app.data.HabitAlarm
import com.lifetrack.app.data.HabitAlarmRecurrence
import com.lifetrack.app.data.MAX_ALARM_SNOOZES
import com.lifetrack.app.domain.HabitAlarmRecurrenceEngine
import com.lifetrack.app.widget.backgroundAlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sustained-ringing foreground service (Prompt 6, Part B): when an eligible alarm occurrence
 * fires, this service plays looping alarm audio on the ALARM stream until the user dismisses or
 * snoozes — or the documented safety timeout (2 minutes) stops it, so a missed dismissal can
 * neither ring indefinitely nor drain the battery. This is not a notification chime: audio keeps
 * playing after the receiver returns because the service owns the MediaPlayer lifecycle.
 *
 * Android policy respected:
 * - Foreground service type `mediaPlayback` (the platform-supported type for owned-audio apps).
 * - Audio on USAGE_ALARM: the alarm stream honors the user's alarm volume; master volume, DND
 *   and silent mode are never overridden.
 * - Dismiss/Snooze notification actions are safe: neither marks the habit complete, neither
 *   touches habit logs, and both work without unlocking (App Lock protects in-app data only).
 * - The next legitimate occurrence is untouched: dismissing today's 06:00 alarm never cancels
 *   the scheduled 18:00 or tomorrow's occurrence.
 */
class AlarmRingService : android.app.Service() {
    private var player: MediaPlayer? = null
    private var alarmUuid: String? = null
    private var occurrenceMillis: Long = 0
    private var snoozeCount: Int = 0
    private var habitName: String? = null
    private var habitIcon: String? = null
    private var habitId: Long = 0
    private var timeoutJob: Job? = null
    private val timeoutScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val snoozeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RINGING -> {
                val uuid = alarmUuid
                stopAudio()
                stopForegroundCompat()
                snoozeScope.launch {
                    try {
                        if (uuid != null) {
                            val repository = backgroundAlarmRepository(applicationContext)
                            val alarm = repository.alarmByUuid(uuid)
                            if (alarm != null) {
                                HabitAlarmScheduler.upsert(applicationContext, alarm)
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                    } finally {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_SNOOZE_RINGING -> {
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
                val uuid = alarmUuid
                val occurrence = occurrenceMillis
                val count = snoozeCount
                stopAudio()
                stopForegroundCompat()
                snoozeScope.launch {
                    try {
                        if (uuid != null && count < MAX_ALARM_SNOOZES) {
                            // Snooze = stop current ringing + one bounded later reminder. The original
                            // occurrence stays; a further occurrence is never duplicated.
                            val repository = backgroundAlarmRepository(applicationContext)
                            val alarm = repository.alarmByUuid(uuid)
                            if (alarm != null) {
                                HabitAlarmScheduler.scheduleSnoozed(
                                    applicationContext, alarm,
                                    System.currentTimeMillis() + minutes * 60_000L, count + 1
                                )
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // A lost snooze is honest: the next regular occurrence still fires.
                    } finally {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                alarmUuid = intent?.getStringExtra(HabitAlarmScheduler.EXTRA_ALARM_UUID) ?: alarmUuid
                occurrenceMillis = intent?.getLongExtra(HabitAlarmScheduler.EXTRA_OCCURRENCE_MILLIS, 0) ?: 0
                snoozeCount = intent?.getIntExtra(HabitAlarmScheduler.EXTRA_SNOOZE_COUNT, 0) ?: 0
                habitName = intent?.getStringExtra(EXTRA_HABIT_NAME) ?: habitName
                habitIcon = intent?.getStringExtra(EXTRA_HABIT_ICON) ?: habitIcon
                habitId = intent?.getLongExtra(EXTRA_HABIT_ID, 0L) ?: habitId
            }
        }
        startForegroundCompat()
        startAudio()
        return START_NOT_STICKY
    }

    private fun startAudio() {
        stopAudio()
        player = MediaPlayer().apply {
            try {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val sound = defaultAlarmSound(applicationContext)
                if (sound != null) setDataSource(applicationContext, sound)
                isLooping = true
                prepare()
                start()
            } catch (error: Exception) {
                // Missing/broken ringtone: stop cleanly — the notification remains as the
                // fallback and the next occurrence still schedules.
                stopAudio()
            }
        }
        // Safety timeout: ringing stops after the documented bound even with no user action.
        timeoutJob?.cancel()
        timeoutJob = timeoutScope.launch {
            delay(ALARM_RINGING_TIMEOUT_SECONDS * 1_000L)
            val uuid = alarmUuid
            stopAudio()
            stopForegroundCompat()
            try {
                if (uuid != null) {
                    val repository = backgroundAlarmRepository(applicationContext)
                    val alarm = repository.alarmByUuid(uuid)
                    if (alarm != null) {
                        HabitAlarmScheduler.upsert(applicationContext, alarm)
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopAudio() {
        timeoutJob?.cancel()
        timeoutJob = null
        player?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        player = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Habit alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ringing alarms for habits that need your attention."
                enableVibration(true)
            }
        )
        val dismiss = PendingIntent.getService(
            this, ("dismiss-$alarmUuid").hashCode(),
            Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP_RINGING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getService(
            this, ("snooze-$alarmUuid").hashCode(),
            Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE_RINGING)
                .putExtra(EXTRA_SNOOZE_MINUTES, 10),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, ("open-$alarmUuid").hashCode(),
            Intent(this, com.lifetrack.app.MainActivity::class.java)
                .putExtra("open_alarm", alarmUuid)
                .apply { if (habitId > 0) putExtra("open_habit", habitId) }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicNotification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("LifeTrack alarm")
            .setContentText("Alarm ringing — dismiss or snooze.")
            .build()
        val title = if (!habitName.isNullOrBlank()) "${habitIcon.orEmpty()} $habitName".trim() else "LifeTrack alarm"
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Alarm ringing — dismiss or snooze.")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismiss)
        if (snoozeCount < MAX_ALARM_SNOOZES) {
            notificationBuilder.addAction(android.R.drawable.ic_lock_idle_lock, "Snooze 10 min", snooze)
        }
        val notification = notificationBuilder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        stopAudio()
        timeoutScope.cancel()
        snoozeScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "lifetrack-session-alarms"
        const val ACTION_STOP_RINGING = "com.lifetrack.app.action.ALARM_STOP_RINGING"
        const val ACTION_SNOOZE_RINGING = "com.lifetrack.app.action.ALARM_SNOOZE_RINGING"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        const val EXTRA_HABIT_NAME = "habit_name"
        const val EXTRA_HABIT_ICON = "habit_icon"
        const val EXTRA_HABIT_ID = "habit_id"
        private const val NOTIFICATION_ID = 3

        /** Starts the ringing service when notification permission allows it. */
        fun start(context: Context, alarm: HabitAlarm,
                  occurrenceMillis: Long, snoozeCount: Int,
                  habitName: String = "", habitIcon: String = "", habitId: Long = 0L) {
            if (!notificationsAllowed(context)) return
            val intent = Intent(context, AlarmRingService::class.java)
                .putExtra(HabitAlarmScheduler.EXTRA_ALARM_UUID, alarm.uuid)
                .putExtra(HabitAlarmScheduler.EXTRA_OCCURRENCE_MILLIS, occurrenceMillis)
                .putExtra(HabitAlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
                .putExtra(EXTRA_HABIT_NAME, habitName)
                .putExtra(EXTRA_HABIT_ICON, habitIcon)
                .putExtra(EXTRA_HABIT_ID, habitId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun notificationsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        /** The device's default alarm sound with a safe fallback; no brittle external paths. */
        fun defaultAlarmSound(context: Context): Uri? {
            val system = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            if (system != null) {
                try {
                    context.contentResolver.openInputStream(system)?.close()
                    return system
                } catch (error: Exception) {
                    // fall through to the ringtone manager
                }
            }
            return android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        }
    }
}

/** Posted-notification handle for alarm occurrences (cancel after restore/reset/delete). */
object AlarmRingNotifications {
    fun cancel(context: Context, alarmUuid: String) {
        NotificationManagerCompat.from(context).cancel("alarm-posted-$alarmUuid", 4)
    }
}
