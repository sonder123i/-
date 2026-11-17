package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * V4.0 首页「深海光学」空间背景：
 * - 主遥测卡片正后方的径向环境背光（深海幽蓝 / 极光冰蓝，随昼夜 Token）；
 * - 3% 透明度的海底等深线矢量暗纹（确定性生成，不随帧变化）。
 *
 * 铺在页面最底层，为上层液态玻璃提供真实可折射的色彩源。
 */
class BathymetricBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val glowColor = context.getColor(R.color.dr_bg_ambient_glow)
    private val contourColor = context.getColor(R.color.pf_text_secondary)

    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = contourColor
        alpha = 8 // ~3%
    }

    private var glowShader: Shader? = null
    private val contours = mutableListOf<Path>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // 环境背光：以页面上部（遥测卡片区）为中心的径向弥散
        glowShader = RadialGradient(
            w * 0.5f, h * 0.16f,
            w * 0.75f,
            glowColor,
            0x00000000,
            Shader.TileMode.CLAMP
        )

        // 等深线：同心"变形圆"，双正弦扰动模拟海床起伏
        contours.clear()
        val cx = w * 0.82f
        val cy = h * 0.34f
        val base = w * 0.16f
        val step = w * 0.11f
        for (i in 0 until 7) {
            val r0 = base + i * step
            val a1 = (0.05 + 0.013 * i) * r0
            val a2 = (0.02 + 0.006 * i) * r0
            val p1 = i * 0.9
            val p2 = i * 1.7
            val path = Path()
            var first = true
            var angle = 0.0
            while (angle <= 2 * PI) {
                val r = r0 + a1 * sin(3 * angle + p1) + a2 * sin(7 * angle + p2)
                val x = (cx + r * cos(angle)).toFloat()
                val y = (cy + r * sin(angle)).toFloat()
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                angle += 0.12
            }
            path.close()
            contours.add(path)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        glowShader?.let {
            val paint = Paint().apply { shader = it }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        contours.forEach { canvas.drawPath(it, contourPaint) }
    }
}
