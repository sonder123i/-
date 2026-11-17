package com.zcshou.gogogo.run.data.prefs

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 漫游速度档：沿用原版 setting_walk / setting_run / setting_bike 键，
 * 新增 setting_car；默认值与原版一致（1.2 / 3.6 / 10.0，车 12.0）。
 */
object RoamSpeeds {

    enum class Gear(
        val id: String,
        val label: String,
        val shortLabel: String,
        val prefKey: String,
        val defaultSpeed: Float
    ) {
        WALK("walk", "步行", "步", "setting_walk", 1.2f),
        RUN("run", "跑步", "跑", "setting_run", 3.6f),
        BIKE("bike", "骑行", "骑", "setting_bike", 10.0f),
        CAR("car", "驾车", "车", "setting_car", 12.0f)
    }

    fun speed(context: Context, gear: Gear): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val raw = prefs.getString(gear.prefKey, null)
        return raw?.toFloatOrNull()?.takeIf { it in 0.2f..40f } ?: gear.defaultSpeed
    }

    fun setSpeed(context: Context, gear: Gear, value: Float) {
        val clamped = value.coerceIn(0.2f, 40f)
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(gear.prefKey, trimmed(clamped))
            .apply()
    }

    private fun trimmed(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
}
