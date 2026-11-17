package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class KineticMathTest {

    private val center = TrackMathUtils.LatLng(36.667662, 117.027707)

    @Test
    fun curvatureAdaptive_straightKeepsDesired() {
        val v = TrackMathUtils.curvatureAdaptiveLateral(3.5, 5.0)
        assertEquals(3.5, v, 1e-9)
    }

    @Test
    fun curvatureAdaptive_sharpTurnCollapsesToMin() {
        val v = TrackMathUtils.curvatureAdaptiveLateral(3.5, 60.0)
        assertEquals(0.8, v, 1e-9)
        val neg = TrackMathUtils.curvatureAdaptiveLateral(-3.5, 60.0)
        assertEquals(-0.8, neg, 1e-9)
    }

    @Test
    fun curvatureAdaptive_midRangeInterpolates() {
        val v = TrackMathUtils.curvatureAdaptiveLateral(3.5, 22.5)
        assertTrue("v=$v", v in 0.8..3.5)
        assertTrue("v=$v", v < 3.5)
    }

    @Test
    fun curvatureAngle_ovalCurveIsLargerThanStraight() {
        val dense = TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        val cum = TrackMathUtils.cumulativeDistances(dense)
        val total = cum.last()
        // 东侧直道中点附近 vs 弯道顶点附近（沿轨迹扫描取最大/最小）
        var maxAngle = 0.0
        var minAngle = 360.0
        var d = 0.0
        while (d < total) {
            val angle = TrackMathUtils.curvatureAngleAt(dense, cum, d, 8.0)
            maxAngle = maxOf(maxAngle, angle)
            minAngle = minOf(minAngle, angle)
            d += 10.0
        }
        assertTrue("max=$maxAngle min=$minAngle", maxAngle > 25.0)
        assertTrue("min=$minAngle", minAngle < 8.0)
    }

    @Test
    fun smooth3_reducesJaggedness() {
        val jagged = listOf(
            TrackMathUtils.LatLng(36.0, 117.0),
            TrackMathUtils.LatLng(36.00002, 117.0),
            TrackMathUtils.LatLng(36.0, 117.0),
            TrackMathUtils.LatLng(36.00002, 117.0)
        )
        val smoothed = TrackMathUtils.smooth3(jagged)
        assertEquals(jagged.size, smoothed.size)
        assertEquals(jagged.first(), smoothed.first())
        assertEquals(jagged.last(), smoothed.last())
        // 原锯齿幅度 2e-5；平滑后中间点应被拉向均值，幅度显著减小
        val originalAmp = 2e-5
        val smoothedAmp = abs(smoothed[1].lat - 36.0)
        assertTrue("smoothed=${smoothed[1].lat}", smoothedAmp in 1e-9..originalAmp * 0.6)
    }

    @Test
    fun microTerrain_isSpatiallyBoundAndBounded() {
        val r1 = Random(1)
        val r2 = Random(2)
        val a1 = TrackMathUtils.microTerrainAltitude(55.0, 0.25, random = r1)
        val a2 = TrackMathUtils.microTerrainAltitude(55.0, 0.25, random = r2)
        // 同一进度、不同随机源：地形分量一致，差异只在 ±0.15 噪声内
        assertTrue("diff=${abs(a1 - a2)}", abs(a1 - a2) <= 0.31)
        // 范围：55 ± (1.2 + 0.15)
        repeat(50) {
            val alt = TrackMathUtils.microTerrainAltitude(55.0, Random.nextDouble(), random = r1)
            assertTrue("alt=$alt", alt in 53.6..56.4)
        }
        // 进度 0.25 处应比 0.75 处高（sin 单调）
        val hi = TrackMathUtils.microTerrainAltitude(55.0, 0.25, random = Random(7))
        val lo = TrackMathUtils.microTerrainAltitude(55.0, 0.75, random = Random(7))
        assertTrue("hi=$hi lo=$lo", hi > lo)
    }

    @Test
    fun reversal_changesBearingDirection() {
        val dense = TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        val reversed = dense.reversed()
        val cumF = TrackMathUtils.cumulativeDistances(dense)
        val cumR = TrackMathUtils.cumulativeDistances(reversed)
        val bF = TrackMathUtils.bearingAt(dense, cumF, 10.0)
        val bR = TrackMathUtils.bearingAt(reversed, cumR, 10.0)
        val delta = TrackMathUtils.headingDelta(bF, bR)
        assertTrue("forward=$bF reversed=$bR delta=$delta", delta > 90.0)
    }
}