package com.zcshou.gogogo.mascot

/**
 * 赛后战报彩蛋匹配。按优先级只命中一张：
 * 配速过快 → 夜跑 → 早跑 → 长时托管 → 默认摸鱼。
 */
object MascotEggResolver {

    /** 12 km/h 对应 5'00"/km = 300 秒/公里。 */
    private const val FAST_PACE_SEC_PER_KM = 300
    private const val LONG_RUN_SECONDS = 20 * 60

    fun forReport(
        averagePaceSecPerKm: Int,
        durationSeconds: Int,
        endHour: Int
    ): MascotEgg = when {
        averagePaceSecPerKm in 1 until FAST_PACE_SEC_PER_KM -> MascotEgg.DRIVE
        endHour >= 23 || endHour < 5 -> MascotEgg.FLASHLIGHT
        endHour in 6..9 -> MascotEgg.TOAST
        durationSeconds >= LONG_RUN_SECONDS -> MascotEgg.DESK_TAIL
        else -> MascotEgg.SLACKING_WHALE
    }
}