package com.zcshou.gogogo.run.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassFeedback

/**
 * GitHub Star 邀请。
 *
 * 旧规则只看「拒绝过几次」：开一次 App 问一次，连问三次；用户连续冷启动时会被
 * 连着弹三次，这不是邀请，是骚扰。现在的规则改成「先攒使用、再隔时间问」：
 * - 至少打开过 [MIN_OPENS] 次、首次打开满 [MIN_DAYS_SINCE_FIRST_OPEN] 天才有资格；
 * - 两次邀请之间至少隔 [COOLDOWN_DAYS] 天，总共最多问 [MAX_PROMPTS] 次，问完永久静默；
 * - 点过 Star、「暂不」到上限、「不再提示」都永久静默；
 * - 展示即计数（旧版点「暂不」才计数），进程被杀也不会下次重弹。
 * 关于页保留常驻入口，不弹窗也能主动去点。
 */
object StarPrompt {

    const val REPO_URL = "https://github.com/sonder123i/CloudTrace"

    private const val PREFS = "pf_star"
    private const val KEY_STARRED = "has_starred"
    private const val KEY_OPTED_OUT = "opted_out"
    /** 沿用旧键名，含义从「拒绝次数」变为「已展示次数」；老用户只会更早静默。 */
    private const val KEY_PROMPT_COUNT = "dismiss_count"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"
    private const val KEY_FIRST_OPEN_AT = "first_open_at"
    private const val KEY_OPEN_COUNT = "open_count"

    /** 总共最多问两次：第一次了解，第二次确认，之后再也不问。 */
    private const val MAX_PROMPTS = 2

    /** 两次邀请至少隔两周，避免同一批冷启动里反复出现。 */
    private const val COOLDOWN_DAYS = 14L

    /** 装好第一天不开口：至少真正打开过 4 次，且首次打开满 3 天。 */
    private const val MIN_OPENS = 4
    private const val MIN_DAYS_SINCE_FIRST_OPEN = 3L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun days(value: Long): Long = value * 24L * 60L * 60L * 1000L

    /** 每次真正进入首页记一次打开，用来判断用户是不是常客。 */
    fun noteLaunch(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        if (prefs.getLong(KEY_FIRST_OPEN_AT, 0L) <= 0L) {
            editor.putLong(KEY_FIRST_OPEN_AT, now)
        }
        editor.putInt(KEY_OPEN_COUNT, prefs.getInt(KEY_OPEN_COUNT, 0) + 1)
        editor.apply()
    }

    /** 是否还有必要邀请：没点过 Star / 没退出、次数未达上限、用得够久且过了冷却期。 */
    fun isInvitePending(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_STARRED, false)) return false
        if (prefs.getBoolean(KEY_OPTED_OUT, false)) return false
        if (prefs.getInt(KEY_PROMPT_COUNT, 0) >= MAX_PROMPTS) return false
        if (prefs.getInt(KEY_OPEN_COUNT, 0) < MIN_OPENS) return false
        val now = System.currentTimeMillis()
        val firstOpen = prefs.getLong(KEY_FIRST_OPEN_AT, 0L)
        if (firstOpen <= 0L || now - firstOpen < days(MIN_DAYS_SINCE_FIRST_OPEN)) return false
        val lastPrompt = prefs.getLong(KEY_LAST_PROMPT_AT, 0L)
        if (lastPrompt > 0L && now - lastPrompt < days(COOLDOWN_DAYS)) return false
        return true
    }

    fun show(activity: Activity, onDismiss: () -> Unit = {}): AlertDialog {
        val shown = prefs(activity).getInt(KEY_PROMPT_COUNT, 0)
        val isLast = shown >= MAX_PROMPTS - 1
        // 先计数再展示：即使用户直接杀进程，这次也算「问过了」。
        markShown(activity)
        val dialog = GlassDialogs.builder(activity)
            .setTitle(
                if (isLast) R.string.star_prompt_title_last else R.string.star_prompt_title_first
            )
            .setMessage(
                if (isLast) R.string.star_prompt_body_last else R.string.star_prompt_body_first
            )
            .setPositiveButton(R.string.star_prompt_go) { _, _ ->
                // 浏览器没拉起来时不记「已 Star」，否则用户再也没机会被提醒。
                if (openRepo(activity)) {
                    prefs(activity).edit().putBoolean(KEY_STARRED, true).apply()
                }
            }
            .setNegativeButton(R.string.star_prompt_later, null)
            .setNeutralButton(R.string.star_prompt_never) { _, _ ->
                prefs(activity).edit().putBoolean(KEY_OPTED_OUT, true).apply()
            }
            .create()
        // Material 按钮默认把中文里的英文单词转成全大写（「去点 Star」→「去点 STAR」），
        // 和 App 其他按钮不一致；这里恢复原始大小写。
        dialog.setOnShowListener {
            for (button in intArrayOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
            )) {
                dialog.getButton(button)?.transformationMethod = null
            }
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return dialog
    }

    /** 记录一次展示，用于上限与冷却判断。 */
    private fun markShown(context: Context) {
        val prefs = prefs(context)
        prefs.edit()
            .putInt(KEY_PROMPT_COUNT, prefs.getInt(KEY_PROMPT_COUNT, 0) + 1)
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .apply()
    }

    /** 打开 GitHub 仓库页；返回是否成功拉起浏览器，失败时给出可读提示而不是崩溃。 */
    fun openRepo(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.onFailure {
            GlassFeedback.toast(context, context.getString(R.string.star_prompt_no_browser))
        }.isSuccess
    }
}