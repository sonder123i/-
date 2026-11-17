package com.zcshou.gogogo.run.guard

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 可选 Shizuku 联动：有权限时用 shell 静默开关 Wi-Fi；未安装/未授权/调用失败时一律安全降级返回 false。
 * 注：Shizuku 13.x 的 newProcess 标记为内部 API，这里反射调用，保证编译与降级路径可用。
 */
object ShizukuWifiHelper {

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        runCatching {
            if (isAvailable()) Shizuku.requestPermission(requestCode)
        }
    }

    /** 关闭/开启 Wi-Fi 后台扫描（wifi_scan_always_enabled），需 Shizuku 权限 */
    fun setWifiScanAlwaysEnabled(enabled: Boolean): Boolean = try {
        if (!hasPermission()) {
            false
        } else {
            val value = if (enabled) 1 else 0
            val process = newProcess(
                arrayOf("sh", "-c", "settings put global wifi_scan_always_enabled $value")
            )
            process != null && waitFor(process) == 0
        }
    } catch (e: Throwable) {
        false
    }

    /** 通过 Shizuku shell 执行 svc wifi，返回是否成功 */
    fun setWifiEnabled(enabled: Boolean): Boolean = try {
        if (!hasPermission()) {
            false
        } else {
            val process = newProcess(arrayOf("sh", "-c", if (enabled) "svc wifi enable" else "svc wifi disable"))
            process != null && waitFor(process) == 0
        }
    } catch (e: Throwable) {
        false
    }

    private fun newProcess(cmd: Array<String>): Any? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null)
    } catch (e: Throwable) {
        null
    }

    private fun waitFor(process: Any): Int = try {
        val method = process.javaClass.getMethod("waitFor")
        method.invoke(process) as Int
    } catch (e: Throwable) {
        -1
    }
}