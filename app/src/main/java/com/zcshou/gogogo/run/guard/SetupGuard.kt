package com.zcshou.gogogo.run.guard

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 新手引导三项基础能力检测：悬浮窗 / 开发者选项 / 模拟位置应用。
 */
object SetupGuard {

    private const val PREFS = "pf_setup"
    private const val KEY_MANUAL_DEV = "dev_options_manual"
    private const val KEY_DEV_INFO_VISITED = "dev_info_visited"

    enum class Step { OVERLAY, DEV_OPTIONS, MOCK_APP }

    data class State(
        val overlay: Boolean,
        val devOptions: Boolean,
        val mockApp: Boolean
    ) {
        val ready: Boolean get() = overlay && devOptions && mockApp
        val missing: List<Step>
            get() = buildList {
                if (!overlay) add(Step.OVERLAY)
                if (!devOptions) add(Step.DEV_OPTIONS)
                if (!mockApp) add(Step.MOCK_APP)
            }
        val firstMissing: Step? get() = missing.firstOrNull()
    }

    fun check(context: Context): State {
        val overlay = EnvGuardManager.isOverlayAllowed(context)
        val mockApp = EnvGuardManager.isMockLocationAllowed(context)
        // 能选中模拟位置应用，说明开发者选项必然已开启；
        // 部分机型读不到 DEVELOPMENT_SETTINGS_ENABLED，用该事实兜底
        val devOptions = mockApp || isDeveloperOptionsEnabled(context) || isManualDevConfirmed(context)
        return State(overlay = overlay, devOptions = devOptions, mockApp = mockApp)
    }

    fun isReady(context: Context): Boolean = check(context).ready

    fun firstMissing(context: Context): Step? = check(context).firstMissing

    /** 用户在向导里点过「我已开启开发者选项」后的手动确认，仅用于读不到系统开关值的机型 */
    fun isManualDevConfirmed(context: Context): Boolean = prefs(context).getBoolean(KEY_MANUAL_DEV, false)

    fun confirmManualDevOptions(context: Context) {
        prefs(context).edit().putBoolean(KEY_MANUAL_DEV, true).apply()
    }

    fun hasVisitedDeviceInfo(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEV_INFO_VISITED, false)

    fun markDeviceInfoVisited(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEV_INFO_VISITED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeveloperOptionsEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    } catch (e: Exception) {
        false
    }

    /** 各品牌开发者模式的 2 步白话口诀 */
    fun developerHint(): String {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        val model = (Build.BRAND ?: "").lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || model.contains("redmi") ->
                "进入「全部参数与信息」→ 连续点击「OS 版本 / MIUI 版本」7 次"

            brand.contains("huawei") || brand.contains("honor") ->
                "连续点击「版本号」7 次，提示已进入开发者模式即可"

            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "进入「关于本机 → 版本信息」→ 连续点击「版本号」7 次"

            brand.contains("vivo") || brand.contains("iqoo") ->
                "进入「软件版本号」→ 连续点击 7 次"

            brand.contains("samsung") ->
                "进入「软件信息」→ 连续点击「编译编号」7 次"

            else -> "进入「关于手机」→ 连续点击「版本号」7 次"
        }
    }

    fun deviceName(): String {
        val brand = Build.MANUFACTURER ?: ""
        return if (brand.isBlank()) "当前手机" else brand
    }
}
