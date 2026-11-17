package com.zcshou.gogogo.run.guard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import java.util.Calendar
import kotlin.random.Random

/**
 * 定时窗口调度：windowStartMinutes + random(0..jitter)，按重复日掩码顺延，
 * 优先 setExactAndAllowWhileIdle，无精确闹钟权限时降级 setAndAllowWhileIdle。
 */
object RunScheduleAlarmManager {

    private const val REQUEST_BASE = 4000

    fun scheduleNext(context: Context, schedule: RunScheduleEntity): Long? {
        if (!schedule.isScheduleEnabled) {
            cancel(context, schedule.id)
            return null
        }
        val triggerAt = computeNextTrigger(schedule)
        val pi = pendingIntent(context, schedule.id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        return triggerAt
    }

    fun cancel(context: Context, scheduleId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pendingIntent(context, scheduleId)) }
    }

    fun computeNextTrigger(schedule: RunScheduleEntity, now: Long = System.currentTimeMillis()): Long {
        val jitter = if (schedule.windowJitterMinutes > 0) {
            Random.nextInt(0, schedule.windowJitterMinutes + 1)
        } else {
            0
        }
        for (offset in 0..8) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, schedule.windowStartMinutes / 60)
                set(Calendar.MINUTE, schedule.windowStartMinutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, jitter)
            }
            if (cal.timeInMillis <= now + 5_000) continue
            if (!isDayAllowed(schedule.repeatDaysMask, cal)) continue
            return cal.timeInMillis
        }
        return now + 24 * 3600_000L
    }

    /** bit0=周一 … bit6=周日；mask=0 视为每天 */
    fun isDayAllowed(mask: Int, cal: Calendar): Boolean {
        if (mask == 0) return true
        val index = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        return (mask shr index) and 1 == 1
    }

    private fun pendingIntent(context: Context, scheduleId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + scheduleId.toInt(),
            Intent(context, RunScheduleAlarmReceiver::class.java)
                .putExtra(RunScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}