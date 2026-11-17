package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.engine.TrackMathUtils.LatLng
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轨迹 JSON 编解码：引擎与 UI 共用 [{"lat":..,"lng":..}] 契约。
 * 抽成对象便于 JVM 单测替换实现（android.jar 的 org.json 在单测中是空壳）。
 */
object TrackJsonCodec {

    fun decode(json: String): List<LatLng> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lng = obj.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null else LatLng(lat, lng)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun encode(points: List<LatLng>): String = JSONArray().apply {
        points.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng)) }
    }.toString()
}