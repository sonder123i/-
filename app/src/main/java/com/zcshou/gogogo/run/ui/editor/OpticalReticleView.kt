package com.zcshou.gogogo.run.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.FiberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * V4.0 光学取景目镜（Optical Reticle）。
 *
 * - 外圈徕卡/蔡司风格游标刻度环：每 5° 细刻度、45° 主刻度、0/90/180/270 方位强调；
 * - 中心十字准星 + 角度游标点；
 * - 双层光纤跑道框：12dp 15% 漫反射光晕 + 2.5dp 100% 锐利核心线；
 * - 合焦反馈：旋转到 0° / ±90° / 180° 的 ±0.75° 内触发一次微闪烁 + CLOCK_TICK 机械触觉。
 *
 * API 与旧 TrackCrosshairView 保持一致，Activity 侧只需换类型。
 */
class OpticalReticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var showTrack = false

    var templatePoints: List<Pair<Double, Double>> = emptyList()

    /** 旋转角（度，0 = 正南北）。赋值时检测合焦。 */
    var rotationDeg: Double = 0.0
        set(value) {
            field = value
            checkFocus(value)
        }

    var metersScale = 1.0
    var pxPerMeter = 1f
    var offsetX = 0f
    var offsetY = 0f

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val accent = context.getColor(R.color.pf_volt)
    private val accentSoft = context.getColor(R.color.pf_cyan)
    private val inkSecondary = context.getColor(R.color.pf_text_secondary)
    private val inkPrimary = context.getColor(R.color.pf_text_primary)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(12f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = accent
        alpha = 38 // 15%
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
        alpha = 15 // 6%
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = inkSecondary
        alpha = 110
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = inkSecondary
        alpha = 160
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = accentSoft
        alpha = 220
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        color = inkPrimary
        alpha = 190
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentSoft
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accentSoft
    }
    private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)

    private var flashAlpha = 0f
    private var flashAnimator: ValueAnimator? = null
    private var lastFocusKey: Int = Int.MIN_VALUE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        val trackRadius = trackExtentPx()
        val ringRadius = max(dp(120f), trackRadius + dp(26f))

        drawTickRing(canvas, cx, cy, ringRadius)
        drawCrosshair(canvas, cx, cy)
        if (flashAlpha > 0f) {
            flashPaint.alpha = (flashAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringRadius, flashPaint)
        }

        if (!showTrack || templatePoints.size < 3 || pxPerMeter <= 0.001f) return
        // 光纤渐变核心线：中心近白、边缘泛冰青
        trackPaint.shader = FiberStyle.linear(context, width, height)
        drawTrack(canvas, cx, cy)
    }

    // ---------------------------------------------------------------- 刻度环

    private fun drawTickRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, ringPaint)

        for (deg in 0 until 360 step 5) {
            val rad = Math.toRadians(deg.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            val cardinal = deg % 90 == 0
            val major = deg % 45 == 0
            val len = when {
                cardinal -> dp(14f)
                major -> dp(9f)
                else -> dp(4.5f)
            }
            val paint = when {
                cardinal -> cardinalPaint
                major -> tickPaint
                else -> ringPaint
            }
            canvas.drawLine(
                cx + dirX * radius,
                cy + dirY * radius,
                cx + dirX * (radius - len),
                cy + dirY * (radius - len),
                paint
            )
        }

        // 当前角度游标
        val rad = Math.toRadians(rotationDeg)
        val nx = cx + sin(rad).toFloat() * radius
        val ny = cy - cos(rad).toFloat() * radius
        canvas.drawCircle(nx, ny, dp(3.2f), needlePaint)
        canvas.drawCircle(nx, ny, dp(6.5f), ringPaint)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val len = dp(26f)
        val gap = dp(8f)
        crossPaint.pathEffect = dash
        canvas.drawLine(cx - len, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + len, cy, crossPaint)
        canvas.drawLine(cx, cy - len, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + len, crossPaint)
        crossPaint.pathEffect = null
        canvas.drawCircle(cx, cy, dp(6.5f), crossPaint)
        canvas.drawCircle(cx, cy, dp(2f), dotPaint)
    }

    // ---------------------------------------------------------------- 光纤跑道

    private fun drawTrack(canvas: Canvas, cx: Float, cy: Float) {
        val rad = Math.toRadians(rotationDeg)
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        val scale = (metersScale * pxPerMeter).toFloat()

        val path = Path()
        val fill = Path()
        var first = true
        templatePoints.forEach { (x, y) ->
            val mx = (x * scale).toFloat()
            val my = (y * scale).toFloat()
            val east = mx * cosR + my * sinR
            val north = -mx * sinR + my * cosR
            val px = cx + offsetX + east
            val py = cy + offsetY - north
            if (first) {
                path.moveTo(px, py)
                fill.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        path.close()
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, trackPaint)
    }

    /** 跑道在屏幕上的最大半径，用于把刻度环撑到跑道外侧。 */
    private fun trackExtentPx(): Float {
        if (!showTrack || templatePoints.size < 3 || pxPerMeter <= 0.001f) return 0f
        val scale = (metersScale * pxPerMeter).toFloat()
        var maxExtent = 0f
        templatePoints.forEach { (x, y) ->
            val px = (x * scale).toFloat()
            val py = (y * scale).toFloat()
            maxExtent = max(maxExtent, kotlin.math.hypot(px, py))
        }
        return maxExtent
    }

    // ---------------------------------------------------------------- 合焦反馈

    /** 归一化到 (-180, 180]。 */
    private fun normalize(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180) a -= 360
        if (a <= -180) a += 360
        return a
    }

    private fun checkFocus(angle: Double) {
        val normalized = normalize(angle)
        val target = FOCUS_ANGLES.minByOrNull { abs(normalize(normalized - it)) } ?: return
        val delta = abs(normalize(normalized - target))
        if (delta > FOCUS_TOLERANCE_DEG) {
            lastFocusKey = Int.MIN_VALUE
            return
        }
        val key = (target * 10).toInt()
        if (key == lastFocusKey) return
        lastFocusKey = key
        playFocusFlash()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun playFocusFlash() {
        flashAnimator?.cancel()
        flashAlpha = 1f
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            addUpdateListener {
                flashAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        flashAnimator?.cancel()
        flashAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FOCUS_TOLERANCE_DEG = 0.75
        val FOCUS_ANGLES = doubleArrayOf(0.0, 90.0, 180.0, -90.0)
    }
}
