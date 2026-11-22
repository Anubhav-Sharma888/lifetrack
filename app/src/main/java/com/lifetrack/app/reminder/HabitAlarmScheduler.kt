package com.lifetrack.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.lifetrack.app.data.HabitAlarm
import com.lifetrack.app.domain.HabitAlarmRecurrenceEngine
import com.lifetrack.app.widget.backgroundAlarmRepository
import kotlinx.coroutines.launch

/**
 * Exact-alarm scheduling for ringing habit alarms (Prompt 6, Part B), following the Android
 * alarm-clock pattern: one [AlarmManager.setAlarmClock] PendingIntent per alarm definition holds
 * the NEXT occurrence only. The data URI is stable per alarm (`lifetrack://alarm/<uuid>`) and
 * PendingIntents are created with FLAG_UPDATE_CURRENT, so scheduling the next occurrence
 * atomically replaces the previous job; the occurrence instant itself travels in extras and is
 * revalidated at delivery. No unbounded enqueueing, no always-running loop.
 *
 * Permission policy (Stage 12): `SCHEDULE_EXACT_ALARM` is checked via `canScheduleExactAlarms()`
 * before every schedule; when unavailable, alarms fall back to `setAndAllowWhileIdle` (inexact)
 * and the UI labels the habit "Best-effort reminder — exact ringing time not available."
 * Revocation is reconciled by the boot/permission receiver and at the next delivery.
 */
object HabitAlarmScheduler {
    const val ACTION_ALARM_FIRE = "com.lifetrack.app.action.ALARM_FIRE"
    const val ACTION_SNOOZED_FIRE = "com.lifetrack.app.action.ALARM_SNOOZED_FIRE"
    const val EXTRA_ALARM_UUID = "alarm_uuid"
    const val EXTRA_HABIT_UUID = "habit_uuid"
    const val EXTRA_OCCURRENCE_MILLIS = "occurrence_millis"
    const val EXTRA_SNOOZE_COUNT = "snooze_count"

    fun canScheduleExactAlarms(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms())

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun alarmUri(alarmUuid: String): Uri = Uri.parse("lifetrack://alarm/$alarmUuid")

