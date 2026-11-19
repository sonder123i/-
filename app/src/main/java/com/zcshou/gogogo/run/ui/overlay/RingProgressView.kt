package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zcshou.gogogo.R

/**
 * 环形进度（胶囊内嵌）：黑曜石底环 + 冰青进度弧，支持 0..1 进度与呼吸点。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress = 0f
    private var pulseAlpha = 255

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.dr_overlay_recess)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.dr_overlay_ice)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dr_overlay_ice)
    }
    private val rect = RectF()

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** 呼吸效果由外部驱动（0..255） */
    fun setPulseAlpha(alpha: Int) {
        pulseAlpha = alpha.coerceIn(40, 255)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 2.5f * density
        val inset = stroke / 2 + 1f * density
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            // 冰青 → 近白 的冷光进度弧
            progressPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    context.getColor(R.color.dr_overlay_ice),
                    0xFFFFFFFF.toInt()
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }
        dotPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                context.getColor(R.color.dr_overlay_ice),
                0xFFFFFFFF.toInt()
            ),
            null, Shader.TileMode.CLAMP
        )
        dotPaint.alpha = pulseAlpha
        canvas.drawCircle(width / 2f, height / 2f, 2.2f * density, dotPaint)
    }
}