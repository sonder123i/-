package com.zcshou.gogogo.run.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.zcshou.gogogo.R

/**
 * V5.0 Z 轴四层光学材质容器（零采样、零逐帧模糊）。
 *
 * - L1 [lgMatte]：92% 高密度哑光陶瓷 + 0.5dp 极细内描边，用于次级信息容器；
 * - L2 [lgPrism]：对角线微光泽 + 1.5dp 棱镜虹彩描边 + 顶部 1dp 天光亮边，
 *   配合 8dp 大半径低透明度弥散阴影，用于核心焦点卡；
 * - L3 [lgLens]：90%~93% 高透悬浮透镜，用于导航胶囊/弹窗底板，防文字穿透；
 * - 默认：V4 静态磨砂 + 顶部高光，兼容旧调用点。
 *
 * 性能铁律：不截取底层视图、不做 RenderEffect 实时模糊；绘制只在尺寸/主题变化时发生，
 * 滚动或巡航期间玻璃本身 0 重绘。
 */
class LiquidGlassContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = Path()
    private val shapeRect = RectF()

    private var cornerRadiusPx = 24f * density
    private var interactive = true
    private var matte = false
    private var prism = false
    private var lens = false

    private var tintColor: Int = 0
    private var hasExplicitTint = false
    private var rimShadowColor: Int = 0

    // L1 / L2 / L3 主题色缓存（onGlassEnvironmentChanged 时刷新）
    private var matteSurface = 0
    private var prismTop = 0
    private var prismBottom = 0
    private var lensSurface = 0

    private var pressAnimator: ValueAnimator? = null

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassContainer)
        cornerRadiusPx = ta.getDimension(
            R.styleable.LiquidGlassContainer_lgCornerRadius, 24f * density
        )
        interactive = ta.getBoolean(R.styleable.LiquidGlassContainer_lgInteractive, true)
        hasExplicitTint = ta.hasValue(R.styleable.LiquidGlassContainer_lgTint)
        tintColor = ta.getColor(
            R.styleable.LiquidGlassContainer_lgTint,
            context.getColor(R.color.dr_glass_surface_tint)
        )
        matte = ta.getBoolean(R.styleable.LiquidGlassContainer_lgMatte, false)
        prism = ta.getBoolean(R.styleable.LiquidGlassContainer_lgPrism, false)
        lens = ta.getBoolean(R.styleable.LiquidGlassContainer_lgLens, false)
        ta.recycle()

        loadThemeColors()
        applySoftShadow()

        setWillNotDraw(false)
        isClickable = interactive
        isFocusable = interactive
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, safeRadius())
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DeepRunGlassManager.register(this)
    }

    override fun onDetachedFromWindow() {
        DeepRunGlassManager.unregister(this)
        pressAnimator?.cancel()
        pressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        invalidate()
    }

    // ---------------------------------------------------------------- 对外 API

    fun onGlassEnvironmentChanged() {
        loadThemeColors()
        if (!hasExplicitTint) {
            tintColor = context.getColor(R.color.dr_glass_surface_tint)
        }
        invalidate()
    }

    /** 保留 API 兼容：静态材质无需重采样。 */
    fun refreshBackdrop() = Unit

    /** 保留 API 兼容：不再使用外部位图作为玻璃背景。 */
    fun setExternalBackdrop(bitmap: Bitmap, originX: Float, originY: Float, scale: Float) = Unit

    fun clearExternalBackdrop() = Unit

    fun setGlassCornerRadius(px: Float) {
        cornerRadiusPx = px.coerceAtLeast(0f)
        invalidateOutline()
        invalidate()
    }

    fun setGlassTint(color: Int) {
        tintColor = color
        hasExplicitTint = true
        invalidate()
    }

    fun setInteractiveGlass(enabled: Boolean) {
        interactive = enabled
        isClickable = enabled
    }

    fun setGlassLevel(matteLevel: Boolean, prismLevel: Boolean, lensLevel: Boolean) {
        matte = matteLevel
        prism = prismLevel
        lens = lensLevel
        invalidate()
    }

    // ---------------------------------------------------------------- 绘制

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            buildPath()
            when {
                matte -> drawMatteSurface(canvas)
                prism -> drawPrismSurface(canvas)
                lens -> drawLensSurface(canvas)
                else -> drawFrostedSurface(canvas)
            }
        }
        super.draw(canvas)
    }

    private fun buildPath() {
        shapeRect.set(0f, 0f, width.toFloat(), height.toFloat())
        shapePath.reset()
        shapePath.addRoundRect(shapeRect, safeRadius(), safeRadius(), Path.Direction.CW)
    }

    /** L1 · 哑光陶瓷：高密度磨砂 + 1.5dp 冷银白镜面切边。 */
    private fun drawMatteSurface(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = if (hasExplicitTint) tintColor else matteSurface
        canvas.drawPath(shapePath, fillPaint)

        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    /** L2 · 棱镜液态玻璃：对角线受光渐变 + 虹彩描边 + 顶部天光。 */
    private fun drawPrismSurface(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        if (hasExplicitTint) {
            fillPaint.shader = null
            fillPaint.color = tintColor
        } else {
            fillPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                prismTop, prismBottom, Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(shapePath, fillPaint)

        // 曲面微光泽：左上角一小片对角线高光，模拟斜射光在微凸玻璃上的反射
        fillPaint.shader = LinearGradient(
            0f, 0f, width * 0.62f, height * 0.5f,
            intArrayOf(0x24FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(shapePath, fillPaint)
        fillPaint.shader = null

        // 1.5dp 冷银白镜面切边（顶部亮、底部微透）
        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    /** L3 · 高透悬浮透镜：90%~93% 底色 + 上下 1dp 银白锐利高光。 */
    private fun drawLensSurface(canvas: Canvas) {
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = if (hasExplicitTint) tintColor else lensSurface
        canvas.drawPath(shapePath, fillPaint)
        GlassLensRenderer.drawOuterCapsuleRim(
            canvas,
            context,
            width,
            height,
            safeRadius(),
            density
        )
    }

    /** 兼容层：V4 静态磨砂 + 顶部高光。 */
    private fun drawFrostedSurface(canvas: Canvas) {
        val h = height.toFloat()
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tintColor
        canvas.drawPath(shapePath, fillPaint)

        val shadowTop = h * 0.75f
        fillPaint.shader = LinearGradient(
            0f, shadowTop, 0f, h,
            0x00000000, rimShadowColor,
            Shader.TileMode.CLAMP
        )
        fillPaint.alpha = 0x12
        canvas.drawPath(shapePath, fillPaint)
        fillPaint.shader = null
        fillPaint.alpha = 255
        GlassLensRenderer.drawCardRim(
            canvas,
            context,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            safeRadius(),
            density
        )
    }

    private fun safeRadius(): Float =
        cornerRadiusPx.coerceIn(0f, minOf(width, height) / 2f)

    private fun loadThemeColors() {
        rimShadowColor = context.getColor(R.color.dr_glass_rim_shadow)
        matteSurface = context.getColor(R.color.dr_l1_matte_surface)
        prismTop = context.getColor(R.color.dr_l2_glass_top)
        prismBottom = context.getColor(R.color.dr_l2_glass_bottom)
        lensSurface = context.getColor(R.color.dr_l3_lens_surface)
    }

    /** L2 弥散阴影：≤8% 透明度，大半径柔和，不产生硬边。 */
    private fun applySoftShadow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = 0x0D000000
            outlineSpotShadowColor = 0x14000000
        }
    }

    // ---------------------------------------------------------------- 按压反馈

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (interactive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animatePress(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(false)
            }
        }
        return handled
    }

    /** Liquid Press：按下微缩，松手过冲回弹（纯变换，不触发重绘）。 */
    private fun animatePress(pressed: Boolean) {
        pressAnimator?.cancel()
        val target = if (pressed) 0.97f else 1f
        pressAnimator = ValueAnimator.ofFloat(scaleX, target).apply {
            duration = if (pressed) 120L else 280L
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(1.6f)
            addUpdateListener {
                val value = it.animatedValue as Float
                scaleX = value
                scaleY = value
            }
            start()
        }
    }
}
