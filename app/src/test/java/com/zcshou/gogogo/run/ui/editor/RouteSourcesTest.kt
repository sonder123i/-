package com.zcshou.gogogo.run.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSourcesTest {

    private val sampleGpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test">
          <trk>
            <name>南操场测试圈</name>
            <trkseg>
              <trkpt lat="36.667662" lon="117.027707"><ele>55</ele></trkpt>
              <trkpt lat="36.667762" lon="117.027807"/>
              <trkpt lat="36.667862" lon="117.027907"/>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun parseGpx_readsNameAndPoints() {
        val result = RouteSources.parseGpx(sampleGpx)
        assertEquals(3, result.points.size)
        assertEquals("南操场测试圈", result.trackName)
        assertEquals(36.667662, result.points[0].lat, 1e-9)
        assertEquals(117.027907, result.points[2].lng, 1e-9)
    }

    @Test
    fun resample_hitsSpacing() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val end = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, 20.0)
        val resampled = RouteSources.resample(listOf(start, end), 2.0)
        assertTrue("点数=${resampled.size}", resampled.size in 10..12)
        assertEquals(end.lat, resampled.last().lat, 1e-9)
        assertEquals(end.lng, resampled.last().lng, 1e-9)
    }

    @Test
    fun closeLoop_appendsStartWithinGap() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val second = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, 30.0)
        val third = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 0.0, 10.0)
        val (points, closed) = RouteSources.closeLoopIfNeeded(listOf(start, second, third))
        assertTrue(closed)
        assertEquals(points.first(), points.last())
    }

    @Test
    fun parsePointsText_handlesSeparatorsAndComments() {
        val text = """
            # 操场关键点
            36.6676,117.0277
            36.6678 117.0280
            36.6680;117.0275
            bad line
            95.0,117.0
        """.trimIndent()
        val points = RouteSources.parsePointsText(text)
        assertEquals(3, points.size)
        assertEquals(36.6678, points[1].lat, 1e-9)
    }

    @Test
    fun parsePointsText_singleLineSemicolonList() {
        val text = "36.6676,117.0277;36.6678,117.0280;36.6679,117.0275;36.6675,117.0273"
        val points = RouteSources.parsePointsText(text)
        assertEquals(4, points.size)
        assertEquals(117.0273, points[3].lng, 1e-9)
    }

    @Test
    fun simplifyRdp_collapsesStraightLine() {
        val start = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val line = (0..100).map { i ->
            com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(start, 90.0, i.toDouble())
        }
        val simplified = RouteSources.simplifyRdp(line, 1.5)
        assertEquals(2, simplified.size)
    }

    @Test
    fun simplifyRdp_keepsSignificantCorner() {
        val a = com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0, 117.0)
        val b = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(a, 90.0, 100.0)
        val c = com.zcshou.gogogo.run.engine.TrackMathUtils.destinationPoint(b, 0.0, 100.0)
        val simplified = RouteSources.simplifyRdp(listOf(a, b, c), 1.5)
        assertEquals(3, simplified.size)
    }

    @Test
    fun isClockwise_detectsDirection() {
        val cw = listOf(
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0000, 117.0000),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.0000, 117.0009),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(35.9992, 117.0009),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(35.9992, 117.0000)
        )
        assertTrue(RouteSources.isClockwise(cw))
        assertTrue(!RouteSources.isClockwise(cw.reversed()))
    }

    @Test
    fun toGpx_roundTripsPointsAndEscapesName() {
        val points = listOf(
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.1234567, 117.7654321),
            com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng(36.1235000, 117.7655000)
        )
        val gpx = RouteSources.toGpx("东区 & <田径场>", points)
        assertTrue(gpx.contains("&amp;"))
        assertTrue(gpx.contains("&lt;田径场&gt;"))
        val parsed = RouteSources.parseGpx(gpx)
        assertEquals(2, parsed.points.size)
        assertEquals(points[0].lat, parsed.points[0].lat, 1e-7)
        assertEquals(points[0].lng, parsed.points[0].lng, 1e-7)
        assertEquals(points[1].lat, parsed.points[1].lat, 1e-7)
    }
}