package com.zcshou.gogogo.run.guard

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * 免 Root 运行环境体检：模拟位置授权 / 悬浮窗 / 电池白名单 / Wi-Fi 连接 / 精确定时权限。
 */
object EnvGuardManager {

    fun isMockLocationAllowed(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun isOverlayAllowed(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (e: Exception) {
        false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** 当前活跃网络是否真的是 Wi-Fi（4G/5G 或断网一律返回 false） */
    fun isWifiActivelyConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (e: Exception) {
        false
    }

    /** 后台扫描（wifi_scan_always_enabled）：仅作高级选项里的参考信息，不再阻断开跑 */
    fun isWifiScanAlwaysAvailable(context: Context): Boolean = try {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            "wifi_scan_always_enabled",
            0
        ) == 1
    } catch (e: Exception) {
        false
    }

    fun isShizukuReady(): Boolean = ShizukuWifiHelper.isAvailable() && ShizukuWifiHelper.hasPermission()

    fun canScheduleExactAlarms(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }
    } catch (e: Exception) {
        false
    }
}