package com.zcshou.gogogo.run.guard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 免 Root 口袋守护：只读监听 TYPE_STEP_COUNTER。
 * 虚拟里程已推进但硬件步数一直不动时给出 warning，让 UI 变色并震动提醒。
 * 无计步传感器或系统拒绝注册时静默跳过。
 */
class StepGuard(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var baseline = -1f
    private var latest = -1f

    var available = false
        private set

    var warning = false
        private set

    fun start() {
        if (sensor == null || available) return
        val ok = runCatching {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrNull()
        available = ok == true
    }

    /** 每次引擎状态更新时调用；virtualMeters 为累计虚拟里程 */
    fun update(virtualMeters: Double): Boolean {
        if (!available || baseline < 0f) return false
        warning = virtualMeters > 100.0 && (latest - baseline) < 1f
        return warning
    }

    fun stop() {
        if (available) runCatching { sensorManager?.unregisterListener(this) }
        available = false
        warning = false
        baseline = -1f
        latest = -1f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        latest = event.values[0]
        if (baseline < 0f) baseline = latest
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
