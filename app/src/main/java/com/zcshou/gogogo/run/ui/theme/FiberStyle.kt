package com.zcshou.gogogo.run.ui.theme

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.zcshou.gogogo.R

/**
 * V5.0 光纤渐变线条：靛蓝 → 冰青 → 紫罗兰。
 *
 * 所有轨迹、进度环、回放线共用这一套 Token；外层再包一层 22% 柔和光晕，
 * 保证在银白制图纸与深渊海图上都锐利醒目。
 */
object FiberStyle {

    private val FIBER_DARK = intArrayOf(
        0xFF8B96FF.toInt(),
        0xFF7CF3F7.toInt(),
        0xFFD9A6FF.toInt()
    )
    private val FIBER_LIGHT = intArrayOf(
        0xFF5E6AD2.toInt(),
        0xFF5CE1E6.toInt(),
        0xFFC77DFF.toInt()
    )

    /** 双层光纤轨迹：底层光晕的 alpha（22%）。 */
    const val GLOW_ALPHA = 56

    /** 按当前主题取光纤渐变三色。 */
    fun colors(context: Context): IntArray =
        if (ThemeHelper.isDark(context)) FIBER_DARK else FIBER_LIGHT

    /** 外层柔和光晕色（22% 靛蓝）。 */
    fun glowColor(context: Context): Int =
        context.getColor(R.color.dr_fiber_glow)

    fun primaryColor(context: Context): Int =
        context.getColor(R.color.dr_fiber_indigo)

    /** 对应主题的线性渐变 Shader（按像素尺寸生成，供 Paint.shader 使用）。 */
    fun linear(
        context: Context,
        width: Int,
        height: Int,
        vertical: Boolean = false
    ): LinearGradient {
        val colors = colors(context)
        return if (vertical) {
            LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                colors, null, Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                colors, null, Shader.TileMode.CLAMP
            )
        }
    }

    /** 直接产出带光纤渐变的画刷。 */
    fun paint(
        context: Context,
        width: Int = 1,
        height: Int = 1,
        vertical: Boolean = false
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = linear(context, width, height, vertical)
    }
}
