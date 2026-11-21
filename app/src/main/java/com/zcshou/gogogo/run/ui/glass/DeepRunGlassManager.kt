package com.zcshou.gogogo.run.ui.glass

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.service.RenderGate
import java.util.Collections
import java.util.WeakHashMap

/**
 * V4.1 玻璃材质调度中心（静态磨砂，零 GPU 采样）。
 *
 * 职责只剩两件事：
 * 1. 维护同屏玻璃控件注册表，主题切换时统一刷新材质色；
 * 2. 给设置页提供「材质说明」文案。
 *
 * V4.0 的三层降级（AGSL 折射 / RenderEffect 模糊 / 磨砂）已全部下线：
 * 逐帧截屏 + 实时高斯模糊会与百度地图抢 GPU，真机上直接卡死。
 * 现在所有设备统一使用同一套静态磨砂材质，渲染开销恒定为一层圆角矩形填充。
 *
 * 本 object 同时是 run/service.RenderGate 的实现方：服务层（RunSessionService）
 * 只依赖接口发布「巡航/锁屏冻结渲染」，不反向 import UI 包。
 */
object DeepRunGlassManager : RenderGate {

    init {
        // object 与应用同生命周期，注册一次即可；服务层经 RenderGate 接口调用本实现
        RenderGate.register(this)
    }

    /** 材质等级：V4.1 只剩静态磨砂一种。 */
    enum class Tier(val title: String, val detail: String) {
        MATTE("原生磨砂玻璃", "静态高透磨砂 + 1dp 高光内描边，零采样零掉帧")
    }

    private val views = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassContainer, Boolean>()
    )

    /** 巡航 RUNNING 或锁屏期间为 true（保留给宿主查询，不再影响绘制）。 */
    @Volatile
    var isRenderSuppressed: Boolean = false
        private set

    private var suppressReason: String = ""

    // ---------------------------------------------------------------- 材质说明

    fun tier(@Suppress("UNUSED_PARAMETER") context: Context): Tier = Tier.MATTE

    /** 设置页副标题用：材质 + 可能的系统级提示。 */
    fun describe(context: Context): String {
        val reason = downgradeReason(context)
        return if (reason == null) Tier.MATTE.detail else "${Tier.MATTE.title}（$reason）"
    }

    fun downgradeReason(context: Context): String? = when {
        isPowerSave(context) -> "系统省电模式"
        isHighContrastText(context) -> "系统高对比度文字"
        DisplayPrefs.quality(context) == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡"
        else -> null
    }

    // ---------------------------------------------------------------- 省电标记

    /**
     * 进入 RUNNING / 锁屏时调用 true，恢复前台或结束后调用 false。
     * V4.1 只记录状态供宿主查询，玻璃本身是静态的，无需冻结任何绘制。
     */
    override fun setRenderSuppressed(suppressed: Boolean, reason: String) {
        if (isRenderSuppressed == suppressed) return
        isRenderSuppressed = suppressed
        suppressReason = if (suppressed) reason else ""
    }

    fun suppressReason(): String = suppressReason

    /** 地图相机开始 / 结束移动。V4.1 无采样，仅保留兼容空实现。 */
    fun setMapInteracting(@Suppress("UNUSED_PARAMETER") interacting: Boolean) = Unit

    // ---------------------------------------------------------------- 视图注册表

    internal fun register(view: LiquidGlassContainer) {
        synchronized(views) { views.add(view) }
    }

    internal fun unregister(view: LiquidGlassContainer) {
        synchronized(views) { views.remove(view) }
    }

    /** 主题切换后让所有玻璃控件重新读取材质色。 */
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
