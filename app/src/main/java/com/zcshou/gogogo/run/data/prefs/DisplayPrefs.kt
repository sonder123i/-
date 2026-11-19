package com.zcshou.gogogo.run.data.prefs

import android.content.Context

/**
 * V4.0 视觉偏好：渲染质量（液态玻璃 / 省电平衡）。
 * 与主题模式（ThemeHelper）分开存放，避免两套开关互相覆盖。
 */
object DisplayPrefs {

    enum class RenderQuality {
        /** 液态玻璃：V4.1 静态磨砂材质（零采样、零实时模糊，恒定开销）。 */
        OPTICAL,

        /** 省电平衡：保留同一静态磨砂材质，仅记录省电偏好。 */
        BALANCED
    }

    private const val NAME = "pf_display_prefs"
    private const val KEY_QUALITY = "render_quality"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun quality(context: Context): RenderQuality {
        val raw = prefs(context).getString(KEY_QUALITY, null) ?: return RenderQuality.OPTICAL
        return runCatching { RenderQuality.valueOf(raw) }.getOrDefault(RenderQuality.OPTICAL)
    }

    fun setQuality(context: Context, quality: RenderQuality) {
        prefs(context).edit().putString(KEY_QUALITY, quality.name).apply()
    }
}
