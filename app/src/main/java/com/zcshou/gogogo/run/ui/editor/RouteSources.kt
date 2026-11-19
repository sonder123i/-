package com.zcshou.gogogo.run.ui.editor

import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 无地图建圈的路线来源：GPX 解析 / 重采样 / 闭环判定 / 关键点文本解析。
 * 纯 Kotlin 无 Android 依赖，可直接 JVM 单测。全部 WGS-84。
 */
object RouteSources {

    data class GpxResult(val trackName: String?, val points: List<LatLng>)

    private val POINT_TAG = Regex("""<(?:trkpt|rtept)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("""\blat\s*=\s*["']([-\d.]+)["']""")
    private val LON_ATTR = Regex("""\blon\s*=\s*["']([-\d.]+)["']""")
    private val NAME_TAG = Regex("""<name>\s*([^<]{1,128}?)\s*</name>""", RegexOption.IGNORE_CASE)

    fun parseGpx(xml: String): GpxResult {
        val points = POINT_TAG.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val lat = LAT_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = LON_ATTR.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null
        }.toList()
        val name = NAME_TAG.find(xml)?.groupValues?.get(1)?.trim()
        return GpxResult(name, points)
    }

    /** 按约 spacingMeters 重采样折线（含首尾） */
    fun resample(points: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (points.size < 2) return points
        val cumulative = TrackMathUtils.cumulativeDistances(points)
        val total = cumulative.last()
        if (total <= 0.0) return points.take(1)
        val out = ArrayList<LatLng>((total / spacingMeters).toInt() + 2)
        var d = 0.0
        while (d < total) {
            out.add(TrackMathUtils.interpolate(points, cumulative, d))
            d += spacingMeters
        }
        out.add(points.last())
        return out
    }

    /** 首尾距不超过 maxGapMeters 视为闭环并补闭合点 */
    fun closeLoopIfNeeded(points: List<LatLng>, maxGapMeters: Double = 40.0): Pair<List<LatLng>, Boolean> {
        if (points.size < 3) return points to false
        val gap = TrackMathUtils.distanceMeters(points.first(), points.last())
        return when {
            gap <= 0.5 -> points to true
            gap <= maxGapMeters -> (points + points.first()) to true
            else -> points to false
        }
    }

    /** Douglas-Peucker 抽稀（米制容差），用于手绘/漫游录制轨迹平滑 */
    fun simplifyRdp(points: List<LatLng>, epsilonMeters: Double = 1.5): List<LatLng> {
        if (points.size < 3) return points
        val lat0 = points.first().lat
        val lng0 = points.first().lng
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        val my = 110574.0
        val local = points.map {
            (it.lng - lng0) * mx to (it.lat - lat0) * my
        }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val (ax, ay) = local[a]
            val (bx, by) = local[b]
            val segLen = hypot(bx - ax, by - ay)
            var maxDist = 0.0
            var index = -1
            for (i in a + 1 until b) {
                val (px, py) = local[i]
                val d = if (segLen < 1e-9) {
                    hypot(px - ax, py - ay)
                } else {
                    abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / segLen
                }
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > epsilonMeters && index > 0) {
                keep[index] = true
                stack.addLast(a to index)
                stack.addLast(index to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** 经纬度多边形有向面积判断：true = 顺时针 */
    fun isClockwise(points: List<LatLng>): Boolean {
        if (points.size < 3) return true
        val lat0 = points.first().lat
        val mx = 111320.0 * cos(Math.toRadians(lat0))
        var area = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val ax = a.lng * mx
            val ay = a.lat * 110574.0
            val bx = b.lng * mx
            val by = b.lat * 110574.0
            area += ax * by - bx * ay
        }
        return area < 0
    }

    /**
     * 关键点文本：lat,lng 数值流（逗号/空格/分号分隔均可，支持多行与 # 注释）。
     * 按出现顺序两两成对解析，越界或非数值的对直接跳过。
     */
    fun parsePointsText(text: String): List<LatLng> {
        val tokens = text.lineSequence()
            .map { it.substringBefore('#') }
            .flatMap { it.split(Regex("[,\\s;]+")).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val points = ArrayList<LatLng>(tokens.size / 2)
        var index = 0
        while (index + 1 < tokens.size) {
            val lat = tokens[index].toDoubleOrNull()
            val lon = tokens[index + 1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(LatLng(lat, lon))
            }
            index += 2
        }
        return points
    }

    /** 导出标准 GPX 1.1（WGS-84）备份文本 */
    fun toGpx(name: String, points: List<LatLng>): String {
        val escaped = name
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"${com.zcshou.gogogo.run.BrandConfig.SHARE_SCHEME}\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk><name>").append(escaped).append("</name><trkseg>\n")
            points.forEach {
                append(
                    String.format(
                        java.util.Locale.US,
                        "    <trkpt lat=\"%.7f\" lon=\"%.7f\"/>\n",
                        it.lat, it.lng
                    )
                )
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }
}