package com.zcshou.gogogo.run.update

import android.content.Context

/**
 * 更新偏好：只保存“是否在启动时自动检查”。
 *
 * 默认开启（便于及时收到修复），但用户可在“关于”页关闭，
 * 关闭后仅保留手动检查入口，不再产生任何后台请求。
 */
object UpdatePrefs {

    private const val NAME = "pf_update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoCheck(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheck(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }
}
