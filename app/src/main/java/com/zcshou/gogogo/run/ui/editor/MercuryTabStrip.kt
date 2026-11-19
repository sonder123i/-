package com.zcshou.gogogo.run.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.zcshou.gogogo.run.ui.glass.GlassLensRenderer
import kotlin.math.max

/**
 * V5.0 水银液滴分段器。
 *
 * 选中胶囊由左右两条独立弹簧（阻尼 0.72 / 刚度 380）驱动：
 * 两侧起止位移不同，滑动中自然产生水平拉伸，到位回弹成果冻感。
 */
class MercuryTabStrip(
    private val host: FrameLayout,
    private val strip: LinearLayout,
    private val tabs: List<View>
) {

    private val density = host.resources.displayMetrics.density
    private val indicator = PillIndicatorView(host.context)
    private var leftSpring: SpringAnimation? = null
    private var rightSpring: SpringAnimation? = null
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
        leftSpring?.cancel()
        rightSpring?.cancel()
        leftSpring = springTo(startLeft, targetLeft) { indicator.setLeftEdge(it) }
        rightSpring = springTo(startRight, targetRight) { indicator.setRightEdge(it) }
        host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        pulse(tab)
    }

    private fun springTo(from: Float, to: Float, apply: (Float) -> Unit): SpringAnimation {
        return SpringAnimation(FloatValueHolder(from)).apply {
            setSpring(
                SpringForce(to).apply {
                    dampingRatio = 0.72f
                    stiffness = 380f
                }
            )
            addUpdateListener { _, value, _ -> apply(value) }
            start()
        }
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

    /**
     * 内层同心玻璃透镜：高透玻璃渐变 + 1.5dp 纯白镜面外圈。
     * V5.2 起彻底移除原先 pf_volt 120α 的深色描边（浅色下呈生硬黑框）。
     */
    private inner class PillIndicatorView(context: Context) : View(context) {

        var leftEdge = 0f
            private set
        var rightEdge = 0f
            private set

        fun setLeftEdge(value: Float) {
            leftEdge = value
            invalidate()
        }

        fun setRightEdge(value: Float) {
            rightEdge = value
            invalidate()
        }

        private val dp = { v: Float -> v * density }

        fun setBounds(left: Float, right: Float) {
            leftEdge = left
            rightEdge = right
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = leftEdge + dp(1.5f)
            val r = max(l + dp(12f), rightEdge - dp(1.5f))
            val t = dp(1f)
            val b = height - dp(1f)
            GlassLensRenderer.drawInnerLens(
                canvas = canvas,
                context = context,
                left = l,
                top = t,
                right = r,
                bottom = b,
                radius = (b - t) / 2f,
                density = density
            )
        }
    }
}
