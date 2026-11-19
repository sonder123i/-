package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 可限制最大高度的 ScrollView：更新说明短时按内容收紧，长时在固定高度内滚动。
 * 标准 ScrollView 只有 match_parent/wrap_content，直接用固定 160dp 会在短文案时
 * 留出一大块空白，把弹窗撑得很高。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeight: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = if (maxHeight == Int.MAX_VALUE) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}
