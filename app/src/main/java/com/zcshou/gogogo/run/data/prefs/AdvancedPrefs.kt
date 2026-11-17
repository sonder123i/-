package com.zcshou.gogogo.run.data.prefs

import android.content.Context

/** 体检页「高级选项」里的实验性开关，默认全部关闭。 */
object AdvancedPrefs {

    private const val NAME = "pf_advanced"
    private const val KEY_SHIZUKU_WIFI = "shizuku_auto_wifi"
    private const val KEY_INJECTION_INTERVAL = "injection_interval_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isShizukuAutoWifiEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHIZUKU_WIFI, false)

    fun setShizukuAutoWifiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHIZUKU_WIFI, enabled).apply()
    }

    /** 坐标注入心跳间隔：200ms 更顺滑，1000ms 更省电，默认 500ms */
    fun injectionIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INJECTION_INTERVAL, 500L).coerceIn(200L, 1000L)

    fun setInjectionIntervalMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_INJECTION_INTERVAL, value.coerceIn(200L, 1000L)).apply()
    }
}
