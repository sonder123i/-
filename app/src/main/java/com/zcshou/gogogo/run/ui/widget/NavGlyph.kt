package com.zcshou.gogogo.run.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/** 首页三个 Tab 的图标规格。 */
enum class NavGlyphSpec { ROUTE, MAP, SHIELD }

/**
 * 双色调导航图标 + 选中动作（移植自 capybara 的 PhosphorNavigationIcon）。
 *
 * 分两层：
 * - **duotone 填充**：随选中权重从 0.10 增浓到 0.28 alpha，选中项因此"实"起来；
 * - **描边**：承担全部主体形状，1.5 单位（24 视口）与项目原有矢量图标同粗。
 *
 * 动作全部由 [phase] 驱动，而 phase 在 0 与 1 处**姿态完全一致、速度为零**，
 * 所以动画可以随时中断、重播，不会跳帧。动作幅度一律乘 envelope/flourish，
 * 两端自然归零 —— 静止态永远是静态字形。
 */
object NavGlyph {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val body = Path()

    /** sin²(πt)：两端为 0，中段最饱满。 */
    fun envelope(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val wave = sin(PI * t).toFloat()
        return wave * wave
    }

    /** 有方向的"甩"：前半程为正，后半程过零，末端回到 0。 */
    fun flourish(t: Float): Float = envelope(t) * (1f - 1.25f * t) * 1.8f

    /**
     * 在 [left], [top] 起的 [size] 见方区域内绘制。
     * [weight] 是选中权重，[phase] 是动作时钟。
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        spec: NavGlyphSpec,
        tint: Int,
        weight: Float,
        phase: Float
    ) {
        if (size <= 0f) return
        val scale = size / 24f
        val t = phase.coerceIn(0f, 1f)
        val w = weight.coerceIn(0f, 1f)
        val tintAlpha = Color.alpha(tint) / 255f
        val strokeColor = tint
        val fillColor = Color.argb(
            (tintAlpha * (0.10f + 0.18f * w) * 255f).toInt().coerceIn(0, 255),
            Color.red(tint),
            Color.green(tint),
            Color.blue(tint)
        )
        stroke.color = strokeColor
        fill.color = fillColor
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        when (spec) {
            NavGlyphSpec.ROUTE -> drawRoute(canvas, t)
            NavGlyphSpec.MAP -> drawMap(canvas, t)
            NavGlyphSpec.SHIELD -> drawShield(canvas, t)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- 自动跑圈

    /**
     * 两个节点 + 一条连接曲线。动作：两个节点错相弹起（-1.8 / -1.5），
     * 连接曲线随之中段鼓起 —— 读作"路线被拽了一下"。
     */
    private fun drawRoute(canvas: Canvas, t: Float) {
        val f0 = flourish(t)
        val f1 = flourish(((t - 0.10f) / 0.90f).coerceIn(0f, 1f))

        canvas.drawCircle(6.5f, 17f, 2.5f, fill)
        canvas.drawCircle(17.5f, 7f, 2.5f, fill)

        path.reset()
        path.moveTo(17.5f, 9.5f)
        path.cubicTo(
            17.5f + 2.4f * f1, 13.6f - 1.6f * f1,
            12.8f - 2.2f * f1, 15.4f - 1.2f * f0,
            9f - 0.9f * f0, 17f - 1.8f * f0
        )
        canvas.drawPath(path, stroke)

        canvas.drawCircle(17.5f, 7f - 1.5f * f1, 2.5f, stroke)
        canvas.drawCircle(6.5f, 17f - 1.8f * f0, 2.5f, stroke)
    }

    // ---------------------------------------------------------------- 自由漫游

    /**
     * 三折地图。动作：两条折痕向两侧张开（±0.9），整块随之轻微倾斜，
     * 像纸被摊平又收回。
     */
    private fun drawMap(canvas: Canvas, t: Float) {
        val f = flourish(t)
        val f2 = flourish(((t - 0.08f) / 0.92f).coerceIn(0f, 1f))
        val xa = 9f + 0.9f * f2
        val xb = 15f - 0.9f * f

        body.reset()
        body.moveTo(3.5f, 6.6f)
        body.lineTo(xa, 4.4f)
        body.lineTo(xb, 6.9f)
        body.lineTo(20.5f, 4.4f)
        body.lineTo(20.5f, 17.4f)
        body.lineTo(xb, 19.6f)
        body.lineTo(xa, 17.1f)
        body.lineTo(3.5f, 19.6f)
        body.close()
        canvas.drawPath(body, fill)
        canvas.drawPath(body, stroke)

        canvas.drawLine(xa, 4.4f, xa, 17.1f, stroke)
        canvas.drawLine(xb, 6.9f, xb, 19.6f, stroke)
    }

    // ---------------------------------------------------------------- 体检设置

    /**
     * 盾牌 + 对勾。动作：整块轻微放大回弹，对勾向上抬一下，
     * 读作"检查通过"。
     */
    private fun drawShield(canvas: Canvas, t: Float) {
        val e = envelope(t)
        val f = flourish(t)
        val pop = 1f + 0.06f * e
        canvas.save()
        canvas.scale(pop, pop, 12f, 12f)

        body.reset()
        body.moveTo(12f, 3.2f)
        body.lineTo(19f, 6.2f)
        body.lineTo(19f, 11.8f)
        body.cubicTo(19f, 16.2f, 16.1f, 19.2f, 12f, 21f)
        body.cubicTo(7.9f, 19.2f, 5f, 16.2f, 5f, 11.8f)
        body.lineTo(5f, 6.2f)
        body.close()
        canvas.drawPath(body, fill)
        canvas.drawPath(body, stroke)

        canvas.save()
        canvas.translate(0f, -1.6f * f)
        path.reset()
        path.moveTo(8.6f, 11.8f)
        path.lineTo(11f, 14.2f)
        path.lineTo(15.6f, 9f)
        canvas.drawPath(path, stroke)
        canvas.restore()

        canvas.restore()
    }
}
