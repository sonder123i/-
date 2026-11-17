package com.zcshou.gogogo.run.ui.theme

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * V4.0「光纤白青」渐变系统：中心近白、边缘泛冰青（#E0F9FA → #5CE1E6）。
 *
 * 所有激活态线条与关键数据统一走这套渐变，替代单调纯色，质感接近高透光纤/晶体。
 * 浅色入队的渐变整体压暗并偏钴青，保证阳光直射下仍锐利。
 */
object FiberStyle {

    private val FIBER_DARK = intArrayOf(
        0xFFEAFCFE.toInt(),
        0xFF9EF2F6.toInt(),
        0xFF5CE1E6.toInt()
    )
    private val FIBER_LIGHT = intArrayOf(
        0xFFCFEFF4.toInt(),
        0xFF5FCBDA.toInt(),
        0xFF007A94.toInt()
    )

    /** 双层光纤轨迹：底层光晕的 alpha（15%，与蓝图 12dp 光晕配套）。 */
    const val GLOW_ALPHA = 38

    /** 按当前主题取光纤渐变三色。 */
    fun colors(context: Context): IntArray =
        if (ThemeHelper.isDark(context)) FIBER_DARK else FIBER_LIGHT

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