package com.zcshou.gogogo.run.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R

/**
 * 单个导航格：图标在上、文字在下（对齐 capybara 的 NavTab）。
 *
 * 三件事必须逐帧一致：
 * 1. **图标抬升**：选中时上移 2dp、放大 10%；按压时下压 1dp、缩小 4%；
 * 2. **字重交叉淡入**：常规字重与加粗字重两层叠在同一基线，按选中权重互补淡入。
 *    两层都按加粗字重测量并居中，所以切换时文字**不会左右跳** —— 单层直接换
 *    Typeface 会跳半个字宽，正是这一层要解决的问题；
 * 3. **着色插值**：未选中取未选中色的 50% 透明，随透镜滑入线性点亮到强调色。
 *
 * 动作时钟 [iconPhase] 交给 [NavGlyph]，这里只管几何。
 */
class NavTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val iconSize = dp(24f)
    private val iconLabelGap = dp(2f)
    /** 标签字号。11sp 与 capybara 的 `labelSmall` 同档；10sp 在中文下偏小。 */
    private val labelTextSize = 11f * resources.displayMetrics.scaledDensity

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = labelTextSize
    }
    private val regularTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /** 图标规格。 */
    var spec: NavGlyphSpec = NavGlyphSpec.ROUTE
        set(value) {
            field = value
            invalidate()
        }

    /** 未选中时图标与文字的基色。 */
    var tintUnselected: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }

    /** 选中时图标的强调色。 */
    var tintAccent: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    /** 选中时文字的颜色（通常比强调色更中性）。 */
    var tintLabelSelected: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    /** 选中权重 0..1，由透镜位置驱动，可以是小数。 */
    var weight: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            invalidate()
        }

    /** 按压进度 0..1（已乘过选中权重）。 */
    var pressProgress: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            invalidate()
        }

    /** 图标动作时钟 0..1。 */
    var iconPhase: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        // 手势统一由 LiquidTabBar 的 scrub 层处理；这里只作为视觉与无障碍节点
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        attrs?.let { readAttributes(it) }
    }

    private fun readAttributes(attrs: AttributeSet) {
        val typed = context.obtainStyledAttributes(attrs, R.styleable.NavTabView)
        spec = when (typed.getInt(R.styleable.NavTabView_navGlyph, 0)) {
            1 -> NavGlyphSpec.MAP
            2 -> NavGlyphSpec.SHIELD
            3 -> NavGlyphSpec.SLIDERS
            else -> NavGlyphSpec.ROUTE
        }
        typed.getString(R.styleable.NavTabView_navLabel)?.let { label = it }
        typed.recycle()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isSelected = weight > 0.5f
        info.className = "android.widget.Button"
        info.text = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelWidth = maxOf(measureWith(regularTypeface), measureWith(boldTypeface))
        val desiredWidth = maxOf(iconSize, labelWidth).toInt() + paddingLeft + paddingRight
        val desiredHeight = (iconSize + iconLabelGap + labelPaint.textSize * 1.32f).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun measureWith(typeface: Typeface): Float {
        val previous = labelPaint.typeface
        labelPaint.typeface = typeface
        val width = labelPaint.measureText(label)
        labelPaint.typeface = previous
        return width
    }

    override fun onDraw(canvas: Canvas) {
        drawContent(canvas, overrideTint = null)
    }

    /**
     * 把本格内容画到任意画布上，可选覆盖着色。
     *
     * 抽出来是为了让同一份几何只有**一处**定义：底栏的按压态、以及将来任何
     * 需要把格子画到别处（迷你胶囊、快照导出）的地方，都读这段代码。
     * 用 `View.draw()` 截图复制内容是不可靠的 —— View 挂上硬件渲染节点后，
     * `draw()` 可能拿到上一帧的层内容（实测副本是旧的未选中灰且带旧位移）。
     */
    fun drawContent(
        canvas: Canvas,
        overrideTint: Int?,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        alphaScale: Float = 1f,
        forceWeight: Float? = null
    ) {
        val w = forceWeight?.coerceIn(0f, 1f) ?: weight
        val press = if (forceWeight != null) 0f else pressProgress
        val cx = width / 2f + offsetX
        val contentHeight = iconSize + iconLabelGap + labelPaint.textSize * 1.32f
        val top = paddingTop +
            ((height - paddingTop - paddingBottom - contentHeight) / 2f) + offsetY

        // ---- 图标：抬升 + 放大，按压反向
        val iconScale = (1f + 0.10f * w) * (1f - 0.04f * press)
        val iconOffsetY = -dp(2f) * w + dp(1f) * press
        val iconTint = overrideTint ?: ColorUtils.blendARGB(tintUnselected, tintAccent, w)
        canvas.save()
        canvas.translate(cx, top + iconSize / 2f + iconOffsetY)
        canvas.scale(iconScale, iconScale)
        NavGlyph.draw(
            canvas = canvas,
            left = -iconSize / 2f,
            top = -iconSize / 2f,
            size = iconSize,
            spec = spec,
            tint = scaleAlpha(iconTint, alphaScale),
            weight = w,
            phase = iconPhase
        )
        canvas.restore()

        // ---- 文字：常规/加粗两层同基线交叉淡入
        val labelColor = overrideTint
            ?: ColorUtils.blendARGB(tintUnselected, tintLabelSelected, w)
        val baseline = top + iconSize + iconLabelGap + labelPaint.textSize
        val alpha = Color.alpha(labelColor) * alphaScale

        labelPaint.typeface = regularTypeface
        labelPaint.color = ColorUtils.setAlphaComponent(labelColor, (alpha * (1f - w)).toInt())
        if (w < 0.999f) canvas.drawText(label, cx, baseline, labelPaint)

        labelPaint.typeface = boldTypeface
        labelPaint.color = ColorUtils.setAlphaComponent(labelColor, (alpha * w).toInt())
        if (w > 0.001f) canvas.drawText(label, cx, baseline, labelPaint)

        labelPaint.typeface = regularTypeface
    }

    private fun scaleAlpha(color: Int, scale: Float): Int {
        if (scale >= 1f) return color
        return ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * scale.coerceIn(0f, 1f)).toInt()
        )
    }
}
