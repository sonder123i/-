package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.zcshou.gogogo.R

/**
 * V5.2 双层同心光学玻璃透镜绘制器（纯静态矢量，零截图、零实时模糊）。
 *
 * 结构：
 * - 外层长胶囊：1dp 银白锐利高光描边（上沿亮、下沿微透），由 [drawOuterCapsuleRim] 绘制；
 * - 内层选中滑块：高透玻璃渐变填充 + 1.5dp 纯白镜面外圈 + 顶部镜面高光弧，
 *   由 [drawInnerLens] 绘制，滑动过程中只做位移，不重新采样底层像素；
 * - 卡片切边：1.5dp 冷银白镜面描边（顶部亮、底部微透）。
 *
 * 所有 Shader 仅在尺寸变化或滑动帧内重建，成本恒定为一层圆角矩形。
 */
object GlassLensRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val prismPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rimRect = RectF()
    private val prismRect = RectF()

    /** 按倍率缩放颜色的 alpha，用于把同一套水晶配方调成"薄玻璃"档。 */
    private fun scaleAlpha(color: Int, scale: Float): Int {
        if (scale >= 1f) return color
        val alpha = (android.graphics.Color.alpha(color) * scale.coerceIn(0f, 1f)).toInt()
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    /** 内层选中透镜：高透玻璃滑块 + 1.5dp 镜面高光外圈 + 微凸曲面光泽。 */
    fun drawInnerLens(
        canvas: Canvas,
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float,
        dispersion: Float = 0f,
        /**
         * 填充不透明度倍率。默认 1 = 既有路线 chip 的实心水晶观感；
         * 底栏透镜传 0.42，与 capybara 的 `NavSelectedSolidAlpha = 0.34` 同档 ——
         * 底栏下面压着模糊壁纸，透镜太实就成了一块贴纸，边缘折射全被盖住。
         */
        fillAlphaScale: Float = 1f
    ) {
        if (right - left < 8f * density || bottom - top < 4f * density) return
        val h = bottom - top
        val lensRadius = radius.coerceAtMost(h / 2f)

        // 0. 极淡冷调接触阴影：纯白水晶在浅色玻璃上也能显出厚度与边界
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        fillPaint.color = context.getColor(R.color.dr_lens_halo)
        val halo = 2f * density
        canvas.drawRoundRect(
            left - halo, top - halo * 0.6f, right + halo, bottom + halo * 1.2f,
            lensRadius + halo, lensRadius + halo, fillPaint
        )
        // Paint.setColor 会把颜色 alpha 写进 paint；必须复位，否则后续 shader 填充只剩 5% 不透明度
        fillPaint.alpha = 255

        // 1. 高透水晶填充：顶部受光更亮，底部保持通透冷白
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            left, top, left, bottom,
            intArrayOf(
                scaleAlpha(context.getColor(R.color.dr_lens_fill_top), fillAlphaScale),
                scaleAlpha(context.getColor(R.color.dr_lens_fill_mid), fillAlphaScale),
                scaleAlpha(context.getColor(R.color.dr_lens_fill_bottom), fillAlphaScale)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(left, top, right, bottom, lensRadius, lensRadius, fillPaint)

        // 2. 微凸曲面光泽：左上受光面对角衰减
        fillPaint.shader = LinearGradient(
            left, top, right, top + h * 0.9f,
            intArrayOf(0x42FFFFFF, 0x12FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(left, top, right, bottom, lensRadius, lensRadius, fillPaint)
        fillPaint.shader = null

        // 3. 1.5dp 镜面高光外圈：纯白 → 冷银 → 银白柔光 → 底部微透
        val stroke = 1.5f * density
        val inset = stroke / 2f
        rimRect.set(left + inset, top + inset, right - inset, bottom - inset)
        rimPaint.strokeWidth = stroke
        rimPaint.shader = LinearGradient(
            rimRect.left, rimRect.top, rimRect.left, rimRect.bottom,
            intArrayOf(
                context.getColor(R.color.dr_silver_rim),
                context.getColor(R.color.dr_crystal_rim),
                context.getColor(R.color.dr_silver_rim_soft),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.42f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            rimRect,
            (lensRadius - inset).coerceAtLeast(0f),
            (lensRadius - inset).coerceAtLeast(0f),
            rimPaint
        )
        rimPaint.shader = null

        // 4. 顶部 1dp 镜面高光弧（两端渐隐，模拟玻璃受光）
        if (h > 10f * density) {
            val y = top + 1.1f * density
            val startX = left + lensRadius * 0.42f
            val endX = right - lensRadius * 0.42f
            sheenPaint.strokeWidth = 1f * density
            sheenPaint.shader = LinearGradient(
                startX, y, endX, y,
                intArrayOf(
                    0x00FFFFFF,
                    context.getColor(R.color.dr_silver_sheen),
                    context.getColor(R.color.dr_silver_sheen),
                    0x00FFFFFF
                ),
                floatArrayOf(0f, 0.16f, 0.84f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(startX, y, endX, y, sheenPaint)
            sheenPaint.shader = null
        }

        // 5. 底部 0.75dp 微透切边，塑造玻璃物理厚度
        if (h > 12f * density) {
            val y = bottom - 1f * density
            val startX = left + lensRadius * 0.5f
            val endX = right - lensRadius * 0.5f
            sheenPaint.strokeWidth = 0.75f * density
            sheenPaint.color = context.getColor(R.color.dr_silver_rim_soft)
            sheenPaint.alpha = 90
            canvas.drawLine(startX, y, endX, y, sheenPaint)
            sheenPaint.alpha = 255
        }

        // 6. 角部限定色散：**只在按压抬起时出现**；静止透镜保持纯白，避免“贴纸彩边”
        if (dispersion > 0.04f && lensRadius > 6f * density) {
            drawCornerDispersion(
                canvas, context, left, top, right, bottom, lensRadius, density,
                strength = dispersion.coerceIn(0f, 1f)
            )
        }
    }

    private fun drawCornerDispersion(
        canvas: Canvas,
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float,
        strength: Float
    ) {
        val offset = 0.55f * density
        val r2 = radius * 2f
        prismPaint.strokeWidth = 1.2f * density
        val red = context.getColor(R.color.dr_prism_red)
        val cyan = context.getColor(R.color.dr_prism_cyan)

        fun corner(l: Float, t: Float, r: Float, b: Float, start: Float) {
            prismRect.set(l - offset, t - offset, r + offset, b + offset)
            prismPaint.color = red
            prismPaint.alpha = (24 * strength).toInt()
            canvas.drawArc(prismRect, start, 90f, false, prismPaint)
            prismRect.set(l + offset, t + offset, r - offset, b - offset)
            prismPaint.color = cyan
            canvas.drawArc(prismRect, start, 90f, false, prismPaint)
        }

        corner(left, top, left + r2, top + r2, 180f)
        corner(right - r2, top, right, top + r2, 270f)
        corner(right - r2, bottom - r2, right, bottom, 0f)
        corner(left, bottom - r2, left + r2, bottom, 90f)
    }

    /** 外层胶囊：上下边缘各一道 1dp 银白锐利高光，侧边以柔光收口。 */
    fun drawOuterCapsuleRim(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        radius: Float,
        density: Float
    ) {
        if (width <= 0 || height <= 0) return
        val stroke = 1f * density
        val inset = stroke / 2f
        rimRect.set(inset, inset, width - inset, height - inset)
        rimPaint.strokeWidth = stroke
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                context.getColor(R.color.dr_silver_rim),
                0x00FFFFFF,
                0x00FFFFFF,
                context.getColor(R.color.dr_silver_rim_soft)
            ),
            floatArrayOf(0f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        val r = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, r, r, rimPaint)
        rimPaint.shader = null

        // 上沿：锐利银白高光（两端收进圆角，避免变成一根横线）
        val topY = 1.2f * density
        val startX = radius * 0.55f
        val endX = width - radius * 0.55f
        sheenPaint.strokeWidth = 1f * density
        sheenPaint.shader = LinearGradient(
            startX, topY, endX, topY,
            intArrayOf(
                0x00FFFFFF,
                context.getColor(R.color.dr_silver_sheen),
                context.getColor(R.color.dr_silver_sheen),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(startX, topY, endX, topY, sheenPaint)

        // 下沿：微弱反光，强调玻璃厚度
        val bottomY = height - 1.4f * density
        sheenPaint.shader = LinearGradient(
            startX, bottomY, endX, bottomY,
            intArrayOf(
                0x00FFFFFF,
                context.getColor(R.color.dr_silver_rim_soft),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(startX, bottomY, endX, bottomY, sheenPaint)
        sheenPaint.shader = null
    }

    /**
     * 卡片切边：1.5dp 冷银白镜面高光描边。
     * 顶部最亮、中部冷银、底部极淡，避免形成生硬黑框。
     */
    fun drawCardRim(
        canvas: Canvas,
        context: Context,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        density: Float
    ) {
        if (right - left <= 0f || bottom - top <= 0f) return
        val stroke = 1.5f * density
        val inset = stroke / 2f
        rimRect.set(left + inset, top + inset, right - inset, bottom - inset)
        rimPaint.strokeWidth = stroke
        rimPaint.shader = LinearGradient(
            rimRect.left, rimRect.top, rimRect.left, rimRect.bottom,
            intArrayOf(
                context.getColor(R.color.dr_silver_rim),
                context.getColor(R.color.dr_crystal_rim),
                context.getColor(R.color.dr_silver_rim_soft),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.38f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        val r = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, r, r, rimPaint)
        rimPaint.shader = null
    }
}
