package com.zcshou.gogogo.run.engine

enum class RunPhase {
    IDLE,
    ENV_GUARD,
    PRE_START_SETTLE,
    RUNNING,
    DETOUR_POINT,
    PAUSED,
    COOLDOWN_WALK,
    FINISHED
}

data class RunRuntimeState(
    val phase: RunPhase = RunPhase.IDLE,
    val accumulatedMeters: Double = 0.0,
    val targetMeters: Double = 0.0,
    val currentLapIndex: Int = 0,
    val speedMps: Double = 0.0,
    val paceSecPerKm: Int = 0,
    val position: TrackMathUtils.LatLng? = null,
    val elapsedSec: Long = 0,
    val message: String? = null,
    /** PRE_START_SETTLE 倒计时剩余秒数（>0 表示预热中） */
    val settleRemainSec: Int = 0,
    /** 口袋守护：虚拟里程已推进但硬件步数没有增长 */
    val stepsWarning: Boolean = false
) {
    val targetKm: Double get() = targetMeters / 1000.0
    val accumulatedKm: Double get() = accumulatedMeters / 1000.0
}