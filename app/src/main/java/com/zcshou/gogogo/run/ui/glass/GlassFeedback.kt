package com.zcshou.gogogo.run.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import com.example.liquidglass.LiquidGlassToast
import com.zcshou.gogogo.R

/**
 * 应用内轻量反馈统一入口：优先使用 Liquid Glass 悬浮提示。
 *
 * `LiquidGlassToast` 自带独立窗口，能折射 Activity / 弹窗 / BottomSheet 叠出来的
 * 真实背景，比系统 Toast 更贴合当前的液态玻璃体系；拿不到 Activity（例如 Service
 * 或 Application Context）时自动退回系统 Toast，保证任何调用点都不会丢提示。
 */
object GlassFeedback {

    fun toast(context: Context, message: CharSequence, long: Boolean = false) {
        val activity = context.findActivity()
        val fallbackDuration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        if (activity == null || activity.isFinishing ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
        ) {
            Toast.makeText(context, message, fallbackDuration).show()
            return
        }
        val duration = if (long) LiquidGlassToast.LENGTH_LONG else LiquidGlassToast.LENGTH_SHORT
        runCatching {
            val toast = LiquidGlassToast.makeText(activity, message, duration)
            // 跟随应用自己的光学色板：浅色白瓷 / 深色曜石，避免默认自适应染色在
            // 纯色页面上呈现一块偏灰的“实心条”。
            toast.glass.glassTint = activity.getColor(R.color.dr_nav_optical_tint)
            toast.glass.enableAdaptiveTint = false
            toast.setTextColor(activity.getColor(R.color.pf_text_primary))
            toast.show()
        }.onFailure {
            Toast.makeText(context, message, fallbackDuration).show()
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
