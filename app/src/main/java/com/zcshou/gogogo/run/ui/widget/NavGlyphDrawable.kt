package com.zcshou.gogogo.run.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 把 [NavGlyph] 包成 Drawable，供迷你胶囊的 compound drawable 使用。
 *
 * 迷你胶囊是 TextView，图标走 drawable 而不是自绘；用同一个渲染器保证它与
 * 底栏格子里的图标是**同一份几何**，不会出现"收起来之后换了套图标"。
 */
class NavGlyphDrawable(
    private val density: Float,
    private val spec: NavGlyphSpec,
    private val sizePx: Int
) : Drawable() {

    private var tintColor: Int = android.graphics.Color.BLACK
    private var alphaScale: Int = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val size = minOf(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - size) / 2f
        val top = b.top + (b.height() - size) / 2f
        NavGlyph.draw(
            canvas = canvas,
            left = left,
            top = top,
            size = size,
            spec = spec,
            tint = applyAlpha(tintColor, alphaScale),
            weight = 1f,
            phase = 1f
        )
    }

    private fun applyAlpha(color: Int, alpha: Int): Int =
        android.graphics.Color.argb(
            (alpha.coerceIn(0, 255) * android.graphics.Color.alpha(color) / 255),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )

    override fun setAlpha(alpha: Int) {
        alphaScale = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setTint(color: Int) {
        tintColor = color
        invalidateSelf()
    }

    /** 供 View 侧显式着色（compound drawable tint 在自绘 Drawable 上不生效）。 */
    fun setGlyphTint(color: Int) {
        tintColor = color
        invalidateSelf()
    }
}
