package com.zcshou.gogogo.mascot

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.zcshou.gogogo.R

/** 彩蛋拍立得卡：玻璃外框 + 原图 + 可选配文；资源缺失时整卡隐藏。 */
class MascotEggCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val image: ImageView
    private val caption: TextView
    private var hideRunnable: Runnable? = null

    init {
        clipChildren = false
        LayoutInflater.from(context).inflate(R.layout.view_mascot_egg_card, this, true)
        image = findViewById(R.id.eggImage)
        caption = findViewById(R.id.eggCaption)
        visibility = View.GONE
    }

    /** 返回是否成功展示（资源不存在时整卡隐藏）。 */
    fun bind(egg: MascotEgg): Boolean {
        val resId = resources.getIdentifier(egg.resName, "drawable", context.packageName)
        if (resId == 0) {
            hideImmediately()
            return false
        }
        image.setImageResource(resId)
        caption.text = egg.caption
        caption.visibility = if (egg.showCaption) View.VISIBLE else View.GONE
        visibility = View.VISIBLE
        return true
    }

    fun showFor(egg: MascotEgg, durationMs: Long = 2500L) {
        if (!bind(egg)) return
        hideRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable { visibility = View.GONE }
        hideRunnable = runnable
        postDelayed(runnable, durationMs)
    }

    fun hideImmediately() {
        hideRunnable?.let { removeCallbacks(it) }
        hideRunnable = null
        visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        hideImmediately()
        super.onDetachedFromWindow()
    }
}