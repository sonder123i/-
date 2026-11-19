package com.zcshou.gogogo.run.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.theme.FiberStyle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Apple 单色光纤轨迹缩略图：
 * - 双层光纤：9dp 10% 漫反射光晕 + 1.5dp 锐利核心线（浅色深炭黑 / 深色纯白微光）；
 * - 声呐预热动效：PRE_START_SETTLE 期间从起点扩散同心单色光波，逐段点亮路线。
 */
class RouteThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    /** 底层 9dp 漫反射光晕（10%，克制不发光）。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = FiberStyle.GLOW_ALPHA
        maskFilter = android.graphics.BlurMaskFilter(4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    /** 顶层 1.5dp 锐利核心线。 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
    }
    /** 声呐尚未扫过的"暗"段。 */
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = 42
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_card)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.pf_volt)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pf_volt)
    }

    // ---------------------------------------------------------------- 声呐状态

    private var sonarRunning = false
    private var sonarProgress = 0f
    private var ripplePhase = 0f
    private var progressAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    // V5.2：沿跑道匀速巡航的冰川青白光点（6s / 圈），带柔和光尾
    private var cometRunning = false
    private var cometPhase = 0f
    private var cometAnimator: ValueAnimator? = null
    private val cometGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_comet_glow)
        alpha = 60
        maskFilter = android.graphics.BlurMaskFilter(5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val cometCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_comet_core)
    }

    fun setRoute(routePoints: List<TrackMathUtils.LatLng>) {
        points = routePoints
        invalidate()
        // 路线数据晚于 onResume 到达时，补启动巡航光点
        if (cometRunning && cometAnimator == null) {
            cometRunning = false
            setCometRunning(true)
        }
    }

    /** 6s 一圈的巡航光点；[running] 为 false 时动画立即 cancel，保持 0fps。 */
    fun setCometRunning(running: Boolean) {
        if (cometRunning == running) return
        cometRunning = running
        if (!running) {
            cometAnimator?.cancel()
            cometAnimator = null
            invalidate()
            return
        }
        if (points.size < 3) return
        cometAnimator?.cancel()
        cometAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                cometPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 5s 预热：声呐光波 + 逐段点亮。
     * 进入 RUNNING 前一直播放；[stopSonar] 后路线保持全亮。
     */
    fun startSonar(durationMs: Long = 5000) {
        if (points.size < 3) return
        sonarRunning = true
        sonarProgress = 0f
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                sonarProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                ripplePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopSonar() {
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = null
        rippleAnimator = null
        sonarRunning = false
        sonarProgress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)
        if (points.size < 3) return

        val lat0 = points.map { it.lat }.average()
        val lng0 = points.map { it.lng }.average()
        val metersPerLng = 111320.0 * cos(Math.toRadians(lat0))
        val local = points.map {
            ((it.lng - lng0) * metersPerLng).toFloat() to ((it.lat - lat0) * 110574.0).toFloat()
        }
        val spanX = max(local.maxOf { it.first } - local.minOf { it.first }, 1f)
        val spanY = max(local.maxOf { it.second } - local.minOf { it.second }, 1f)
        val pad = 6f * density
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val midX = (local.minOf { it.first } + local.maxOf { it.first }) / 2f
        val midY = (local.minOf { it.second } + local.maxOf { it.second }) / 2f

        val screen = local.map { (east, north) ->
            cx + (east - midX) * scale to cy - (north - midY) * scale
        }

        // 声呐光波：从起点向外扩散的同心圆
        if (sonarRunning) {
            val origin = screen.first()
            val maxRipple = radius * 0.95f
            repeat(2) { k ->
                var phase = ripplePhase - k * 0.5f
                if (phase < 0f) phase += 1f
                val r = maxRipple * phase
                if (r > dp(8f)) {
                    ripplePaint.alpha = ((1f - phase) * 70).toInt().coerceIn(0, 70)
                    canvas.drawCircle(origin.first, origin.second, r, ripplePaint)
                }
            }
        }

        val litCount = (screen.size * sonarProgress).toInt().coerceIn(0, screen.size)
        if (litCount < screen.size) {
            // 未点亮段：从扫描沿绕行到起点（含回绕段），不做直线和弦
            val dim = Path()
            screen.forEachIndexed { index, (x, y) ->
                if (index < litCount) return@forEachIndexed
                if (index == litCount) dim.moveTo(x, y) else dim.lineTo(x, y)
            }
            dim.lineTo(screen[0].first, screen[0].second)
            dim.close()
            canvas.drawPath(dim, dimPaint)
        }
        if (litCount > 0) {
            val lit = Path()
            for (i in 0 until litCount) {
                if (i == 0) lit.moveTo(screen[i].first, screen[i].second)
                else lit.lineTo(screen[i].first, screen[i].second)
            }
            // 只有整圈点亮后才闭合；扫描中途闭合会出现横穿缩略图的直线弦
            if (litCount == screen.size) lit.close()
            canvas.drawPath(lit, glowPaint)
            // 光纤渐变核心线：中心近白、边缘泛冰青
            corePaint.shader = FiberStyle.linear(context, width, height)
            canvas.drawPath(lit, corePaint)
        }

        // 起点星标
        val origin = screen.first()
        canvas.drawCircle(origin.first, origin.second, dp(2.5f), startPaint)

        if (cometRunning) drawComet(canvas, screen)
    }

    /** 沿闭环折线按累计边长插值，绘制带柔和光尾的冰川青白巡航光点。 */
    private fun drawComet(canvas: Canvas, screen: List<Pair<Float, Float>>) {
        if (screen.size < 3) return
        val segmentLengths = FloatArray(screen.size)
        var total = 0f
        for (i in screen.indices) {
            val a = screen[i]
            val b = screen[(i + 1) % screen.size]
            val len = kotlin.math.hypot(b.first - a.first, b.second - a.second)
            segmentLengths[i] = len
            total += len
        }
        if (total <= 0.01f) return

        // 光尾：沿跑道向后均匀取 10 个采样点，半径与透明度递减
        val tailSteps = 10
        val coreColor = context.getColor(R.color.dr_comet_core)

        // 局部绘制函数：直接插值，不产生逐帧临时对象
        fun paintPoint(
            phase: Float,
            glowRadius: Float,
            glowAlpha: Int,
            coreRadius: Float,
            coreAlpha: Int
        ) {
            var target = phase * total
            var index = 0
            while (index < segmentLengths.size && target > segmentLengths[index]) {
                target -= segmentLengths[index]
                index++
            }
            if (index >= screen.size) index = screen.size - 1
            val a = screen[index]
            val b = screen[(index + 1) % screen.size]
            val ratio = if (segmentLengths[index] > 0f) target / segmentLengths[index] else 0f
            val x = a.first + (b.first - a.first) * ratio
            val y = a.second + (b.second - a.second) * ratio
            cometGlowPaint.color = coreColor
            cometGlowPaint.alpha = glowAlpha
            canvas.drawCircle(x, y, glowRadius, cometGlowPaint)
            cometCorePaint.alpha = coreAlpha
            canvas.drawCircle(x, y, coreRadius, cometCorePaint)
        }

        for (i in tailSteps downTo 1) {
            val strength = 1f - (i - 1) / tailSteps.toFloat()
            val phase = ((cometPhase - i * 0.0055f) % 1f + 1f) % 1f
            paintPoint(
                phase = phase,
                glowRadius = dp(5.4f) * strength.coerceAtLeast(0.35f),
                glowAlpha = (78 * strength * strength).toInt().coerceIn(0, 78),
                coreRadius = dp(1.7f) * strength.coerceAtLeast(0.4f),
                coreAlpha = (150 * strength).toInt().coerceIn(0, 150)
            )
        }

        paintPoint(
            phase = cometPhase,
            glowRadius = dp(6.5f),
            glowAlpha = 90,
            coreRadius = dp(2.6f),
            coreAlpha = 255
        )
        // 白热核心
        var headTarget = cometPhase * total
        var headIndex = 0
        while (headIndex < segmentLengths.size && headTarget > segmentLengths[headIndex]) {
            headTarget -= segmentLengths[headIndex]
            headIndex++
        }
        if (headIndex >= screen.size) headIndex = screen.size - 1
        val ha = screen[headIndex]
        val hb = screen[(headIndex + 1) % screen.size]
        val hr = if (segmentLengths[headIndex] > 0f) {
            headTarget / segmentLengths[headIndex]
        } else {
            0f
        }
        cometCorePaint.color = 0xFFFFFFFF.toInt()
        cometCorePaint.alpha = 255
        canvas.drawCircle(
            ha.first + (hb.first - ha.first) * hr,
            ha.second + (hb.second - ha.second) * hr,
            dp(1.1f),
            cometCorePaint
        )
        cometCorePaint.color = coreColor
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = null
        rippleAnimator = null
        cometAnimator?.cancel()
        cometAnimator = null
        super.onDetachedFromWindow()
    }
}