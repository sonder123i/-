package com.zcshou.gogogo.run.ui.glass

import android.os.SystemClock
import android.view.Choreographer
import com.example.liquidglass.LiquidGlassView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * V6.0 光学玻璃刷新闸门（单节拍共享采样）。
 *
 * 液态玻璃库默认每块玻璃在 onDraw 末尾 `invalidate()` 自循环，N 块面板就会
 * 各自抢帧重拍背景。这里统一关掉自循环，改为：
 * - 所有注册面板在同一个 Choreographer 帧里一起 `invalidate()`，
 *   地图拖动 / 页面滚动时共享同一刷新节拍；
 * - 只在交互窗口内刷新（默认 280ms，松手后自然冷却到 0fps），
 *   静止时零采样、零功耗；
 * - Activity onPause / 锁屏立即 [setActive] false，后台巡航不再刷新着色器。
 *
 * 地图页的多块面板都指向同一个 `backdropSource`（TextureMapView），
 * 由本闸门在同一帧驱动，避免各自为战。
 */
object GlassRenderGate {

    private val views = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassView, Boolean>()
    )

    private var active = true
    private var refreshUntilMs = 0L
    private var scheduled = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!active) return
            val now = SystemClock.uptimeMillis()
            val snapshot = synchronized(views) { views.toList() }
            for (view in snapshot) {
                if (view.isAttachedToWindow && view.isShown) {
                    view.invalidate()
                }
            }
            if (active && now < refreshUntilMs) schedule()
        }
    }

    fun register(view: LiquidGlassView) {
        // 统一在这里对齐光学参数，调用方无需再各自试数
        OpticalGlassTuning.apply(view)
        synchronized(views) { views.add(view) }
        // 自循环交给我们统一驱动，避免每块玻璃各抢一帧
        view.enableDynamicBackground = false
        view.collectFrameStats = false
        requestRefresh(80)
    }

    fun unregister(view: LiquidGlassView) {
        synchronized(views) { views.remove(view) }
    }

    /** 交互期间请求刷新；重复调用会把窗口顺延，松手后自动停止。 */
    fun requestRefresh(durationMs: Long = 280L) {
        refreshUntilMs = max(refreshUntilMs, SystemClock.uptimeMillis() + durationMs)
        if (active) schedule()
    }

    /** Activity / Fragment 前后台切换。 */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            requestRefresh(320L)
        } else {
            scheduled = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            // 后台/锁屏时连触摸焦散的衰减动画也一并熄灭
            val snapshot = synchronized(views) { views.toList() }
            for (view in snapshot) {
                (view as? CausticGlassView)?.cancelCaustics()
            }
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
