package com.zcshou.gogogo.run.engine

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 球面几何工具：距离、航向、法线偏移、闭合向心 Catmull-Rom 样条。
 * 纯 Kotlin 无 Android 依赖，可直接 JVM 单测。
 */
object TrackMathUtils {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val METERS_PER_DEG_LAT = 110574.0
    private const val METERS_PER_DEG_LNG_EQUATOR = 111320.0

    data class LatLng(val lat: Double, val lng: Double)

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 航向角：正北 0°，顺时针 0~360 */
    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun destinationPoint(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lng1 = Math.toRadians(from.lng)
        val ang = distanceM / EARTH_RADIUS_M
        val lat2 = kotlin.math.asin(sin(lat1) * cos(ang) + cos(lat1) * sin(ang) * cos(brg))
        val lng2 = lng1 + atan2(
            sin(brg) * sin(ang) * cos(lat1),
            cos(ang) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), normalizeLng(Math.toDegrees(lng2)))
    }

    /**
     * 沿切线方向的垂直法线平移：lateralMeters 正为右侧，负为左侧。
     */
    fun offsetPoint(point: LatLng, tangentBearingDeg: Double, lateralMeters: Double): LatLng =
        destinationPoint(point, (tangentBearingDeg + 90.0) % 360.0, lateralMeters)

    /**
     * 闭合向心 Catmull-Rom 样条（alpha = 0.5），输入 4~8 个锚点，
     * 输出每隔约 spacingMeters 一个点的平滑闭环 WGS-84 轨迹。
     */
    fun catmullRomClosed(anchors: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (anchors.size < 3) return anchors.toList()
        val lat0 = anchors.map { it.lat }.average()
        val lng0 = anchors.map { it.lng }.average()
        val local = anchors.map { toLocal(it, lat0, lng0) }
        val n = local.size
        val out = ArrayList<LatLng>()
        for (i in 0 until n) {
            val p0 = local[(i - 1 + n) % n]
            val p1 = local[i]
            val p2 = local[(i + 1) % n]
            val p3 = local[(i + 2) % n]
            val segLen = hypot(p2.first - p1.first, p2.second - p1.second)
            if (segLen < 1e-6) continue
            val steps = max(2, ceil(segLen / spacingMeters).toInt())
            val t0 = 0.0
            val t1 = t0 + max(hypot(p1.first - p0.first, p1.second - p0.second), 1e-6).pow(0.5)
            val t2 = t1 + max(segLen, 1e-6).pow(0.5)
            val t3 = t2 + max(hypot(p3.first - p2.first, p3.second - p2.second), 1e-6).pow(0.5)
            for (s in 0 until steps) {
                val t = t1 + (t2 - t1) * s / steps
                val pt = barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t)
                out.add(fromLocal(pt.first, pt.second, lat0, lng0))
            }
        }
        return out
    }

    /** 开放向心 Catmull-Rom 样条（首尾不闭合，端点用重复点夹紧） */
    fun catmullRomOpen(anchors: List<LatLng>, spacingMeters: Double = 2.0): List<LatLng> {
        if (anchors.size < 2) return anchors.toList()
        if (anchors.size == 2) {
            val a = anchors[0]
            val b = anchors[1]
            val total = distanceMeters(a, b)
            val steps = max(2, ceil(total / spacingMeters).toInt())
            return (0..steps).map { i ->
                val f = i.toDouble() / steps
                LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
        }
        val lat0 = anchors.map { it.lat }.average()
        val lng0 = anchors.map { it.lng }.average()
        val local = anchors.map { toLocal(it, lat0, lng0) }
        val n = local.size
        val out = ArrayList<LatLng>()
        for (i in 0 until n - 1) {
            val p0 = local[(i - 1).coerceAtLeast(0)]
            val p1 = local[i]
            val p2 = local[i + 1]
            val p3 = local[(i + 2).coerceAtMost(n - 1)]
            val segLen = hypot(p2.first - p1.first, p2.second - p1.second)
            if (segLen < 1e-6) continue
            val steps = max(2, ceil(segLen / spacingMeters).toInt())
            val t0 = 0.0
            val t1 = t0 + max(hypot(p1.first - p0.first, p1.second - p0.second), 1e-6).pow(0.5)
            val t2 = t1 + max(segLen, 1e-6).pow(0.5)
            val t3 = t2 + max(hypot(p3.first - p2.first, p3.second - p2.second), 1e-6).pow(0.5)
            for (s in 0 until steps) {
                val t = t1 + (t2 - t1) * s / steps
                val pt = barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t)
                out.add(fromLocal(pt.first, pt.second, lat0, lng0))
            }
        }
        out.add(anchors.last())
        return out
    }

    /** 折线累计里程（含起点 0） */
    fun cumulativeDistances(points: List<LatLng>): DoubleArray {        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + distanceMeters(points[i - 1], points[i])
        }
        return cum
    }

    /** 按沿线里程插值取点（dist 超界时按模取整圈，适配闭环） */
    fun interpolate(points: List<LatLng>, cumulative: DoubleArray, dist: Double): LatLng {
        require(points.isNotEmpty()) { "empty polyline" }
        if (points.size == 1) return points[0]
        val total = cumulative.last()
        val d = if (total > 0) ((dist % total) + total) % total else 0.0
        var lo = 0
        var hi = cumulative.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid
        }
        val segLen = cumulative[lo + 1] - cumulative[lo]
        if (segLen <= 1e-9) return points[lo]
        val f = (d - cumulative[lo]) / segLen
        val a = points[lo]
        val b = points[lo + 1]
        return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    /** 里程处的切线航向 */
    fun bearingAt(points: List<LatLng>, cumulative: DoubleArray, dist: Double): Double {
        if (points.size < 2) return 0.0
        val total = cumulative.last()
        val d = if (total > 0) ((dist % total) + total) % total else 0.0
        var lo = 0
        var hi = cumulative.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        return bearingDegrees(points[lo], points[lo + 1])
    }

    /** 两航向角之差（0~180） */
    fun headingDelta(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** 指定里程处的前后切线夹角（度），用于曲率近似 */
    fun curvatureAngleAt(
        points: List<LatLng>,
        cumulative: DoubleArray,
        dist: Double,
        lookaroundMeters: Double = 8.0
    ): Double {
        if (points.size < 3) return 0.0
        val before = bearingAt(points, cumulative, dist - lookaroundMeters)
        val after = bearingAt(points, cumulative, dist + lookaroundMeters)
        return headingDelta(before, after)
    }

    /** 曲率自适应横向偏移：急弯按比例衰减，最窄收到 minLateralMeters，防内侧自交打结 */
    fun curvatureAdaptiveLateral(
        desiredMeters: Double,
        curvatureAngleDeg: Double,
        fullLateralUntilDeg: Double = 15.0,
        collapseFromDeg: Double = 30.0,
        minLateralMeters: Double = 0.8
    ): Double {
        if (curvatureAngleDeg <= fullLateralUntilDeg) return desiredMeters
        val sign = if (desiredMeters < 0) -1.0 else 1.0
        if (curvatureAngleDeg >= collapseFromDeg) return sign * minLateralMeters
        val t = (curvatureAngleDeg - fullLateralUntilDeg) / (collapseFromDeg - fullLateralUntilDeg)
        return sign * (kotlin.math.abs(desiredMeters) * (1 - t) + minLateralMeters * t)
    }

    /** 三点滑动平均平滑折线（首尾保持不动） */
    fun smooth3(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points
        val out = ArrayList<LatLng>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            out.add(LatLng((a.lat + b.lat + c.lat) / 3.0, (a.lng + b.lng + c.lng) / 3.0))
        }
        out.add(points.last())
        return out
    }

    /**
     * 空间位置绑定的微地形海拔：按路径归一化进度生成固定正弦起伏，
     * 同一物理位置每次经过高度一致，仅叠加微小噪点。
     */
    fun microTerrainAltitude(
        baseAltitudeM: Double,
        progressFraction: Double,
        amplitudeM: Double = 1.2,
        noiseM: Double = 0.15,
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): Double {
        val terrain = amplitudeM * sin(2 * Math.PI * progressFraction)
        val noise = (random.nextDouble() * 2 - 1) * noiseM
        return baseAltitudeM + terrain + noise
    }

    private fun normalizeLng(lng: Double): Double = when {
        lng > 180.0 -> lng - 360.0
        lng < -180.0 -> lng + 360.0
        else -> lng
    }

    private fun toLocal(p: LatLng, lat0: Double, lng0: Double): Pair<Double, Double> {
        val east = (p.lng - lng0) * METERS_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat0))
        val north = (p.lat - lat0) * METERS_PER_DEG_LAT
        return east to north
    }

    private fun fromLocal(east: Double, north: Double, lat0: Double, lng0: Double): LatLng {
        val lat = lat0 + north / METERS_PER_DEG_LAT
        val lng = lng0 + east / (METERS_PER_DEG_LNG_EQUATOR * cos(Math.toRadians(lat0)))
        return LatLng(lat, lng)
    }

    private fun barryGoldman(
        p0: Pair<Double, Double>, p1: Pair<Double, Double>,
        p2: Pair<Double, Double>, p3: Pair<Double, Double>,
        t0: Double, t1: Double, t2: Double, t3: Double, t: Double
    ): Pair<Double, Double> {
        val a1 = lerp(p0, p1, (t1 - t) / (t1 - t0), (t - t0) / (t1 - t0))
        val a2 = lerp(p1, p2, (t2 - t) / (t2 - t1), (t - t1) / (t2 - t1))
        val a3 = lerp(p2, p3, (t3 - t) / (t3 - t2), (t - t2) / (t3 - t2))
        val b1 = lerp(a1, a2, (t2 - t) / (t2 - t0), (t - t0) / (t2 - t0))
        val b2 = lerp(a2, a3, (t3 - t) / (t3 - t1), (t - t1) / (t3 - t1))
        return lerp(b1, b2, (t2 - t) / (t2 - t1), (t - t1) / (t2 - t1))
    }

    private fun lerp(a: Pair<Double, Double>, b: Pair<Double, Double>, wa: Double, wb: Double): Pair<Double, Double> =
        (a.first * wa + b.first * wb) to (a.second * wa + b.second * wb)
}