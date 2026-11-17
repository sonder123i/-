package com.zcshou.gogogo.run.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标变换工具：WGS-84（存储/计算唯一事实来源）↔ GCJ-02 ↔ BD-09（仅百度地图渲染/拾取边界）。
 */
object CoordTransformUtil {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val PI = Math.PI

    data class Point(val lat: Double, val lng: Double)

    fun wgs84ToBd09(lat: Double, lng: Double): Point {
        val gcj = wgs84ToGcj02(lat, lng)
        return gcj02ToBd09(gcj.lat, gcj.lng)
    }

    fun bd09ToWgs84(lat: Double, lng: Double): Point {
        val gcj = bd09ToGcj02(lat, lng)
        return gcj02ToWgs84(gcj.lat, gcj.lng)
    }

    fun wgs84ToGcj02(lat: Double, lng: Double): Point {
        if (outOfChina(lat, lng)) return Point(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return Point(lat + dLat, lng + dLng)
    }

    /** GCJ-02 → WGS-84（迭代反解，误差 < 0.5m） */
    fun gcj02ToWgs84(lat: Double, lng: Double): Point {
        if (outOfChina(lat, lng)) return Point(lat, lng)
        var wgsLat = lat
        var wgsLng = lng
        repeat(3) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat += lat - gcj.lat
            wgsLng += lng - gcj.lng
        }
        return Point(wgsLat, wgsLng)
    }

    fun gcj02ToBd09(lat: Double, lng: Double): Point {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * PI * 3000.0 / 180.0)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * PI * 3000.0 / 180.0)
        return Point(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    fun bd09ToGcj02(lat: Double, lng: Double): Point {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = atan2(y, x) - 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return Point(z * sin(theta), z * cos(theta))
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}