package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTemplatesTest {

    private val lat0 = 39.9087
    private val lng0 = 116.3975

    private fun lapOf(id: String, scale: Double = 1.0, rotation: Double = 0.0): Double {
        val pts = TrackTemplates.transform(
            TrackTemplates.template(id).localPoints, lat0, lng0, scale, rotation
        )
        return TrackMathUtils.cumulativeDistances(pts).last()
    }

    @Test
    fun `400m track lap is close to 400m`() {
        val lap = lapOf("400")
        assertTrue("lap=$lap", lap in 392.0..404.0)
    }

    @Test
    fun `300m and 200m tracks scale accordingly`() {
        assertTrue(lapOf("300") in 292.0..308.0)
        assertTrue(lapOf("200") in 193.0..207.0)
    }

    @Test
    fun `scale multiplies lap length`() {
        val base = lapOf("400", 1.0)
        val doubled = lapOf("400", 2.0)
        assertEquals(base * 2, doubled, 1.5)
    }

    @Test
    fun `rotation preserves lap length`() {
        val base = lapOf("400", 1.3, 0.0)
        val rotated = lapOf("400", 1.3, 47.0)
        assertEquals(base, rotated, 2.0)
    }

    @Test
    fun `figure eight is a closed loop with sane length`() {
        val pts = TrackTemplates.transform(
            TrackTemplates.template("eight").localPoints, lat0, lng0, 1.0, 0.0
        )
        val gap = TrackMathUtils.distanceMeters(pts.first(), pts.last())
        assertTrue("gap=$gap", gap < 1.0)
        assertTrue(TrackMathUtils.cumulativeDistances(pts).last() > 150.0)
    }
}
