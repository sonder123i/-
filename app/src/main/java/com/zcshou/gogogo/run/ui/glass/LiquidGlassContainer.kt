package com.zcshou.gogogo.run.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.zcshou.gogogo.R

/** 焦散微光量化步数：8s 周期 × 8Hz = 64 步。 */
private const val CAUSTICS_STEPS = 64

/**
 * V4.0 液态玻璃容器。
 *
 * 三层自动降级（由 [DeepRunGlassManager] 判定，本类负责实现）：
 * - OPTICAL：API 33+，AGSL RuntimeShader 做圆角 SDF 边缘压缩折射 + 微量色散 + 菲涅尔高光；
 * - BLUR：API 31-32，RenderNode + RenderEffect 实时高斯模糊 + 亮边；
 * - MATTE：API 27-30 / 省电模式 / 高对比度 / 无硬件加速，零采样的半透磨砂底板。
 *
 * 设计约束：
 * - 背景采样必须来自 [GlassBackdrop]（同屏共享一帧），不允许本类自行截屏；
 * - 边缘斜坡厚度由 [DeepRunGlassManager.bevelWidthPx] 截断到 `40% * min(w, h) / 2` 以内；
 * - 可通过 [DeepRunGlassManager.setRenderSuppressed] 在巡航 / 锁屏期间冻结传感器与重绘。
 */
class LiquidGlassContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePath = Path()
    private val shapeRect = RectF()
    private val rimRect = RectF()
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var cornerRadiusPx = 28f * density
    private var bevelWidthPx = 7f * density
    private var refraction = 0.55f
    private var interactive = true
    private var matteOnly = false
    private var causticsEnabled = false

    private var tintColor: Int = 0
    private var matteColor: Int = 0
    private var rimHighlightColor: Int = 0
    private var rimShadowColor: Int = 0

    private var tier = DeepRunGlassManager.Tier.MATTE
    private var backdrop: Bitmap? = null
    private var backdropScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var highlightX = 0f
    private var highlightY = 0f

    private var externalBackdrop = false
    private var externalOriginX = 0f
    private var externalOriginY = 0f

    private var sensorManager: SensorManager? = null
    private var gyroscope: Sensor? = null
    private var sensorRegistered = false

    private var pressAnimator: ValueAnimator? = null

    private var shaderSource: String? = null
    private var shaderUnavailable = false
    private var debugLogged = false

    // 逐帧复用的渲染对象：Uniforms 每帧重设，对象本体按需重建
    private var glassShader: RuntimeShader? = null
    private var glassContentShader: BitmapShader? = null
    private var glassContentBitmap: Bitmap? = null
    private val glassMatrix = Matrix()
    private var rimGradient: LinearGradient? = null
    private var rimGradientHeight = -1
    private var blurNode: RenderNode? = null
    private var blurEffect: RenderEffect? = null
    private var blurNodeWidth = 0
    private var blurNodeHeight = 0

    // 镜头焦散微光：两团正弦漂移的低对比光斑；动画本身跑在 vsync 上，
    // 这里按 8Hz 量化后才 invalidate，避免常驻满帧重绘
    private var causticsPhase = 0f
    private var lastCausticsStep = -1
    private val causticsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 8000L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            val step = ((it.animatedValue as Float) * CAUSTICS_STEPS).toInt()
            if (step != lastCausticsStep) {
                lastCausticsStep = step
                causticsPhase = step.toFloat() / CAUSTICS_STEPS
                invalidate()
            }
        }
    }
    private val causticsPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassContainer)
        cornerRadiusPx = ta.getDimension(
            R.styleable.LiquidGlassContainer_lgCornerRadius, 28f * density
        )
        bevelWidthPx = ta.getDimension(
            R.styleable.LiquidGlassContainer_lgBevelWidth, 7f * density
        )
        refraction = ta.getFloat(R.styleable.LiquidGlassContainer_lgRefraction, 0.55f)
        interactive = ta.getBoolean(R.styleable.LiquidGlassContainer_lgInteractive, true)
        matteOnly = ta.getBoolean(R.styleable.LiquidGlassContainer_lgMatte, false)
        causticsEnabled = ta.getBoolean(R.styleable.LiquidGlassContainer_lgCaustics, false)
        tintColor = ta.getColor(
            R.styleable.LiquidGlassContainer_lgTint,
            context.getColor(R.color.dr_glass_surface_tint)
        )
        ta.recycle()

        matteColor = context.getColor(R.color.dr_matte_card_surface)
        rimHighlightColor = context.getColor(R.color.dr_glass_rim_highlight)
        rimShadowColor = context.getColor(R.color.dr_glass_rim_shadow)

        setWillNotDraw(false)
        isClickable = interactive
        isFocusable = interactive
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    bevelSafeRadius()
                )
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DeepRunGlassManager.register(this)
        refreshTier()
        updateCaustics()
        scheduleBackdropRefresh()
    }

    override fun onDetachedFromWindow() {
        DeepRunGlassManager.unregister(this)
        stopGyroscope()
        causticsAnimator.cancel()
        pressAnimator?.cancel()
        pressAnimator = null
        backdrop = null
        externalBackdrop = false
        glassContentShader = null
        glassContentBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        scheduleBackdropRefresh()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateGyroscope()
        updateCaustics()
    }

    // ---------------------------------------------------------------- 对外 API

    /** 由 DeepRunGlassManager 在主题 / 质量 / 省电状态变化时调用。 */
    fun onGlassEnvironmentChanged() {
        // 允许一次重试：上次 AGSL 失败可能是临时驱动状态，切质量/主题后重建
        shaderUnavailable = false
        refreshTier()
        GlassBackdrop.invalidate()
        scheduleBackdropRefresh()
        updateCaustics()
        invalidate()
    }

    /** 宿主（如地图 onCameraChangeFinish）在背景变化后主动请求重采样。 */
    fun refreshBackdrop() {
        scheduleBackdropRefresh()
    }

    /**
     * 外部背景源（如百度地图快照）。
     * 设置后不再使用 [GlassBackdrop] 的全屏共享快照，直到 [clearExternalBackdrop]。
     *
     * @param originX/originY 位图左上角在窗口坐标系中的位置
     * @param scale 位图相对屏幕像素的缩放（地图快照通常为 1f）
     */
    fun setExternalBackdrop(bitmap: Bitmap, originX: Float, originY: Float, scale: Float) {
        if (bitmap.isRecycled) return
        // Baidu 快照位图可能被 SDK 复用/回收，拷贝一份自持，避免玻璃间歇性退化为磨砂
        val owned = runCatching {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull() ?: return
        val location = IntArray(2)
        getLocationInWindow(location)
        externalOriginX = originX
        externalOriginY = originY
        backdrop = owned
        backdropScale = if (scale > 0f) scale else 1f
        offsetX = location[0] - originX
        offsetY = location[1] - originY
        externalBackdrop = true
        invalidate()
    }

    fun clearExternalBackdrop() {
        if (!externalBackdrop) return
        externalBackdrop = false
        backdrop = null
        GlassBackdrop.invalidate()
        scheduleBackdropRefresh()
    }

    fun setGlassCornerRadius(px: Float) {
        cornerRadiusPx = px.coerceAtLeast(0f)
        invalidateOutline()
        invalidate()
    }

    fun setGlassTint(color: Int) {
        tintColor = color
        invalidate()
    }

    fun setInteractiveGlass(enabled: Boolean) {
        interactive = enabled
        isClickable = enabled
        updateGyroscope()
    }

    // ---------------------------------------------------------------- 绘制

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0 && !GlassBackdrop.isCapturing) {
            drawGlassSurface(canvas)
        }
        super.draw(canvas)
    }

    private fun drawGlassSurface(canvas: Canvas) {
        if (!debugLogged) {
            debugLogged = true
            Log.i(
                "LiquidGlass",
                "glass draw tier=$tier suppressed=${DeepRunGlassManager.isRenderSuppressed} " +
                    "external=$externalBackdrop backdrop=${backdrop != null} " +
                    "tint=#${Integer.toHexString(tintColor)} matte=#${Integer.toHexString(matteColor)}"
            )
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = bevelSafeRadius()

        shapeRect.set(0f, 0f, w, h)
        shapePath.reset()
        shapePath.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)

        // 运行期再校验一次硬件加速：软件层直接退回磨砂，绝不 black out
        val effectiveTier = when {
            !isHardwareAccelerated -> DeepRunGlassManager.Tier.MATTE
            DeepRunGlassManager.isRenderSuppressed -> DeepRunGlassManager.Tier.MATTE
            else -> tier
        }
        val bevel = DeepRunGlassManager.bevelWidthPx(width, height, bevelWidthPx)

        val drawn = when (effectiveTier) {
            DeepRunGlassManager.Tier.OPTICAL -> drawOptical(canvas, bevel)
            DeepRunGlassManager.Tier.BLUR -> drawBlurred(canvas)
            DeepRunGlassManager.Tier.MATTE -> false
        }
        if (!drawn) drawMatte(canvas, effectiveTier)
        if (causticsEnabled && effectiveTier != DeepRunGlassManager.Tier.MATTE && backdrop != null) {
            drawCaustics(canvas)
        }

        drawRim(canvas, effectiveTier)
    }

    /** 焦散微光：两团缓慢漂移的极低对比度光斑，模拟光线穿过深海透镜。 */
    private fun drawCaustics(canvas: Canvas) {
        val p = causticsPhase * 2f * PI.toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val minD = min(w, h)
        val save = canvas.save()
        canvas.clipPath(shapePath)
        repeat(2) { k ->
            val a = p + k * PI.toFloat()
            val cx = w * (0.30f + 0.40f * (0.5f + 0.5f * sin(a)))
            val cy = h * (0.30f + 0.40f * (0.5f + 0.5f * cos(a * 0.8f)))
            val radius = minD * 0.42f
            causticsPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0x14FFFFFF.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, causticsPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun updateCaustics() {
        val shouldRun = causticsEnabled &&
            tier != DeepRunGlassManager.Tier.MATTE &&
            !DeepRunGlassManager.isRenderSuppressed &&
            isAttachedToWindow &&
            windowVisibility == View.VISIBLE
        if (shouldRun && !causticsAnimator.isRunning) causticsAnimator.start()
        if (!shouldRun && causticsAnimator.isRunning) causticsAnimator.cancel()
    }

    /** Tier 1：AGSL 折射。返回 false 表示需要兜底。 */
    private fun drawOptical(canvas: Canvas, bevel: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // RuntimeShader 只能在硬件画布上使用；软件画布不是"着色器损坏"，不要污染 shaderUnavailable
        if (!canvas.isHardwareAccelerated) return false
        if (shaderUnavailable) return false
        val bitmap = backdrop ?: return false
        if (bitmap.isRecycled) return false

        return try {
            val content = ensureContentShader(bitmap) ?: return false
            val scale = if (backdropScale > 0f) backdropScale else 1f
            glassMatrix.reset()
            glassMatrix.setScale(1f / scale, 1f / scale)
            // 快照原点是窗口 (0,0)：bitmap 像素 = 窗口坐标 × scale。
            // 本地图形坐标 c 对应窗口 (c + offset)，故采样点 = (c + offset) × scale，
            // 即 bitmap→canvas 映射 L(p) = p/scale - offset（localMatrix 为 bitmap→canvas 方向）。
            glassMatrix.postTranslate(-offsetX, -offsetY)
            content.setLocalMatrix(glassMatrix)

            fillPaint.shader = buildShader(content, bevel)
            canvas.drawPath(shapePath, fillPaint)
            fillPaint.shader = null
            true
        } catch (t: Throwable) {
            // AGSL 运行期异常（个别 GPU / ROM）时本次及后续一律兜底，避免反复抛异常
            shaderUnavailable = true
            glassShader = null
            fillPaint.shader = null
            Log.w("LiquidGlass", "AGSL shader unavailable, falling back to matte", t)
            false
        }
    }

    /** 复用 RuntimeShader 实例（Uniform 每帧重设即可，重建成本高）。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ensureGlassShader(): RuntimeShader? {
        glassShader?.let { return it }
        val source = ensureShaderSource() ?: return null
        return try {
            RuntimeShader(source).also { glassShader = it }
        } catch (t: Throwable) {
            shaderUnavailable = true
            Log.w("LiquidGlass", "AGSL compile failed, falling back to matte", t)
            null
        }
    }

    /** 复用 BitmapShader：仅在快照位图实例变化时重建。 */
    private fun ensureContentShader(bitmap: Bitmap): BitmapShader? {
        if (glassContentBitmap === bitmap && glassContentShader != null) return glassContentShader
        if (bitmap.isRecycled) return null
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            glassContentShader = it
            glassContentBitmap = bitmap
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildShader(content: BitmapShader, bevel: Float): RuntimeShader {
        val shader = ensureGlassShader()
            ?: throw IllegalStateException("AGSL shader unavailable")
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uRadius", bevelSafeRadius())
        shader.setFloatUniform("uBevel", bevel)
        shader.setFloatUniform("uRefract", refraction)
        shader.setFloatUniform("uDispersion", 0.8f * density)
        shader.setFloatUniform("uHighlight", highlightX, highlightY)
        shader.setFloatUniform("uRimIntensity", if (matteOnly) 0.2f else 0.75f)
        // uTint 是普通 half4（非 layout(color)），按 vec4 (r,g,b,a) 顺序传入
        shader.setFloatUniform(
            "uTint",
            Color.red(tintColor) / 255f,
            Color.green(tintColor) / 255f,
            Color.blue(tintColor) / 255f,
            Color.alpha(tintColor) / 255f
        )
        shader.setInputShader("content", content)
        return shader
    }

    /** Tier 2：RenderEffect 背景高斯模糊。返回 false 表示需要兜底。 */
    private fun drawBlurred(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!canvas.isHardwareAccelerated) return false
        val bitmap = backdrop ?: return false
        if (bitmap.isRecycled) return false
        if (!computeSourceRect(bitmap)) return false

        return try {
            val node = blurNode ?: RenderNode("deeprun-glass-blur").also { blurNode = it }
            if (blurNodeWidth != width || blurNodeHeight != height) {
                node.setPosition(0, 0, width, height)
                blurNodeWidth = width
                blurNodeHeight = height
            }
            val recording = node.beginRecording()
            recording.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
            node.endRecording()
            val radius = 16f * density
            val effect = blurEffect
                ?: RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    .also { blurEffect = it }
            node.setRenderEffect(effect)

            val save = canvas.save()
            canvas.clipPath(shapePath)
            canvas.drawRenderNode(node)
            // 模糊之上叠半透明底色，保证文字对比度
            fillPaint.shader = null
            fillPaint.color = tintColor
            canvas.drawPath(shapePath, fillPaint)
            canvas.restoreToCount(save)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Tier 3 / 所有兜底路径：零采样磨砂底板。 */
    private fun drawMatte(canvas: Canvas, effectiveTier: DeepRunGlassManager.Tier) {
        fillPaint.shader = null
        fillPaint.color = if (effectiveTier == DeepRunGlassManager.Tier.MATTE) matteColor else tintColor
        canvas.drawPath(shapePath, fillPaint)
    }

    private fun drawRim(canvas: Canvas, effectiveTier: DeepRunGlassManager.Tier) {
        val stroke = if (effectiveTier == DeepRunGlassManager.Tier.MATTE) 0.5f else 1f
        val inset = stroke * density / 2f
        rimRect.set(inset, inset, width - inset, height - inset)

        rimPaint.strokeWidth = stroke * density
        // Rim 渐变按高度缓存，主题色随 Activity 重建刷新
        if (rimGradient == null || rimGradientHeight != height) {
            rimGradient = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(rimHighlightColor, 0x00000000, rimShadowColor),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            rimGradientHeight = height
        }
        rimPaint.shader = rimGradient
        val radius = (bevelSafeRadius() - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, radius, radius, rimPaint)
        rimPaint.shader = null
    }

    // ---------------------------------------------------------------- 背景采样

    private fun scheduleBackdropRefresh() {
        if (!isAttachedToWindow) return
        post {
            if (!isAttachedToWindow) return@post
            if (externalBackdrop) {
                // 外部位图已提供，只跟随自身位置更新偏移，不重复截图
                val location = IntArray(2)
                getLocationInWindow(location)
                offsetX = location[0] - externalOriginX
                offsetY = location[1] - externalOriginY
                invalidate()
                return@post
            }
            // 冻结 / 磨砂档位下玻璃被强制为 MATTE，抓了快照也不会被消费：
            // 省电路径上不做全 decor 绘制，解冻时 onGlassEnvironmentChanged 会重新触发
            if (DeepRunGlassManager.isRenderSuppressed ||
                tier == DeepRunGlassManager.Tier.MATTE
            ) {
                return@post
            }
            val activity = GlassBackdrop.findActivity(context) ?: return@post
            val scale = DeepRunGlassManager.backdropScale(context)
            backdrop = GlassBackdrop.snapshot(activity, scale)
            backdropScale = scale
            val location = IntArray(2)
            getLocationInWindow(location)
            offsetX = location[0].toFloat()
            offsetY = location[1].toFloat()
            invalidate()
        }
    }

    private fun computeSourceRect(bitmap: Bitmap): Boolean {
        val scale = if (backdropScale > 0f) backdropScale else 1f
        // 快照按 scale 缩小：bitmap 像素 = 窗口坐标 × scale
        val left = (offsetX * scale).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top = (offsetY * scale).coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right = ((offsetX + width) * scale).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = ((offsetY + height) * scale).coerceIn(0f, bitmap.height.toFloat()).toInt()
        if (right - left <= 1 || bottom - top <= 1) return false
        srcRect.set(left, top, right, bottom)
        dstRect.set(0, 0, width, height)
        return true
    }

    private fun ensureShaderSource(): String? {
        shaderSource?.let { return it }
        return runCatching {
            context.resources.openRawResource(R.raw.liquid_glass)
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.also { shaderSource = it }
    }

    private fun bevelSafeRadius(): Float =
        cornerRadiusPx.coerceIn(0f, minOf(width, height) / 2f)

    // ---------------------------------------------------------------- 触觉 / 传感器

    private fun refreshTier() {
        tier = if (matteOnly) {
            DeepRunGlassManager.Tier.MATTE
        } else {
            DeepRunGlassManager.tier(context)
        }
        updateGyroscope()
        updateCaustics()
    }

    private fun updateGyroscope() {
        val shouldListen = interactive &&
            !matteOnly &&
            tier == DeepRunGlassManager.Tier.OPTICAL &&
            !DeepRunGlassManager.isRenderSuppressed &&
            isAttachedToWindow &&
            windowVisibility == View.VISIBLE

        if (shouldListen && !sensorRegistered) {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (manager != null && sensor != null) {
                sensorManager = manager
                gyroscope = sensor
                manager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_UI)
                sensorRegistered = true
            }
        } else if (!shouldListen && sensorRegistered) {
            stopGyroscope()
        }
    }

    private fun stopGyroscope() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(gyroListener, gyroscope)
        sensorRegistered = false
        sensorManager = null
        gyroscope = null
        highlightX = 0f
        highlightY = 0f
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 轻阻尼跟随，避免高光抖动；SENSOR_DELAY_UI 下约 15Hz 重绘
            val nx = (highlightX * 0.88f - event.values[0] * 0.05f).coerceIn(-1f, 1f)
            val ny = (highlightY * 0.88f - event.values[1] * 0.05f).coerceIn(-1f, 1f)
            // 桌面静置死区：变化量过小不重绘
            if (kotlin.math.abs(nx - highlightX) + kotlin.math.abs(ny - highlightY) < 0.01f) return
            highlightX = nx
            highlightY = ny
            invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

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

    /** Liquid Press：按下微缩，松手过冲回弹。 */
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
