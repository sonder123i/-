package com.zcshou.gogogo.mascot

import android.content.Context

/**
 * 伴侣模式存储。默认 OFF，尊重只想用极简功能的用户。
 *
 * 沿用 run 模块 `DisplayPrefs` 的 enum-as-name 约定，避免模式顺序变化后读错。
 */
object MascotPrefs {

    private const val NAME = "pf_mascot_prefs"
    private const val KEY_MODE = "mascot_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun mode(context: Context): MascotMode =
        MascotMode.fromName(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: MascotMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isEnabled(context: Context): Boolean = mode(context) != MascotMode.OFF

    fun isFull(context: Context): Boolean = mode(context) == MascotMode.FULL
}