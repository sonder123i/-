package com.zcshou.gogogo.run.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.engine.TrackMathUtils
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 无地图路线预览：WGS-84 轨迹自绘 + 网格 + 起点标记（不依赖任何地图 SDK）。
 */
class RoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TrackMathUtils.LatLng> = emptyList()
    private var closed = true

    private val density = resources.displayMetrics.density
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.pf_volt)
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pf_amber)
    }
    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.pf_stroke)
        strokeWidth = density
    }
    private val bgPaint = Paint().apply { color = context.getColor(R.color.pf_card) }

    fun setRoute(points: List<TrackMathUtils.LatLng>, closed: Boolean) {
        this.points = points
        this.closed = closed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val step = 32f * density
        var gx = step
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint)
            gx += step
        }
        var gy = step
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint)
            gy += step
        }
        if (points.size < 2) return

        val lat0 = points.map { it.lat }.average()
        val lng0 = points.map { it.lng }.average()
        val metersPerLng = 111320.0 * cos(Math.toRadians(lat0))
        val local = points.map {
            ((it.lng - lng0) * metersPerLng).toFloat() to ((it.lat - lat0) * 110574.0).toFloat()
        }
        val minX = local.minOf { it.first }
        val maxX = local.maxOf { it.first }
        val minY = local.minOf { it.second }
        val maxY = local.maxOf { it.second }
        val spanX = max(maxX - minX, 1f)
        val spanY = max(maxY - minY, 1f)
        val pad = 24f * density
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val centerX = width / 2f
        val centerY = height / 2f
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        fun viewX(east: Float) = centerX + (east - midX) * scale
        fun viewY(north: Float) = centerY - (north - midY) * scale

        val path = Path()
        local.forEachIndexed { index, (east, north) ->
            if (index == 0) path.moveTo(viewX(east), viewY(north))
            else path.lineTo(viewX(east), viewY(north))
        }
        if (closed) path.close()
        canvas.drawPath(path, pathPaint)

        canvas.drawCircle(viewX(local.first().first), viewY(local.first().second), 6f * density, startPaint)
    }
}