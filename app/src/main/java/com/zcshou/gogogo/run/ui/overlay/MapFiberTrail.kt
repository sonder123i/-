package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Polyline
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * Apple 单色双层光纤轨迹：
 * - 底层 9dp、10% 透明度的中性漫反射光晕；
 * - 顶层 1.5dp 单色核心线（浅色深炭黑 / 深色纯白微光）。
 * 两条线成组管理，update/remove 成对操作。
 */
class FiberTrail private constructor(
    private val glow: Polyline,
    private val core: Polyline,
    private val isDark: Boolean
) {
    fun update(points: List<LatLng>) {
        runCatching {
            glow.points = points
            core.points = points
            // colorsValues 的数量必须与点数一致，尾迹增长时同步重建分段渐变
            core.setColorList(fiberColors(points.size, isDark).toIntArray())
        }
    }

    fun remove() {
        runCatching { glow.remove() }
        runCatching { core.remove() }
    }

    companion object {
        fun create(
            map: BaiduMap,
            points: List<LatLng>,
            density: Float,
            isDark: Boolean
        ): FiberTrail? {
            if (points.size < 2) return null
            return runCatching {
                val glow = map.addOverlay(
                    PolylineOptions()
                        .points(points)
                        .width((9f * density).toInt())
                        .color(if (isDark) 0x26000000.toInt() else 0x261D1D1F.toInt())
                ) as? Polyline ?: return null
                val core = map.addOverlay(
                    PolylineOptions()
                        .points(points)
                        .width((1.5f * density).toInt())
                        .colorsValues(fiberColors(points.size, isDark))
                ) as? Polyline
                if (core == null) {
                    runCatching { glow.remove() }
                    return null
                }
                FiberTrail(glow, core, isDark)
            }.getOrNull()
        }

        /** 单色光纤：起点略微提亮，沿线收敛到主色。 */
        fun fiberColors(count: Int, isDark: Boolean): List<Int> {
            // 深色模式地图会被反相，所以这里先画黑色，反相后呈白色光纤
            val start = if (isDark) 0xFF000000.toInt() else 0xFF3A3A3C.toInt()
            val end = if (isDark) 0xFF1D1D1F.toInt() else 0xFF1D1D1F.toInt()
            val out = ArrayList<Int>(count)
            for (i in 0 until count) {
                val t = if (count <= 1) 0f else i.toFloat() / (count - 1)
                out.add(lerpColor(start, end, t))
            }
            return out
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            fun mix(x: Int, y: Int) = (x + ((y - x) * t)).toInt().coerceIn(0, 255)
            return Color.argb(255, mix(ar, br), mix(ag, bg), mix(ab, bb))
        }
    }
}

/** 空降目标 Marker 图标：单色地图针（无彩色默认图钉）。 */
object FiberTargetPin {

    private var iconDark: BitmapDescriptor? = null
    private var iconLight: BitmapDescriptor? = null

    fun icon(context: Context): BitmapDescriptor {
        val dark = ThemeHelper.isDark(context)
        val cached = if (dark) iconDark else iconLight
        if (cached != null) return cached
        val density = context.resources.displayMetrics.density
        val size = (30f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 深色地图反相：ink 画黑→反相白，paper 画白→反相黑
        val ink = if (dark) 0xFF000000.toInt() else 0xFF1D1D1F.toInt()
        val paper = if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val cx = size / 2f
        val head = size * 0.34f
        canvas.drawCircle(cx, size * 0.38f, head, paint)
        val tail = Path().apply {
            moveTo(cx - head * 0.62f, size * 0.55f)
            lineTo(cx + head * 0.62f, size * 0.55f)
            lineTo(cx, size * 0.92f)
            close()
        }
        canvas.drawPath(tail, paint)
        paint.color = paper
        canvas.drawCircle(cx, size * 0.38f, head * 0.42f, paint)
        val desc = BitmapDescriptorFactory.fromBitmap(bitmap)
        if (dark) iconDark = desc else iconLight = desc
        return desc
    }
}

/** 当前位置光点 Marker 图标：中性光晕 + 单色内环 + 高对比核心。 */
object FiberPositionDot {

    private var iconDark: BitmapDescriptor? = null
    private var iconLight: BitmapDescriptor? = null

    fun icon(context: Context): BitmapDescriptor {
        val dark = ThemeHelper.isDark(context)
        val cached = if (dark) iconDark else iconLight
        if (cached != null) return cached
        val density = context.resources.displayMetrics.density
        val size = (24f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 深色地图会反相：先画黑，反相后得到白色光晕
            color = if (dark) 0x33000000.toInt() else 0x331D1D1F.toInt()
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = if (dark) 0xFF000000.toInt() else 0xFF1D1D1F.toInt()
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        }

        canvas.drawCircle(center, center, size * 0.42f, halo)
        canvas.drawCircle(center, center, size * 0.24f, ring)
        canvas.drawCircle(center, center, size * 0.12f, core)

        val desc = BitmapDescriptorFactory.fromBitmap(bitmap)
        if (dark) iconDark = desc else iconLight = desc
        return desc
    }
}