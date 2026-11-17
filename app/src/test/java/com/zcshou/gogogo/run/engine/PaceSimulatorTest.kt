package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PaceSimulatorTest {

    @Test
    fun baseSpeed_fromPace() {
        val sim = PaceSimulator(340, Random(42))
        assertEquals(2.941, sim.baseSpeedMps, 0.001)
    }

    @Test
    fun warmup_slower_than_base() {
        val sim = PaceSimulator(340, Random(42))
        val v = sim.speedMps(100.0, 2900.0, 0.0, 0.5)
        assertTrue("warmup=$v", v < sim.baseSpeedMps)
    }

    @Test
    fun sprint_faster_than_base() {
        val sim = PaceSimulator(340, Random(42))
        val v = sim.speedMps(2900.0, 100.0, 0.0, 0.5)
        assertTrue("sprint=$v", v > sim.baseSpeedMps)
    }

    @Test
    fun sharpTurn_slowsBy20Percent() {
        val straight = PaceSimulator(340, Random(42)).speedMps(2000.0, 1000.0, 10.0, 0.5)
        val turn = PaceSimulator(340, Random(42)).speedMps(2000.0, 1000.0, 60.0, 0.5)
        assertEquals(straight * PaceSimulator.TURN_SLOWDOWN, turn, 1e-9)
    }

    @Test
    fun cruiseSpeedWithinJitterBounds() {
        val sim = PaceSimulator(340, Random(7))
        repeat(200) {
            val v = sim.speedMps(1500.0, 1500.0, 0.0, 0.5)
            assertTrue("v=$v", v in 2.5..3.4)
        }
    }
}