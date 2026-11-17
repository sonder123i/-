package com.zcshou.gogogo.run.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import kotlin.math.abs

/**
 * V4.0 同屏共享背景采样。
 *
 * 铁律二：同屏多个玻璃控件必须共享同一次背景帧采样，严禁各自截图。
 * 本对象以「同一帧时间窗」缓存一张 decor 快照，并在绘制瞬间把所有已注册玻璃控件
 * 的 alpha 置 0，避免玻璃把自己的影像再折射一遍（递归脏数据）。
 *
 * 注意：SurfaceView 形态的地图（MapView）在这份快照里会呈现为黑块，
 * 因此 Phase 2 必须把地图统一换成 TextureMapView，这一点与蓝图铁律一致。
 */
object GlassBackdrop {

    /** 同帧判定窗口，约 60fps 一帧。 */
    private const val FRAME_MS = 12L

    private var cached: Bitmap? = null
    private var cachedAt = 0L
    private var cachedScale = 1f
    private var capturing = false

    /** 采样进行中：玻璃控件应跳过自身绘制，避免在软件 Canvas 上触发 RuntimeShader。 */
    val isCapturing: Boolean
        get() = capturing

    /**
     * 取当前帧的共享背景快照。
     * @param scale 0.25x（地图拖动）/ 0.5x（省电平衡）/ 1.0x（静止）。
     */
    fun snapshot(activity: Activity, scale: Float): Bitmap? {
        val now = SystemClock.uptimeMillis()
        val safeScale = scale.coerceIn(0.2f, 1f)
        if (cached != null && now - cachedAt < FRAME_MS && abs(cachedScale - safeScale) < 0.001f) {
            return cached
        }
        if (capturing) return cached

        val decor: View = activity.window?.decorView ?: return cached
        val decorWidth = decor.width
        val decorHeight = decor.height
        if (decorWidth <= 0 || decorHeight <= 0) return cached

        val width = (decorWidth * safeScale).toInt().coerceAtLeast(1)
        val height = (decorHeight * safeScale).toInt().coerceAtLeast(1)

        // 采样期间让玻璃自身隐身，避免自折射
        val hidden = ArrayList<Pair<View, Float>>(8)
        DeepRunGlassManager.forEachView { view ->
            if (view.alpha != 0f) {
                hidden.add(view to view.alpha)
                view.alpha = 0f
            }
        }

        capturing = true
        val bitmap = try {
            val shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(shot)
            canvas.scale(safeScale, safeScale)
            decor.draw(canvas)
            shot
        } catch (t: Throwable) {
            null
        } finally {
            hidden.forEach { (view, alpha) -> view.alpha = alpha }
            capturing = false
        }

        if (bitmap != null) {
            cached = bitmap
            cachedAt = now
            cachedScale = safeScale
        }
        return cached
    }

    /** 布局、主题或地图相机状态变化后调用，下一次取样会重拍。 */
    fun invalidate() {
        cachedAt = 0L
    }

    /**
     * 释放常驻的整窗快照位图（约 10-20MB ARGB_8888）。
     * 最后一个玻璃控件注销 / 页面退到后台时调用，避免屏幕内容位图常驻堆内存。
     */
    fun release() {
        cached = null
        cachedAt = 0L
    }

    /** 统一的活动查找（兼容 ContextThemeWrapper / ContextWrapper 链路）。 */
    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
