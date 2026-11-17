package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.TrackMathUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/**
 * 本地历史战报复盘：多圈变道分层轨迹 + 分段配速柱状图。
 * 轨迹来自路线模板；配速曲线为基于平均配速的本地估算（数据库不存采样点）。
 */
class RunReplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()
    private var laps = 1
    private var averagePaceSec = 0
    private var seed = 0L

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_text_primary)
        textSize = 12f * density
        textAlign = Paint.Align.LEFT
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_text_secondary)
        textSize = 10f * density
        textAlign = Paint.Align.LEFT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_stroke)
        strokeWidth = 1f * density
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_volt)
        style = Paint.Style.FILL
    }
    /** 光晕滤波器按 dp 常量构造一次，避免每圈每帧重复分配。 */
    private val lapGlowFilter = android.graphics.BlurMaskFilter(
        5f * density,
        android.graphics.BlurMaskFilter.Blur.NORMAL
    )

    fun bind(
        points: List<TrackMathUtils.LatLng>,
        laps: Int,
        averagePaceSec: Int,
        seed: Long
    ) {
        this.points = points
        this.laps = laps.coerceAtLeast(1)
        this.averagePaceSec = averagePaceSec
        this.seed = seed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mapBottom = h * 0.64f
        canvas.drawLine(
            dp(12f), mapBottom + dp(6f), w - dp(12f), mapBottom + dp(6f), dividerPaint
        )
        drawLayeredTrack(canvas, w, mapBottom)
        drawPaceBars(canvas, w, mapBottom)
    }

    private fun drawLayeredTrack(canvas: Canvas, w: Float, mapBottom: Float) {
        if (points.size < 3) {
            canvas.drawText("原路线已删除，无法还原轨迹", dp(16f), mapBottom / 2f, subPaint)
            return
        }
        val lat0 = points.map { it.lat }.average()
        val lng0 = points.map { it.lng }.average()
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        val my = 110574.0
        val local = points.map {
            ((it.lng - lng0) * mx).toFloat() to ((it.lat - lat0) * my).toFloat()
        }
        val minX = local.minOf { it.first }
        val maxX = local.maxOf { it.first }
        val minY = local.minOf { it.second }
        val maxY = local.maxOf { it.second }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val padding = dp(26f)
        val scale = min((w - padding * 2) / spanX, (mapBottom - padding * 2) / spanY)
        val offsetX = (w - spanX * scale) / 2f
        val offsetY = (mapBottom - spanY * scale) / 2f
        val lapCount = laps.coerceIn(1, 8)
        // 先画一层淡底，突出分层轨迹
        trackPaint.color = 0x22C8FF00
        trackPaint.strokeWidth = dp(1.5f)
        drawLap(canvas, local, 0f, scale, offsetX, offsetY, maxY)
        for (lap in 0 until lapCount) {
            val lane = (lap - (lapCount - 1) / 2.0) * 0.9
            val fraction = if (lapCount <= 1) 0f else lap.toFloat() / (lapCount - 1)
            trackPaint.color = blend(
                context.getColor(R.color.pf_volt),
                context.getColor(R.color.pf_cyan),
                fraction,
                255
            )
            // V4.0 双层光纤：10dp 漫反射光晕 + 2.2dp 锐利核心线
            trackPaint.strokeWidth = dp(10f)
            trackPaint.alpha = 36
            trackPaint.maskFilter = lapGlowFilter
            drawLap(canvas, local, lane.toFloat(), scale, offsetX, offsetY, maxY)
            trackPaint.strokeWidth = dp(2.2f)
            trackPaint.alpha = if (lapCount == 1) 230 else 150 + lap * 10
            trackPaint.maskFilter = null
            drawLap(canvas, local, lane.toFloat(), scale, offsetX, offsetY, maxY)
        }
        val start = local.first()
        val sx = offsetX + (start.first - minX) * scale
        val sy = offsetY + (maxY - start.second) * scale
        canvas.drawCircle(sx, sy, dp(4f), startPaint)
        canvas.drawText("起点", sx + dp(7f), sy + dp(3f), subPaint)
        canvas.drawText(
            "${min(lapCount, laps)} 圈 · 自动变道分层",
            dp(14f), dp(18f), labelPaint
        )
    }

    private fun drawLap(
        canvas: Canvas,
        local: List<Pair<Float, Float>>,
        lane: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        maxY: Float
    ) {
        val path = Path()
        local.forEachIndexed { index, (east, north) ->
            val next = local[(index + 1) % local.size]
            val dx = next.first - east
            val dy = next.second - north
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
            // 切线左侧法线（米制局部坐标）
            val nx = -dy / len * lane
            val ny = dx / len * lane
            val px = offsetX + (east + nx - local.minOf { it.first }) * scale
            val py = offsetY + (maxY - (north + ny)) * scale
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, trackPaint)
    }

    private fun drawPaceBars(canvas: Canvas, w: Float, top: Float) {
        val base = if (averagePaceSec > 0) averagePaceSec else 340
        val segments = 6
        val left = dp(16f)
        val right = w - dp(16f)
        val barAreaTop = top + dp(30f)
        val barAreaBottom = height - dp(28f)
        val barHeight = (barAreaBottom - barAreaTop).coerceAtLeast(20f)
        val gap = dp(7f)
        val barWidth = ((right - left) - gap * (segments - 1)) / segments
        canvas.drawText("分段配速（估算）", left, top + dp(20f), labelPaint)
        canvas.drawText(
            if (averagePaceSec > 0) "平均 ${formatPace(averagePaceSec)} /km" else "平均配速 --",
            left, top + dp(20f) + dp(14f), subPaint
        )
        val speeds = (0 until segments).map { i ->
            val wave = sin(seed * 0.7 + i * 1.6) * 0.06
            val pace = (base * (1.0 + wave)).toInt().coerceIn(220, 720)
            1000.0 / pace
        }
        val maxSpeed = speeds.maxOrNull() ?: 1.0
        for (i in 0 until segments) {
            val x = left + i * (barWidth + gap)
            val speedFraction = (speeds[i] / maxSpeed).toFloat().coerceIn(0.25f, 1f)
            val heightPx = barHeight * speedFraction
            val barTop = barAreaBottom - heightPx
            barPaint.color = blend(
                context.getColor(R.color.pf_cyan),
                context.getColor(R.color.pf_volt),
                i / (segments - 1f),
                220
            )
            canvas.drawRoundRect(
                x, barTop, x + barWidth, barAreaBottom,
                dp(4f), dp(4f), barPaint
            )
            val pace = (1000.0 / speeds[i]).toInt()
            canvas.drawText(formatPace(pace), x, barAreaBottom + dp(14f), subPaint)
        }
    }

    private fun formatPace(secPerKm: Int): String =
        String.format(java.util.Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)

    private fun blend(from: Int, to: Int, fraction: Float, alpha: Int): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.argb(
            alpha,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }

    private fun dp(value: Float): Float = value * density
}
