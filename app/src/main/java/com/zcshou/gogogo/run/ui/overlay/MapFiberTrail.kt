package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Polyline
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * V4.0 地图双层光纤轨迹：
 * - 底层 12dp、15% 透明度的冰川青漫反射光晕；
 * - 顶层 2.5dp、100% 不透明的白青渐变核心线（colorsValues 逐段插值）。
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
                        .width((12f * density).toInt())
                        .color(if (isDark) 0x265CE1E6.toInt() else 0x26009AB4.toInt())
                ) as? Polyline ?: return null
                val core = map.addOverlay(
                    PolylineOptions()
                        .points(points)
                        .width((2.5f * density).toInt())
                        .colorsValues(fiberColors(points.size, isDark))
                ) as? Polyline
                if (core == null) {
                    runCatching { glow.remove() }
                    return null
                }
                FiberTrail(glow, core, isDark)
            }.getOrNull()
        }

        /** 白青渐变：起点近白，沿线渐变为冰青。 */
        fun fiberColors(count: Int, isDark: Boolean): List<Int> {
            val start = if (isDark) 0xFFEAFCFE.toInt() else 0xFFCFEFF4.toInt()
            val end = if (isDark) 0xFF5CE1E6.toInt() else 0xFF007A94.toInt()
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

/** 当前位置光学光点 Marker 图标：冰青光晕 + 白热核心 + 内环。 */
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
            color = if (dark) 0x405CE1E6.toInt() else 0x40009AB4.toInt()
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = if (dark) 0xFF5CE1E6.toInt() else 0xFF0088A3.toInt()
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        canvas.drawCircle(center, center, size * 0.42f, halo)
        canvas.drawCircle(center, center, size * 0.24f, ring)
        canvas.drawCircle(center, center, size * 0.12f, core)

        val desc = BitmapDescriptorFactory.fromBitmap(bitmap)
        if (dark) iconDark = desc else iconLight = desc
        return desc
    }
}