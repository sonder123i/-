package com.zcshou.gogogo.run.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.zcshou.gogogo.R

/**
 * V5.2 选中态点亮器（无色美学：曜石黑 / 纯白）。
 *
 * 透镜滑到某个 Tab 时，图标与文字从柔灰平滑过渡到高对比前景色（200ms），
 * 而不是瞬间跳色。动画只作用于单个 TextView 的颜色通道，
 * 不截图、不模糊、不触发布局测量。
 *
 * 进行中的动画挂在 view 的 tag 上而非静态集合：静态 map（哪怕 WeakHashMap）
 * 会因 update listener 闭包捕获 tab 形成 value→key 强引用，条目永不回收，
 * 旧 Activity 将无法随销毁释放。
 */
object TabTintAnimator {

    private val evaluator = ArgbEvaluator()

    fun apply(tab: TextView, selected: Boolean, animate: Boolean = true) {
        val context = tab.context
        val target = context.getColor(
            if (selected) R.color.pf_volt else R.color.dr_text_unselected
        )
        (tab.getTag(R.id.tab_tint_animator) as? ValueAnimator)?.cancel()
        if (!animate) {
            paint(tab, target)
            return
        }
        val from = tab.currentTextColor
        if (from == target) {
            paint(tab, target)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener {
                val fraction = it.animatedValue as Float
                paint(tab, evaluator.evaluate(fraction, from, target) as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tab.setTag(R.id.tab_tint_animator, null)
                }
            })
        }
        tab.setTag(R.id.tab_tint_animator, animator)
        animator.start()
    }

    private fun paint(tab: TextView, color: Int) {
        tab.setTextColor(color)
        TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color))
    }
}
