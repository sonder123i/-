package com.zcshou.gogogo.run.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.service.RunSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 定时闹钟到点：先排次日闹钟，再拉起 RunSessionService 自动开跑；
 * Android 12+/14 后台限制导致拉起失败时，降级高优先级全屏通知。
 */
class RunScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1)
        if (scheduleId <= 0) return
        val pending = goAsync()
        // SupervisorJob 兜住单个计划查询异常，避免无名 scope 把失败吞成静默无响应
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = RunRepository(context.applicationContext)
                val schedule = repository.getSchedule(scheduleId) ?: return@launch
                if (!schedule.isScheduleEnabled) return@launch

                // 先排下一次，保证即使本次执行异常也能循环
                RunScheduleAlarmManager.scheduleNext(context, schedule)

                val route = repository.getRoute(schedule.routeId) ?: return@launch
                val startIntent = RunSessionService.startIntent(
                    context,
                    route.id,
                    schedule.targetDistanceKm,
                    schedule.targetPaceSecPerKm,
                    schedule.enableLaneDrift,
                    schedule.enableCooldownWalk,
                    schedule.linkedTargetAppPkg
                )
                try {
                    ContextCompat.startForegroundService(context, startIntent)
                } catch (e: Exception) {
                    RunScheduleNotifier.postFallback(context, schedule, route)
                }
            } catch (e: Exception) {
                // goAsync 只有 10 秒窗口，失败必须留痕，否则“闹钟没响”无从排查
                Log.e("RunScheduleAlarm", "定时开跑执行失败", e)
            } finally {
                pending.finish()
            }
        }
    }
}