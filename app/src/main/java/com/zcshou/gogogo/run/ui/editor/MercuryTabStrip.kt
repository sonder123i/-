package com.zcshou.gogogo.run.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.FiberStyle
import kotlin.math.max

/**
 * V4.0 水银液滴分段器。
 *
 * 在现有分段行下方插入一枚圆角指示器，切换时以过冲弹簧插值在标签之间滑动，
 * 滑动瞬间产生轻微拉伸（果冻感），并给目标标签一次微放大反馈。
 *
 * 不依赖任何第三方库；指示器是自绘 View，避免每帧 requestLayout。
 */
class MercuryTabStrip(
    private val host: FrameLayout,
    private val strip: LinearLayout,
    private val tabs: List<View>
) {

    private val density = host.resources.displayMetrics.density
    private val indicator = PillIndicatorView(host.context)
    private var animator: ValueAnimator? = null
    private var selected = 0

    init {
        // 指示器插在最底层，避免半透明液滴盖住标签文字
        host.addView(
            indicator,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        host.post {
            if ((tabs.firstOrNull()?.width ?: 0) > 0) {
                moveTo(selected, animate = false)
            } else {
                // 首次 post 早于布局：再等一帧，保证第一帧就有指示器
                host.post { moveTo(selected, animate = false) }
            }
        }
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in tabs.indices) return
        val changed = index != selected
        selected = index
        host.post { moveTo(index, animate && changed) }
    }

    private fun moveTo(index: Int, animate: Boolean) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.width == 0) return
        val tabLoc = IntArray(2)
        val hostLoc = IntArray(2)
        tab.getLocationInWindow(tabLoc)
        host.getLocationInWindow(hostLoc)
        val targetLeft = (tabLoc[0] - hostLoc[0]).toFloat()
        val targetRight = targetLeft + tab.width

        if (!animate) {
            indicator.setBounds(targetLeft, targetRight)
            return
        }

        val startLeft = indicator.leftEdge
        val startRight = indicator.rightEdge
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                indicator.setBounds(
                    startLeft + (targetLeft - startLeft) * t,
                    startRight + (targetRight - startRight) * t
                )
            }
            start()
        }
        pulse(tab)
    }

    private fun pulse(tab: View) {
        tab.animate().cancel()
        tab.scaleX = 1f
        tab.scaleY = 1f
        tab.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(110L)
            .withEndAction {
                tab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .start()
            }
            .start()
    }

    /** 圆角液滴指示器：18% 强调色填充 + 顶部一道冷白高光。 */
    private inner class PillIndicatorView(context: Context) : View(context) {

        var leftEdge = 0f
            private set
        var rightEdge = 0f
            private set

        private val dp = { v: Float -> v * density }
        private val radius = dp(17f)
        private val rect = RectF()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.pf_volt)
            alpha = 90
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.8f)
            color = context.getColor(R.color.pf_volt)
            alpha = 120
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.7f)
            color = context.getColor(R.color.dr_glass_rim_highlight)
            alpha = 160
        }

        fun setBounds(left: Float, right: Float) {
            leftEdge = left
            rightEdge = right
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = leftEdge + dp(1f)
            val r = max(l + dp(12f), rightEdge - dp(1f))
            val t = dp(1f)
            val b = height - dp(1f)
            rect.set(l, t, r, b)
            // V4.0 光纤渐变：中心近白、边缘泛冰青
            fillPaint.shader = FiberStyle.linear(context, width, height, vertical = true)
            fillPaint.alpha = 90
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.drawRoundRect(rect, radius, radius, rimPaint)

            // 顶部高光弧：只画上沿 60% 宽度，模拟液滴受光
            val inset = dp(6f)
            rect.set(l + inset, t + dp(1.2f), r - inset, t + dp(1.2f))
            canvas.drawLine(rect.left, rect.top, rect.right, rect.top, sheenPaint)
        }
    }
}
