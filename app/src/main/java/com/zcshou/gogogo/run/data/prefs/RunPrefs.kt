package com.zcshou.gogogo.run.data.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 首页/漫游通用偏好：目标跑步 App、平滑走过去、常去地标。 */
object RunPrefs {

    private const val NAME = "pf_run_prefs"
    private const val KEY_TARGET_APP = "target_run_app"
    private const val KEY_SMOOTH_WALK = "smooth_walk"
    private const val KEY_FAVORITES = "roam_favorites"
    private const val MAX_FAVORITES = 5

    data class Favorite(val name: String, val lat: Double, val lng: Double)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun targetApp(context: Context): String? =
        prefs(context).getString(KEY_TARGET_APP, null)?.takeIf { it.isNotBlank() }

    fun setTargetApp(context: Context, pkg: String?) {
        prefs(context).edit().apply {
            if (pkg.isNullOrBlank()) remove(KEY_TARGET_APP) else putString(KEY_TARGET_APP, pkg)
        }.apply()
    }

    fun isSmoothWalkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMOOTH_WALK, true)

    fun setSmoothWalkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMOOTH_WALK, enabled).apply()
    }

    fun favorites(context: Context): List<Favorite> = runCatching {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lng = obj.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null else Favorite(name, lat, lng)
        }
    }.getOrDefault(emptyList())

    fun addFavorite(context: Context, favorite: Favorite) {
        val list = favorites(context).toMutableList()
        list.removeAll { it.name == favorite.name }
        list.add(0, favorite)
        while (list.size > MAX_FAVORITES) list.removeAt(list.lastIndex)
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng))
        }
        prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    fun removeFavorite(context: Context, name: String) {
        val list = favorites(context).filterNot { it.name == name }
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng))
        }
        prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply()
    }
}
