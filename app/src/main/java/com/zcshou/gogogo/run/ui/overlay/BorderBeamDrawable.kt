package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.zcshou.gogogo.R
import kotlin.math.sqrt

/**
 * 边缘流光（Border Beam）：一段彗星状高光沿圆角矩形周长绕行，外加一圈缓慢流转的色散描边。
 *
 * 对应 Google AI Mode 加载态那种「边缘在流动」的效果——彩色只出现在 1~2dp 的描边上，
 * 填充保持中性磨砂，所以白玻璃依然干净。
 *
 * ## 为什么用弧长而不是 SweepGradient 转
 *
 * 扫描渐变按**角度**等分。一个 288×40dp 的胶囊，长直边只占很小角度却要走很长距离，
 * 圆头占大角度但周长极短——转起来就是「直边慢、圆头瞬间冲过去」，一眼假。
 * 跑动的光核因此用 [PathMeasure] 按**弧长**取位置，速度在视觉上恒定。
 *
 * ## 为什么用软光斑铺贴而不是分段描边
 *
 * 早期版本把拖尾切成十几段直线逐段 stroke，段与段之间 alpha / 线宽突变，
 * 放大看有明显的阶梯。这里改成沿路径等距铺贴**软边光斑**（预渲染的径向渐变 bitmap）：
 * 相邻光斑自然重叠，形成连续衰减的彗尾，且每帧只是 drawBitmap，比 stroke 更便宜。
 *
 * ## 呼吸
 *
 * 三条不同周期的正弦叠加驱动 [breath]，调制拖尾长度、头部大小、整体亮度与色散环浓度。
 * 三个周期（3.1s / 4.7s / 2.3s）互质且都不等于绕行周期，避免「呼吸和绕行绑在一起」
 * 那种一眼就能看出规律的机械感。
 *
 * ## 性能
 *
 * 静止时不绘制；帧节拍由宿主驱动（见 FloatingCapsuleView 的 beam 帧循环），
 * 折叠态降帧、展开态全速、锁屏完全停止。
 */
