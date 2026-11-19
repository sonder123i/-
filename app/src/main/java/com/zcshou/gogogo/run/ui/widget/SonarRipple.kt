package com.zcshou.gogogo.run.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.zcshou.gogogo.R
import kotlin.math.hypot

/**
 * 冰青色声呐扩散波（Sonar Ripple）。
 *
 * 只在长按蓄力完成时临时挂载一个透明 Overlay View，700ms 画 2~3 圈扩散圆环后自行移除，
 * 不做任何持续绘制；父容器需是 FrameLayout 系（LiquidGlassContainer 满足）。
 */
object SonarRipple {

    fun emit(anchor: View, container: ViewGroup) {
        if (container.width <= 0 || container.height <= 0) return
        val context = container.context
        val anchorLoc = IntArray(2)
        val hostLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        container.getLocationOnScreen(hostLoc)
        val cx = anchorLoc[0] - hostLoc[0] + anchor.width / 2f
        val cy = anchorLoc[1] - hostLoc[1] + anchor.height / 2f
        val maxRadius = hypot(
            maxOf(cx, container.width - cx).toDouble(),
            maxOf(cy, container.height - cy).toDouble()
        ).toFloat()

        val overlay = SonarView(context, cx, cy, maxRadius)
        val lp = if (container is FrameLayout) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay.isClickable = false
        overlay.isFocusable = false
        container.addView(overlay, lp)
        overlay.start {
            runCatching { container.removeView(overlay) }
        }
    }

    private class SonarView(
        context: Context,
        private val cx: Float,
        private val cy: Float,
        private val maxRadius: Float
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * resources.displayMetrics.density
            color = context.getColor(R.color.dr_fiber_cyan)
        }
        private val density = resources.displayMetrics.density
        private var progress = 0f

        fun start(onEnd: () -> Unit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700L
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 三圈相位错开的扩散波，最外圈先到达边缘
            for (index in 0 until 3) {
                val phase = progress - index * 0.14f
                if (phase <= 0f) continue
                val p = phase.coerceIn(0f, 1f)
                val radius = maxRadius * p
                paint.alpha = ((1f - p) * 150).toInt().coerceIn(0, 255)
                paint.strokeWidth = (1.8f - p * 1.0f) * density
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }
}
