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
 * V4.0 轻量矢量轨迹缩略图：
 * - 双层光纤轨迹：12dp 15% 漫反射光晕 + 2.5dp 100% 锐利核心线；
 * - 声呐预热动效：PRE_START_SETTLE 期间从起点扩散同心声呐光波，光波扫过逐段点亮路线。
 */
class RouteThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    /** 底层 12dp 漫反射光晕（15%）。 */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = 38 // 15%
        maskFilter = android.graphics.BlurMaskFilter(6f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    /** 顶层 2.5dp 锐利核心线（光纤白青渐变）。 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
    }
    /** 声呐尚未扫过的"暗"段。 */
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
        alpha = 60
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_canvas)
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = context.getColor(R.color.pf_cyan)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.pf_cyan)
    }

    // ---------------------------------------------------------------- 声呐状态

    private var sonarRunning = false
    private var sonarProgress = 0f
    private var ripplePhase = 0f
    private var progressAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    fun setRoute(routePoints: List<TrackMathUtils.LatLng>) {
        points = routePoints
        invalidate()
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
                    ripplePaint.alpha = ((1f - phase) * 120).toInt().coerceIn(0, 120)
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
        canvas.drawCircle(origin.first, origin.second, dp(3f), startPaint)
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        rippleAnimator?.cancel()
        progressAnimator = null
        rippleAnimator = null
        super.onDetachedFromWindow()
    }
}