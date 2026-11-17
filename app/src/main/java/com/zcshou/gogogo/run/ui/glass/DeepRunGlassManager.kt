package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

/**
 * V4.0 液态玻璃渲染调度中心。
 *
 * 职责：
 * 1. 三层硬件渲染自动降级（AGSL 折射 / RenderEffect 毛玻璃 / 经典磨砂）；
 * 2. 边缘斜坡厚度截断（防止小胶囊变成空心发光环）；
 * 3. 同屏玻璃控件注册表，统一派发「环境变化」与「巡航省电冻结」；
 * 4. 地图交互期间的背景采样降采样倍率。
 *
 * 本类不持有任何定位 / 巡航状态机引用，省电冻结通过 [setRenderSuppressed] 由宿主主动告知。
 */
object DeepRunGlassManager {

    /** 渲染等级，顺序即降级顺序。 */
    enum class Tier(val title: String, val detail: String) {
        OPTICAL("极致光学", "AGSL 圆角 SDF 折射 + 色散 + 陀螺仪高光"),
        BLUR("硬件毛玻璃", "RenderEffect 实时高斯模糊 + 菲涅尔亮边"),
        MATTE("经典磨砂", "零 GPU 采样的半透磨砂底板 + 精密边界线")
    }

    private val views = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassContainer, Boolean>()
    )

    /** 地图手指拖动 / 缩放期间为 true，采样率降到 0.25x。 */
    @Volatile
    private var mapInteracting = false

    /** 巡航 RUNNING 或锁屏期间为 true：冻结传感器高光与连续重绘。 */
    @Volatile
    var isRenderSuppressed: Boolean = false
        private set

    private var suppressReason: String = ""

    // ---------------------------------------------------------------- 渲染等级

    /**
     * 当前设备与用户设置共同决定的可用等级。
     * 注意：硬件加速是逐 View 属性，由 [LiquidGlassContainer] 在绘制时二次校验。
     */
    fun tier(context: Context): Tier {
        val quality = DisplayPrefs.quality(context)
        if (isPowerSave(context) || isHighContrastText(context)) return Tier.MATTE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Tier.MATTE
        if (quality == DisplayPrefs.RenderQuality.BALANCED) return Tier.BLUR
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Tier.OPTICAL
        } else {
            Tier.BLUR
        }
    }

    /** 设置页副标题用：等级 + 被降级的原因。 */
    fun describe(context: Context): String {
        val tier = tier(context)
        val reason = downgradeReason(context)
        return if (reason == null) tier.detail else "${tier.title}（$reason）"
    }

    fun downgradeReason(context: Context): String? = when {
        isPowerSave(context) -> "系统省电模式，已降级"
        isHighContrastText(context) -> "系统高对比度文字，已降级"
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "系统版本低于 Android 12"
        DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡"
        else -> null
    }

    /**
     * 边缘折射斜坡厚度。
     * 铁律：bevelWidth < 40% * min(w, h) / 2，否则小胶囊上下折射带重叠成空心环。
     */
    fun bevelWidthPx(widthPx: Int, heightPx: Int, desiredPx: Float): Float {
        if (widthPx <= 0 || heightPx <= 0) return 0f
        val ceiling = 0.4f * min(widthPx, heightPx) / 2f
        return min(desiredPx, ceiling).coerceAtLeast(0f)
    }

    /** 背景采样倍率：拖动 0.25x，省电平衡 0.5x，静止 1.0x。 */
    fun backdropScale(context: Context): Float = when {
        mapInteracting -> 0.25f
        DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED -> 0.5f
        else -> 1f
    }

    // ---------------------------------------------------------------- 省电冻结

    /**
     * 进入 RUNNING / 锁屏时调用 true，恢复前台或结束后调用 false。
     * 冻结会立即注销陀螺仪监听并停止玻璃重绘，但保留当前静态外观。
     */
    fun setRenderSuppressed(suppressed: Boolean, reason: String = "") {
        if (isRenderSuppressed == suppressed) return
        isRenderSuppressed = suppressed
        suppressReason = if (suppressed) reason else ""
        forEachView { it.onGlassEnvironmentChanged() }
    }

    fun suppressReason(): String = suppressReason

    /** 地图相机开始移动 / 结束移动。 */
    fun setMapInteracting(interacting: Boolean) {
        if (mapInteracting == interacting) return
        mapInteracting = interacting
        forEachView { it.onGlassEnvironmentChanged() }
    }

    // ---------------------------------------------------------------- 视图注册表

    internal fun register(view: LiquidGlassContainer) {
        synchronized(views) { views.add(view) }
    }

    internal fun unregister(view: LiquidGlassContainer) {
        val nowEmpty = synchronized(views) {
            views.remove(view)
            views.isEmpty()
        }
        // 最后一个玻璃控件注销时释放整窗快照，避免屏幕内容位图常驻堆
        if (nowEmpty) GlassBackdrop.release()
    }

    /** 设置切换后让所有玻璃控件重新判定等级与采样。 */
    fun refreshAll() {
        forEachView { it.onGlassEnvironmentChanged() }
    }

    internal fun forEachView(action: (LiquidGlassContainer) -> Unit) {
        val snapshot = synchronized(views) { views.toList() }
        snapshot.forEach(action)
    }

    // ---------------------------------------------------------------- 环境探测

    private fun isPowerSave(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    }.getOrDefault(false)

    private fun isHighContrastText(context: Context): Boolean = runCatching {
        // 系统「高对比度文字」开关（Settings.Secure 无公开常量，用官方键名）
        Settings.Secure.getInt(
            context.contentResolver,
            "high_text_contrast_enabled",
            0
        ) == 1
    }.getOrDefault(false)
}
