package com.zcshou.gogogo.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShareCodecTest {

    private val points = listOf(
        TrackMathUtils.LatLng(36.667662, 117.027707),
        TrackMathUtils.LatLng(36.667900, 117.028100),
        TrackMathUtils.LatLng(36.667500, 117.028400),
        TrackMathUtils.LatLng(36.667662, 117.027707)
    )

    @Test
    fun roundTrip_keepsNameAndPoints() {
        val code = RouteShareCodec.encode("南区 & 操场", points, clockwise = true)
        assertTrue(code.startsWith("DEEPRUN://"))
        val decoded = RouteShareCodec.decode(code)
        assertNotNull(decoded)
        assertEquals("南区 & 操场", decoded!!.name)
        assertTrue(decoded.clockwise)
        assertEquals(points.size, decoded.points.size)
        assertEquals(points[0].lat, decoded.points[0].lat, 1e-6)
        assertEquals(points[1].lng, decoded.points[1].lng, 1e-6)
    }

    @Test
    fun decode_acceptsMissingPrefix() {
        val code = RouteShareCodec.encode("环湖", points, clockwise = false)
        val decoded = RouteShareCodec.decode(code.removePrefix("DEEPRUN://"))
        assertNotNull(decoded)
        assertEquals(false, decoded!!.clockwise)
    }

    @Test
    fun decode_rejectsBrokenPayload() {
        assertNull(RouteShareCodec.decode("DEEPRUN://not-a-valid-code"))
        assertNull(RouteShareCodec.decode(""))
    }
}
