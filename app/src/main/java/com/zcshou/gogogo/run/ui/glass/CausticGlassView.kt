package com.zcshou.gogogo.run.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.example.liquidglass.LiquidGlassView
import kotlin.math.min

/**
 * V6.1 触摸随动焦散液态玻璃。
 *
 * 在 QWEA0 的 SDF 折射玻璃之上叠加一层 **生成式 AGSL 焦散**：
 * - 手指位置渲染柔和内部亮斑 + 一圈极细的焦散环；
 * - 同时提亮距离指尖最近的玻璃边缘高光；
 * - 按下 120ms 平滑点亮，松手 420ms 衰减归零；
 * - 只有触摸与衰减动画期间 invalidate，静止 0 重绘。
 *
 * API < 33 退化为 RadialGradient 光斑，不加载 RuntimeShader 类。
 */
class CausticGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val lensPath = Path()
    private val lensRect = RectF()
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var touchX = 0f
    private var touchY = 0f
    private var intensity = 0f
    private var fadeAnimator: ValueAnimator? = null
    private var shader33: CausticShader? = null
    private var lastDownEventTime = 0L

    init {
        setWillNotDraw(false)
        // 用 outline 让 elevation 真正投影：iOS 26 玻璃下方的大半径柔和阴影
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = min(cornerRadius, min(view.width, view.height) / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = 0x140B1220.toInt()
            outlineSpotShadowColor = 0x2E0B1220.toInt()
        }
    }

    // ---------------------------------------------------------------- 触摸追踪

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        trackTouch(event)
        return super.dispatchTouchEvent(event)
    }

    /**
     * 导航 Tab 的触摸会由宿主直接转发到玻璃的 onTouchEvent，这里也要接住。
     * 与 dispatchTouchEvent 的重复 DOWN 用 eventTime 去重。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackTouch(event)
        return super.onTouchEvent(event)
    }

    private fun trackTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.eventTime - lastDownEventTime < 20L) return
                lastDownEventTime = event.eventTime
                touchX = event.x
                touchY = event.y
                animateIntensity(1f, 120L)
            }

            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                if (intensity > 0.01f) invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animateIntensity(0f, 420L)
            }
        }
    }

    private fun animateIntensity(target: Float, durationMs: Long) {
        fadeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(intensity, target).apply {
            duration = durationMs
            interpolator = if (target > intensity) {
                DecelerateInterpolator(1.6f)
            } else {
                AccelerateDecelerateInterpolator()
            }
            addUpdateListener {
                intensity = it.animatedValue as Float
                invalidate()
            }
        }
        fadeAnimator = animator
        animator.start()
    }

    /** 后台 / 锁屏时由 GlassRenderGate 调用：立即熄灭焦散。 */
    fun cancelCaustics() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        intensity = 0f
        invalidate()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0.003f || width <= 0 || height <= 0) return

        val radius = min(width, height) * 0.58f
        val corner = min(cornerRadius, min(width, height) / 2f)
        lensRect.set(0f, 0f, width.toFloat(), height.toFloat())
        lensPath.reset()
        lensPath.addRoundRect(lensRect, corner, corner, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(lensPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = shader33 ?: CausticShaderFactory.create()?.also { shader33 = it }
            if (shader != null) {
                shader.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    corner = corner,
                    touchX = touchX,
                    touchY = touchY,
                    intensity = intensity,
                    radius = radius
                )
            } else {
                drawFallback(canvas, radius)
            }
        } else {
            drawFallback(canvas, radius)
        }
        canvas.restoreToCount(save)
    }

    /** API < 33 的冷白光斑兜底：没有生成式 AGSL，只保留触摸位置的柔光。 */
    private fun drawFallback(canvas: Canvas, radius: Float) {
        fallbackPaint.shader = RadialGradient(
            touchX, touchY, radius,
            intArrayOf(
                (0x40 * intensity).toInt().shl(24) or 0xDCEBFF,
                (0x14 * intensity).toInt().shl(24) or 0xBFD9FF,
                0x00DCEBFF
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        fallbackPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
        fallbackPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        cancelCaustics()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) cancelCaustics()
    }
}