class BorderBeamDrawable @JvmOverloads constructor(
    private val context: Context,
    private val cornerRadiusPx: Float,
    private val borderWidthPx: Float
) : Drawable() {

    private val density = context.resources.displayMetrics.density

    /** 0..1 的周长相位：0 = 起点，1 = 绕完一圈。 */
    var phase = 0f
        set(value) {
            field = value % 1f
            if (field < 0f) field += 1f
            invalidateSelf()
        }

    /**
     * 0..1 的色散环相位，周期与 [phase] **不同**（宿主用更慢的时长驱动）。
     *
     * 必须和光核解耦：若两者同周期，整圈色带会跟着彗尾一起转，看起来像一条被绑住的光带，
     * 少了「薄膜在底下缓慢流动」的层次感。旋转角按完整 360° 走，回绕时视觉连续。
     */
    var ringPhase = 0f
        set(value) {
            field = value % 1f
            if (field < 0f) field += 1f
            invalidateSelf()
        }

    /**
     * 呼吸量 0..1：0 = 收（最暗最小），1 = 张（最亮最大）。
     * 由宿主每帧写入，本类只负责把它映射到各个视觉参数上。
     */
    var breath = 0.5f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (kotlin.math.abs(field - clamped) < 0.002f) return
            field = clamped
            invalidateSelf()
        }

    /** 整体强度 0..1：告警/暂停等状态可整体压暗或熄灯。 */
    var intensity = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidateSelf()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()
    private val measure = PathMeasure()
    private val sweepMatrix = Matrix()
    private val stampRect = RectF()
    private val posTan = FloatArray(2)

    private var pathLength = 0f
    private var lastLeft = Int.MIN_VALUE
    private var lastTop = Int.MIN_VALUE
    private var lastRight = Int.MIN_VALUE
    private var lastBottom = Int.MIN_VALUE

    /**
     * 流光色：这里必须取**不透明**的色相，透明度统一在绘制时按强度/拖尾曲线乘。
     * 若直接复用 `dr_prism_sheen_*`（本身就带 20~30% alpha），再乘一次强度会淡到看不见。
     */
    private val beamColors = intArrayOf(
        opaqueOf(R.color.dr_prism_sheen_a, 0xFFFF8AC8.toInt()),
        opaqueOf(R.color.dr_prism_sheen_b, 0xFFA78BFA.toInt()),
        opaqueOf(R.color.dr_prism_sheen_c, 0xFFFFC46B.toInt()),
        opaqueOf(R.color.dr_prism_sheen_d, 0xFF7DE2FF.toInt())
    )

    /**
     * 预渲染的软光斑：白核 → 透明边缘的径向渐变。
     * 上色靠 [PorterDuffColorFilter]（SRC_IN），所以一张 bitmap 可以铺任意色相的彗尾。
     */
    private val dotBitmap: Bitmap by lazy { createSoftDot() }

    /**
     * 彗尾色阶：预建 ColorFilter 避免每帧 new 对象。
     * 步数够多（24）才能让颜色过渡看不出分段，又不至于让 GC 有压力。
     */
    private val tintSteps = 24
    private val tintFilters: Array<ColorFilter?> by lazy {
        Array(tintSteps) { i ->
            val t = if (tintSteps <= 1) 1f else i / (tintSteps - 1).toFloat()
            PorterDuffColorFilter(colorAt(t), PorterDuff.Mode.SRC_IN)
        }
    }

    /** 拖尾占周长的比例（呼吸会在它基础上 ±15%）。 */
    private val tailRatio = 0.30f

    /** 每帧铺贴的光斑数：越多越连续，24 段在 40~290dp 的周长上都看不出接缝。 */
    private val stamps = 26

    override fun draw(canvas: Canvas) {
        if (intensity <= 0.01f) return
        val b = bounds
        if (b.isEmpty) return
        if (!ensurePath(b)) return

        drawDispersionRing(canvas, b)
        drawBeam(canvas)
    }

    /** 圆角矩形路径只在尺寸变化时重建——PathMeasure 的 setPath 不便宜。 */
    private fun ensurePath(b: android.graphics.Rect): Boolean {
        if (b.left == lastLeft && b.top == lastTop &&
            b.right == lastRight && b.bottom == lastBottom && pathLength > 0f
        ) {
            return true
        }
        lastLeft = b.left
        lastTop = b.top
        lastRight = b.right
        lastBottom = b.bottom

        rect.set(b)
        val inset = borderWidthPx / 2f
        val radius = (cornerRadiusPx - inset).coerceAtLeast(0f)
        path.reset()
        path.addRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius, radius, Path.Direction.CW
        )
        measure.setPath(path, false)
        pathLength = measure.length
        return pathLength > 1f
    }

    /**
     * 整圈色散：SweepGradient 按相位旋转。
     *
     * 它只是「颜色在缓慢流动」，速度不匀看不出来，所以这一层继续用扫描渐变——
     * 用弧长采样反而要切上百段，得不偿失。浓度受呼吸调制，让整条边跟着一起「起伏」。
     *
     * 旋转角必须走**完整 360°**：扫描渐变是周期性的，转到 360° 等价于 0°，
     * 所以 phase 从 1 回绕到 0 时视觉上是连续的。早期写成 `phase * 180f`，
     * 结果每圈结束角度从 178° 瞬间跳回 0°，表现为「绕完一圈颜色突变一次」。
     */
    private fun drawDispersionRing(canvas: Canvas, b: android.graphics.Rect) {
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        // 不透明色入组，透明度统一由 ringPaint.alpha 控制（否则两层 alpha 相乘会过淡）
        val colors = intArrayOf(
            beamColors[0], beamColors[1], beamColors[2], beamColors[3], beamColors[0]
        )
        ringPaint.shader = SweepGradient(cx, cy, colors, null)
        sweepMatrix.setRotate(ringPhase * 360f, cx, cy)
        ringPaint.shader?.setLocalMatrix(sweepMatrix)

        val breathe = 0.72f + 0.28f * breath
        // 两层：先宽一圈做柔光底，再窄一圈做清晰色带。
        // 大面板（288dp 控制岛）周长长，单层 1.6dp 会被稀释到看不见。
        ringPaint.strokeWidth = borderWidthPx * 3.2f
        ringPaint.alpha = (105 * intensity * breathe).toInt().coerceIn(0, 255)
        canvas.drawPath(path, ringPaint)

        ringPaint.strokeWidth = borderWidthPx
        ringPaint.alpha = (240 * intensity * breathe).toInt().coerceIn(0, 255)
        canvas.drawPath(path, ringPaint)

        ringPaint.shader = null
        ringPaint.alpha = 255
    }

    /**
     * 彗尾：沿路径从尾到头等距铺贴软光斑。
     *
     * 每个光斑的透明度按 `t^2.4` 增长（能量压在头部），尺寸按 `t` 线性增长，
     * 相邻光斑重叠后形成连续衰减——比分段 stroke 少了段落突变，也自然带出
     * 类似运动模糊的拖影。
     */
    private fun drawBeam(canvas: Canvas) {
        // 呼吸调制拖尾长度：张的时候尾巴更长更舒展
        val tailLen = pathLength * tailRatio * (0.86f + 0.28f * breath)
        val head = phase * pathLength
        val baseSize = borderWidthPx * 4.2f * (0.92f + 0.16f * breath)

        for (i in 0..stamps) {
            val t = i / stamps.toFloat()
            // 沿周长往回取位置：t=0 是尾端，t=1 是头部
            val dist = normalize(head - tailLen * (1f - t))
            if (!measure.getPosTan(dist, posTan, null)) continue

            // 亮度：尾部几乎透明，头部最亮；指数 2.5 让能量更集中在头
            val energy = t * t * sqrt(t)
            val alpha = (255f * energy * intensity).toInt().coerceIn(0, 255)
            if (alpha <= 2) continue

            val size = baseSize * (0.28f + 0.72f * t)
            val half = size / 2f
            stampRect.set(posTan[0] - half, posTan[1] - half, posTan[0] + half, posTan[1] + half)

            dotPaint.alpha = alpha
            dotPaint.colorFilter = tintFilters[(t * (tintSteps - 1)).toInt().coerceIn(0, tintSteps - 1)]
            canvas.drawBitmap(dotBitmap, null, stampRect, dotPaint)
        }

        // 头部光核：小半径径向光晕，让「核」有实体感；半径随呼吸轻微胀缩
        val headDist = normalize(head)
        if (measure.getPosTan(headDist, posTan, null)) {
            val glowR = borderWidthPx * 3.6f * (0.88f + 0.24f * breath)
            headPaint.shader = RadialGradient(
                posTan[0], posTan[1], glowR,
                intArrayOf(
                    withAlpha(Color.WHITE, 0.95f * intensity),
                    withAlpha(beamColors[1], 0.5f * intensity),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(posTan[0], posTan[1], glowR, headPaint)
            headPaint.shader = null
        }

        dotPaint.alpha = 255
        dotPaint.colorFilter = null
    }

    /** 沿 t 在四色之间插值，得到彗尾的色相过渡。 */
    private fun colorAt(t: Float): Int {
        val f = t.coerceIn(0f, 1f) * (beamColors.size - 1)
        val i = f.toInt().coerceIn(0, beamColors.size - 2)
        return ColorUtils.blendARGB(beamColors[i], beamColors[i + 1], f - i)
    }

    /**
     * 生成软光斑：中心实心白、外缘平滑归零。
     *
     * 用 radial 渐变而不是简单的实心圆：实心圆的边缘会在铺贴时出现一圈圈硬边，
     * 只有羽化到透明的光斑重叠起来才是连续的。
     */
    private fun createSoftDot(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                r, r, r,
                intArrayOf(
                    Color.WHITE,
                    Color.argb(220, 255, 255, 255),
                    Color.argb(90, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(r, r, r, p)
        return bmp
    }

    private fun opaqueOf(colorRes: Int, fallback: Int): Int {
        val c = runCatching { context.getColor(colorRes) }.getOrDefault(fallback)
        return Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun normalize(distance: Float): Float {
        var d = distance % pathLength
        if (d < 0f) d += pathLength
        return d
    }

    private fun withAlpha(color: Int, factor: Float): Int =
        ColorUtils.setAlphaComponent(
            color,
            (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        )

    override fun setAlpha(alpha: Int) {
        intensity = (alpha / 255f).coerceIn(0f, 1f)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
