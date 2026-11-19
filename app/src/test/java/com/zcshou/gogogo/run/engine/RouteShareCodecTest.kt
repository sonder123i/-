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
        assertTrue(code.startsWith("CLOUDTRACE://"))
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
        val decoded = RouteShareCodec.decode(code.removePrefix("CLOUDTRACE://"))
        assertNotNull(decoded)
        assertEquals(false, decoded!!.clockwise)
    }

    @Test
    fun decode_acceptsCodeInsideChatMessage() {
        val code = RouteShareCodec.encode("夜跑环线", points, clockwise = true)
        val message = "我用云迹画了一条跑圈路线，复制整段导入：\n$code\n谢谢！"
        val decoded = RouteShareCodec.decode(message)
        assertNotNull(decoded)
        assertEquals("夜跑环线", decoded!!.name)
    }

    @Test
    fun decode_acceptsLegacyDeeprunPrefix() {
        val code = RouteShareCodec.encode("旧口令", points, clockwise = true)
            .replaceFirst("CLOUDTRACE://", "DEEPRUN://")
        val decoded = RouteShareCodec.decode("旧版口令：$code。")
        assertNotNull(decoded)
        assertEquals("旧口令", decoded!!.name)
    }

    @Test
    fun extractCode_stopsAtTrailingPunctuation() {
        val code = RouteShareCodec.encode("操场", points, clockwise = true)
        assertEquals(code, RouteShareCodec.extractCode("口令：$code。"))
    }

    @Test
    fun extractCode_returnsNullWithoutPrefix() {
        assertNull(RouteShareCodec.extractCode("hello"))
    }

    @Test
    fun decode_rejectsBrokenPayload() {
        assertNull(RouteShareCodec.decode("CLOUDTRACE://not-a-valid-code"))
        assertNull(RouteShareCodec.decode(""))
    }
}
