package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R

/**
 * 干净磨砂玻璃底片：静态材质，不含任何彩色扫光。
 *
 * 悬浮窗跑在 `TYPE_APPLICATION_OVERLAY` 里，既不能开 `FLAG_BLUR_BEHIND`
 * （Android 12+ 会生成全屏 Dim Layer，输入分发器把窗外触摸全判为 untrusted，
 * 表现为「悬浮窗把下层页面卡死」），也采不到下层 App 的像素。所以玻璃的「厚度」
 * 只能靠材质本身画出来：
 *
 * 1. 底色：竖向微渐变（顶部提亮 6%），避免纯色平板感；
 * 2. 镜面高光：顶部 45% 高度的白色渐变，形成受光的上边缘；
 * 3. 内阴影：底部 30% 轻微压暗，压出体积；
 * 4. 描边：1dp 冷银边 + 顶部 1px 冰青亮线。
 *
 * 彩色只留给边缘流光（见 [BorderBeamDrawable]）——填充保持中性，
 * 这样白磨砂才干净，彩色不会被摊薄到整块玻璃上。
 *
 * 颜色全部取自 `dr_overlay_*` Token，随主题昼夜翻转
 * （浅色「冰晶白磨砂」/ 深色「深空黑曜石」）。
 */
class PrismGlassDrawable @JvmOverloads constructor(
    private val context: Context,
    private val cornerRadiusPx: Float,
    /** 告警态：底色换成暖白/深蓝，其余结构不变。 */
    private val warn: Boolean = false
) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val rect = RectF()
    private val shape = Path()
    private val rimShape = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private var alphaScale = 1f

    private val surface = context.getColor(
        if (warn) R.color.dr_overlay_surface_warn else R.color.dr_overlay_surface
    )
    private val specular = context.getColor(R.color.dr_prism_specular)
    private val rimSilver = context.getColor(R.color.dr_overlay_rim_silver)
    private val rimIce = context.getColor(R.color.dr_overlay_rim_ice)

    /**
     * 深色底上镜面高光必须收着画。
     *
     * 同一套 alpha 叠在白底和黑曜石上效果完全不同：白底需要较强高光才显厚度，
     * 黑底上 45% 高的镜面高光会把整块面板冲成浅灰，黑曜石质感全丢。
     * 这里按底色亮度反比缩放——底色越暗，叠层越轻。
     */
    private val isDarkSurface: Boolean = runCatching {
        val r = Color.red(surface) / 255f
        val g = Color.green(surface) / 255f
        val b = Color.blue(surface) / 255f
        (0.213f * r + 0.715f * g + 0.072f * b) < 0.5f
    }.getOrDefault(false)

    private val specularFactor = if (isDarkSurface) 0.22f else 1f
    private val innerShadowFactor = if (isDarkSurface) 0.35f else 1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val radius = cornerRadiusPx.coerceAtMost(minOf(b.width(), b.height()) / 2f)
        shape.reset()
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(shape)

        // Rect 的字段是 Int，LinearGradient 只接受 Float —— 先提成本地浮点量
        val bLeft = b.left.toFloat()
        val bTop = b.top.toFloat()
        val bBottom = b.bottom.toFloat()
        val h = b.height().toFloat()

        // 1. 底色：顶部提亮 6% 的竖向渐变（走 alpha 管道，setAlpha 后不退化成实心板）
        paint.shader = LinearGradient(
            bLeft, bTop, bLeft, bBottom,
            withAlpha(ColorUtils.blendARGB(surface, Color.WHITE, 0.06f), 1f),
            withAlpha(surface, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        // 2. 镜面高光：顶部受光面
        paint.shader = LinearGradient(
            bLeft, bTop, bLeft, bTop + h * 0.45f,
            withAlpha(specular, 0.75f * specularFactor), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        // 3. 底部内阴影：压出厚度
        paint.shader = LinearGradient(
            bLeft, bTop + h * 0.7f, bLeft, bBottom,
            Color.TRANSPARENT, withAlpha(Color.BLACK, 0.18f * innerShadowFactor),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)

        paint.shader = null
        canvas.restoreToCount(save)

        // 4. 冷银描边：上亮下淡
        rimPaint.shader = LinearGradient(
            bLeft, bTop, bLeft, bBottom,
            intArrayOf(withAlpha(rimSilver, 1f), withAlpha(rimIce, 1f), withAlpha(rimSilver, 0.35f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        rimShape.reset()
        val inset = rimPaint.strokeWidth / 2f
        rimShape.addRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            (radius - inset).coerceAtLeast(0f), (radius - inset).coerceAtLeast(0f),
            Path.Direction.CW
        )
        canvas.drawPath(rimShape, rimPaint)
        rimPaint.shader = null

        // 5. 顶部一线冰青高光
        if (h > 12f * density) {
            val y = b.top + 1f * density
            sheenPaint.shader = LinearGradient(
                b.left + radius, y, b.right - radius, y,
                intArrayOf(Color.TRANSPARENT, withAlpha(rimIce, 0.9f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(b.left + radius, y, b.right - radius, y, sheenPaint)
            sheenPaint.shader = null
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int =
        ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * factor * alphaScale).toInt().coerceIn(0, 255)
        )

    override fun setAlpha(alpha: Int) {
        alphaScale = (alpha / 255f).coerceIn(0f, 1f)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 给 View 套上磨砂玻璃底片（替换原来的静态 shape 背景）。 */
        fun attach(view: View, cornerRadiusDp: Float, warn: Boolean = false) {
            val radius = cornerRadiusDp * view.resources.displayMetrics.density
            view.background = PrismGlassDrawable(view.context, radius, warn)
        }
    }
}
