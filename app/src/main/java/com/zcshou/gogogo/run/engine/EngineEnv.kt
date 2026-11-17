package com.zcshou.gogogo.run.engine

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 引擎运行环境依赖（模拟位置授权、震动、时钟）。
 * 抽成接口以便 JVM 单测注入假实现，确定性地驱动状态机。
 */
interface EngineEnv {
    fun isMockLocationAllowed(): Boolean
    fun vibrate()
    fun elapsedRealtimeMs(): Long
    fun wallTimeMs(): Long
    fun nanoTime(): Long
}

class ContextEngineEnv(context: Context) : EngineEnv {

    private val appContext = context.applicationContext

    override fun isMockLocationAllowed(): Boolean = try {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                appContext.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        true
    }

    override fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 500, 300, 500, 300, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            // 无震动权限/设备无马达时静默
        }
    }

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}