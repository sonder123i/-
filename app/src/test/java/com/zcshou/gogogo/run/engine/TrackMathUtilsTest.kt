package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class TrackMathUtilsTest {

    @Test
    fun haversine_oneDegreeLatitude() {
        val d = TrackMathUtils.distanceMeters(
            TrackMathUtils.LatLng(0.0, 0.0),
            TrackMathUtils.LatLng(1.0, 0.0)
        )
        assertEquals(111194.9, d, 5.0)
    }

    @Test
    fun bearing_cardinal() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        assertEquals(0.0, TrackMathUtils.bearingDegrees(origin, TrackMathUtils.LatLng(36.001, 117.0)), 0.01)
        assertEquals(90.0, TrackMathUtils.bearingDegrees(origin, TrackMathUtils.LatLng(36.0, 117.001)), 0.05)
    }

    @Test
    fun offsetPoint_perpendicular() {
        val p = TrackMathUtils.LatLng(36.667662, 117.027707)
        val shifted = TrackMathUtils.offsetPoint(p, 0.0, 10.0)
        assertEquals(10.0, TrackMathUtils.distanceMeters(p, shifted), 0.05)
        assertEquals(90.0, TrackMathUtils.bearingDegrees(p, shifted), 0.5)
    }

    @Test
    fun spline_oval_is_closed_dense_smooth() {
        val anchors = TrackFactory.ovalAnchors(TrackMathUtils.LatLng(36.667662, 117.027707))
        assertEquals(6, anchors.size)
        val dense = TrackMathUtils.catmullRomClosed(anchors, 2.0)
        assertTrue("密度过低: ${dense.size}", dense.size >= 150)
        val cum = TrackMathUtils.cumulativeDistances(dense)
        val total = cum.last()
        assertTrue("周长异常: $total", total in 380.0..430.0)
        val gap = TrackMathUtils.distanceMeters(dense.first(), dense.last())
        assertTrue("闭合缺口: $gap", gap <= 3.0)
        var maxDelta = 0.0
        for (i in 1 until dense.size - 1) {
            val a = TrackMathUtils.bearingDegrees(dense[i - 1], dense[i])
            val b = TrackMathUtils.bearingDegrees(dense[i], dense[i + 1])
            maxDelta = max(maxDelta, TrackMathUtils.headingDelta(a, b))
        }
        assertTrue("平滑度不足: $maxDelta", maxDelta < 35.0)
    }

    @Test
    fun interpolate_midpoint() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val pts = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 0.0, 10.0),
            TrackMathUtils.destinationPoint(origin, 0.0, 20.0)
        )
        val cum = TrackMathUtils.cumulativeDistances(pts)
        val mid = TrackMathUtils.interpolate(pts, cum, 5.0)
        assertEquals(5.0, TrackMathUtils.distanceMeters(pts[0], mid), 0.05)
    }

    @Test
    fun bearingAt_straightNorth() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val pts = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 0.0, 2.0),
            TrackMathUtils.destinationPoint(origin, 0.0, 4.0)
        )
        val cum = TrackMathUtils.cumulativeDistances(pts)
        assertEquals(0.0, TrackMathUtils.bearingAt(pts, cum, 3.0), 0.5)
    }

    @Test
    fun catmullRomOpen_keepsEndpointsWithoutClosingSegment() {
        val origin = TrackMathUtils.LatLng(36.0, 117.0)
        val anchors = listOf(
            origin,
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0),
            TrackMathUtils.destinationPoint(origin, 90.0, 100.0).let {
                TrackMathUtils.destinationPoint(it, 0.0, 100.0)
            }
        )
        val dense = TrackMathUtils.catmullRomOpen(anchors, 2.0)
        assertTrue("点密度过低: ${dense.size}", dense.size >= 50)
        assertEquals(anchors.first().lat, dense.first().lat, 1e-9)
        assertEquals(anchors.first().lng, dense.first().lng, 1e-9)
        assertEquals(anchors.last().lat, dense.last().lat, 1e-9)
        assertEquals(anchors.last().lng, dense.last().lng, 1e-9)
        val total = TrackMathUtils.cumulativeDistances(dense).last()
        assertTrue("开放样条不应闭合: $total", total in 190.0..210.0)
    }
}