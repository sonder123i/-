package com.zcshou.gogogo.run.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import com.zcshou.gogogo.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 本地崩溃记录：不接第三方崩溃平台，只在下一次启动时提示一次，并把记录并入诊断包。
 *
 * 只在默认处理器之前写一份文件，随后原样交还给系统/原处理器，不吞掉崩溃、
 * 不阻止进程退出。
 */
object CrashJournal {

    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "pf_crash"
    private const val KEY_CRASH_AT = "crash_at"
    private const val KEY_PROMPTED_AT = "prompted_at"
    private const val MAX_AGE_DAYS = 7L
    private const val MAX_CHARS = 12_000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun maxAgeMs(): Long = MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

    /** 在 Application 启动时安装，链在已有处理器之前。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 写记录失败也绝不能影响原始崩溃流程。
            runCatching { record(app, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val text = buildString {
            appendLine("时间: ${formatTime(now)}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.BRAND}")
            appendLine("线程: ${thread.name}")
            appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("堆栈:")
            append(throwable.stackTraceToString())
        }.take(MAX_CHARS)
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(text, Charsets.UTF_8)
        }
        prefs(context).edit()
            .putLong(KEY_CRASH_AT, now)
            .remove(KEY_PROMPTED_AT)
            .commit()
    }

    fun lastCrashAt(context: Context): Long = prefs(context).getLong(KEY_CRASH_AT, 0L)

    /** 有新的、未提示过且还在 7 天内的崩溃记录。 */
    fun pending(context: Context): Boolean {
        val crashAt = lastCrashAt(context)
        if (crashAt <= 0L) return false
        val promptedAt = prefs(context).getLong(KEY_PROMPTED_AT, 0L)
        if (promptedAt >= crashAt) return false
        return System.currentTimeMillis() - crashAt <= maxAgeMs()
    }

    /** 已经提示过，避免每次启动都弹。 */
    fun markPrompted(context: Context) {
        prefs(context).edit()
            .putLong(KEY_PROMPTED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 读取最近一次崩溃记录；超过 7 天视为过期。 */
    fun read(context: Context): String? {
        val crashAt = lastCrashAt(context)
        if (crashAt <= 0L) return null
        if (System.currentTimeMillis() - crashAt > maxAgeMs()) return null
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        prefs(context).edit()
            .remove(KEY_CRASH_AT)
            .remove(KEY_PROMPTED_AT)
            .apply()
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
}