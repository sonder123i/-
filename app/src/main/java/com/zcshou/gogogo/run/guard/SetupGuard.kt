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
        /**
         * 开跑所需的核心能力。
         *
         * 悬浮窗不参与就绪判定：侧载安装常被系统「受限设置」冻结悬浮窗，
         * 如果把它设成硬门槛，用户不解除限制就完全用不了 App。现在悬浮窗
         * 只是增强项 —— 不开也能开跑，暂停/收尾/继续都在通知栏完成。
         */
        val coreReady: Boolean get() = devOptions && mockApp
        val ready: Boolean get() = coreReady
        val overlayOptional: Boolean get() = !overlay
        val missing: List<Step>
            get() = buildList {
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

    /**
     * 「关于手机」页内的找法说明。
     *
     * 与 [developerHint] 分开：那个是**动作口诀**（点哪个词 7 次），这个是
     * **定位说明**（那个词在页面什么位置、叫什么）。新手最常卡在第二步 ——
     * 跳到「关于本机」首屏只有设备名称 / 手机号码 / 机主，版本号在**下面一屏**，
     * 不往下滑就会以为走错了页面。
     *
     * ## 为什么列出多种叫法
     *
     * 这一项在各家 ROM 上的译名不统一：原生中文叫「版本号」，小米叫
     * 「MIUI 版本 / OS 版本」，三星叫「编译编号」，AOSP 英文残留会显示成
     * 「Build 号」。只写一个词，用户在别的机器上滚到底也找不到。
     * 列全几种叫法，比猜品牌可靠。
     */
    fun locateVersionHint(): String =
        "往下滑到页面最底部，找到「版本号」" +
            "（也可能叫「Build 号」「编译编号」「内部版本号」「OS 版本」）"

    /**
     * 点版本号之后的预期反馈。
     *
     * 主要解决「不知道成功长什么样」：点了几下没反应，用户会以为走错了页面。
     * 所以把成功时的倒数提示写出来，让他能自己确认。
     */
    fun tapVersionFeedbackHint(): String =
        "连续点 7 次\n" +
            "点对了屏幕底部会倒数「再点 N 次即可进入开发者模式」"

    fun deviceName(): String {
        val brand = Build.MANUFACTURER ?: ""
        return if (brand.isBlank()) "当前手机" else brand
    }

    /**
     * 是否从「非应用商店」安装。
     *
     * Android 13 与部分国产 ROM 会对侧载应用启用「受限设置」：悬浮窗、无障碍、
     * 通知使用权等敏感能力会先被冻结，必须由用户在「应用信息 → 右上角 ⋮」里
     * 手动解除。系统没有提供查询该状态或一键解除的公开 API，只能结合安装来源
     * 与「去了设置页回来还是没开」来推断，并把用户带到正确的页面。
     */
    fun isSideloaded(context: Context): Boolean {
        val installer = try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
        // 读不到来源时按侧载处理：宁可多给一次说明，也别让用户卡在系统弹窗上
        if (installer.isNullOrBlank()) return true
        return installer !in TRUSTED_INSTALLERS
    }

    /** 各品牌「解除设置限制」菜单路径（文案随 ROM 变化，菜单项名称并不统一） */
    fun restrictionMenuHint(context: Context): String {
        val app = context.getString(com.zcshou.gogogo.R.string.app_name)
        val brand = ((Build.MANUFACTURER ?: "") + " " + (Build.BRAND ?: "")).lowercase()
        return when {
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ||
                brand.contains("heytap") ->
                "设置 → 应用 → 应用管理 → $app → 右上角「⋮ / 更多」→「解除设置限制」"

            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "设置 → 应用设置 → 应用管理 → $app → 右上角「⋮」→「解除限制」"

            brand.contains("huawei") || brand.contains("honor") ->
                "设置 → 应用和服务 → 应用管理 → $app → 右上角「⋮」→「解除限制」"

            brand.contains("vivo") || brand.contains("iqoo") ->
                "设置 → 应用与权限 → 应用管理 → $app → 右上角「⋮」→「解除限制」"

            brand.contains("samsung") ->
                "设置 → 应用程序 → $app → 右上角「⋮」→「允许受限制的设置」"

            else ->
                "设置 → 应用 → $app → 右上角「⋮」→「允许受限制的设置 / 解除限制」"
        }
    }

    private val TRUSTED_INSTALLERS = setOf(
        // 应用商店
        "com.android.vending",
        "com.huawei.appmarket",
        "com.xiaomi.market",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.tencent.android.qqdownloader",
        "com.sec.android.app.samsungapps",
        // ADB / 系统 shell 安装不会触发侧载限制
        "com.android.shell"
    )
}