    private fun firePendingIntent(context: Context, action: String, alarmUuid: String,
                                  occurrenceMillis: Long, snoozeCount: Int): PendingIntent {
        val intent = Intent(context, HabitAlarmReceiver::class.java)
            .setAction(action)
            .setData(alarmUri(alarmUuid))
            .putExtra(EXTRA_ALARM_UUID, alarmUuid)
            .putExtra(EXTRA_OCCURRENCE_MILLIS, occurrenceMillis)
            .putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        return PendingIntent.getBroadcast(
            context, ("alarm-fire-$alarmUuid").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun existingFirePendingIntent(context: Context, alarmUuid: String): PendingIntent? {
        val intent = Intent(context, HabitAlarmReceiver::class.java)
            .setAction(ACTION_ALARM_FIRE).setData(alarmUri(alarmUuid))
        return PendingIntent.getBroadcast(
            context, ("alarm-fire-$alarmUuid").hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmOpenIntent(context: Context, alarmUuid: String): PendingIntent {
        val intent = Intent(context, com.lifetrack.app.MainActivity::class.java)
            .setData(Uri.parse("lifetrack://alarm/$alarmUuid"))
            .putExtra("open_alarm", alarmUuid)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, ("open-alarm-$alarmUuid").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedules the next occurrence for one alarm definition, atomically replacing any job. */
    fun upsert(context: Context, alarm: HabitAlarm) {
        val manager = alarmManager(context)
        val now = System.currentTimeMillis()
        val next = HabitAlarmRecurrenceEngine.nextOccurrenceMillis(alarm, now)
        if (next == null) {
            cancel(context, alarm.uuid)
            return
        }
        val exact = canScheduleExactAlarms(context)
        val pending = firePendingIntent(context, ACTION_ALARM_FIRE, alarm.uuid, next, snoozeCount = 0)
        try {
            if (exact) manager.setAlarmClock(AlarmManager.AlarmClockInfo(next, alarmOpenIntent(context, alarm.uuid)), pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } catch (error: SecurityException) {
            // Permission revoked between the check and the call: fall back to inexact delivery.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }

    /** Schedules one snoozed occurrence (count bounded by the receiver). */
    fun scheduleSnoozed(context: Context, alarm: HabitAlarm,
                        occurrenceMillis: Long, snoozeCount: Int) {
        val manager = alarmManager(context)
        val pending = firePendingIntent(context, ACTION_SNOOZED_FIRE, alarm.uuid, occurrenceMillis, snoozeCount)
        val exact = canScheduleExactAlarms(context)
        try {
            if (exact) manager.setAlarmClock(AlarmManager.AlarmClockInfo(occurrenceMillis, alarmOpenIntent(context, alarm.uuid)), pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrenceMillis, pending)
        } catch (error: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrenceMillis, pending)
        }
    }

    /** Cancels the live job and any posted notification for one alarm definition. */
    fun cancel(context: Context, alarmUuid: String) {
        existingFirePendingIntent(context, alarmUuid)?.let { alarmManager(context).cancel(it) }
        AlarmRingNotifications.cancel(context, alarmUuid)
    }

    /** Full reconciliation after restore/reset/boot/timezone/permission changes: one live job
     *  per enabled alarm of a non-archived habit, nothing else. */
    fun reconcileAll(context: Context) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            try {
                val repository = backgroundAlarmRepository(context)
                repository.allAlarms().forEach { alarm ->
                    val habit = repository.habitById(alarm.habitId)
                    if (habit == null || habit.isArchived || !alarm.enabled) {
                        cancel(context, alarm.uuid)
                    } else {
                        upsert(context, alarm)
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The next lifecycle event (boot, edit, delivery) reconciles again.
            }
        }
    }
}

/** The receiver that validates and delivers one alarm occurrence (Stage 17 staleness checks). */
class HabitAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmUuid = intent.getStringExtra(HabitAlarmScheduler.EXTRA_ALARM_UUID) ?: return
        val occurrenceMillis = intent.getLongExtra(HabitAlarmScheduler.EXTRA_OCCURRENCE_MILLIS, -1L)
        val habitUuid = intent.getStringExtra(HabitAlarmScheduler.EXTRA_HABIT_UUID).orEmpty()
        val snoozeCount = intent.getIntExtra(HabitAlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        if (occurrenceMillis <= 0) return
        val pending = goAsync()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        scope.launch {
            try {
                val repository = backgroundAlarmRepository(context)
                val alarm = repository.alarmByUuid(alarmUuid) ?: return@launch
                val habit = repository.habitById(alarm.habitId) ?: return@launch
                if (!alarm.enabled || habit.isArchived) return@launch
                // For regular occurrences: verify the occurrence is still the next expected.
                // For snoozed occurrences: verify snooze bounds (snooze does not follow recurrence).
                if (intent.action == HabitAlarmScheduler.ACTION_ALARM_FIRE) {
                    val expected = HabitAlarmRecurrenceEngine.nextOccurrenceMillis(alarm, occurrenceMillis - 1)
                    if (expected == null || kotlin.math.abs(expected - occurrenceMillis) > 60_000L) return@launch
                } else if (intent.action == HabitAlarmScheduler.ACTION_SNOOZED_FIRE) {
                    if (snoozeCount <= 0 || snoozeCount > com.lifetrack.app.data.MAX_ALARM_SNOOZES) return@launch
                } else {
                    return@launch
                }
                AlarmRingService.start(context, alarm, occurrenceMillis, snoozeCount, habit.name, habit.icon, habit.id)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A failed delivery is not retried by a crashed receiver; the next occurrence
                // still fires and the user can always open the habit to act directly.
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reconciles the alarm schedule after reboot, package replacement, timezone/clock changes and
 *  exact-alarm permission state changes — supported platform broadcasts only. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action in setOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
            )
        ) {
            HabitAlarmScheduler.reconcileAll(context)
        }
    }
}
