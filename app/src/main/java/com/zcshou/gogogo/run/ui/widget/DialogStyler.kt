package com.zcshou.gogogo.run.ui.widget

import android.widget.EditText
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R

/** V5.0 弹窗输入框统一材质：内嵌圆角微灰凹槽，无下划线，与磨砂玻璃弹窗一致。 */
object DialogStyler {

    fun styleInput(edit: EditText) {
        val context = edit.context
        val density = context.resources.displayMetrics.density
        edit.background = ContextCompat.getDrawable(context, R.drawable.bg_input_recess)
        edit.setPadding(
            (14 * density).toInt(),
            (12 * density).toInt(),
            (14 * density).toInt(),
            (12 * density).toInt()
        )
        edit.setTextColor(context.getColor(R.color.pf_text_primary))
        edit.setHintTextColor(context.getColor(R.color.pf_text_tertiary))
        edit.textSize = 15f
        edit.minHeight = (48 * density).toInt()
        edit.isSingleLine = false
        edit.setLines(1)
    }
}
