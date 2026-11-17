package com.zcshou.gogogo.run.engine

import kotlin.random.Random

/**
 * 人体配速模拟：热身减速、巡航高斯波动、急转弯减速、末段冲刺。
 */
class PaceSimulator(
    targetPaceSecPerKm: Int,
    private val random: Random = Random.Default,
    private val warmupMeters: Double = 300.0,
    private val sprintMeters: Double = 200.0
) {

    companion object {
        const val WARMUP_FACTOR_MIN = 0.90
        const val WARMUP_FACTOR_MAX = 0.95
        const val SPRINT_FACTOR_MIN = 1.05
        const val SPRINT_FACTOR_MAX = 1.10
        const val CRUISE_JITTER = 0.12
        const val TURN_HEADING_DEG = 45.0
        const val TURN_SLOWDOWN = 0.80
    }

    val baseSpeedMps: Double = 1000.0 / targetPaceSecPerKm

    private val warmupFactor: Double = random.nextDouble(WARMUP_FACTOR_MIN, WARMUP_FACTOR_MAX)
    private val sprintFactor: Double = random.nextDouble(SPRINT_FACTOR_MIN, SPRINT_FACTOR_MAX)
    private var cruiseFactor: Double = 1.0

    /**
     * @param accumulatedMeters 已跑里程（含起步）
     * @param remainingMeters   距目标剩余里程
     * @param headingChangeDeg  前方航向变化（度）
     * @param dtSec             心跳间隔（秒）
     */
    fun speedMps(
        accumulatedMeters: Double,
        remainingMeters: Double,
        headingChangeDeg: Double,
        dtSec: Double
    ): Double {
        cruiseFactor += random.nextGaussian() * 0.02 * dtSec
        cruiseFactor = cruiseFactor.coerceIn(1.0 - CRUISE_JITTER, 1.0 + CRUISE_JITTER)

        var speed = baseSpeedMps * cruiseFactor
        when {
            accumulatedMeters < warmupMeters -> speed *= warmupFactor
            remainingMeters < sprintMeters -> speed *= sprintFactor
        }
        if (headingChangeDeg > TURN_HEADING_DEG) speed *= TURN_SLOWDOWN
        return speed
    }
}

private fun Random.nextGaussian(): Double {
    // Box-Muller，避免依赖 java.util.Random 的同步实现
    var u1 = nextDouble()
    while (u1 <= 1e-12) u1 = nextDouble()
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}