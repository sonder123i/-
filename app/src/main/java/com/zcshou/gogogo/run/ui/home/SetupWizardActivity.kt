package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.guard.SetupHintBanner
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * 3 步新手向导（独立页面，避免悬浮窗/对话框主题兼容问题）。
 * 返回 App 时自动刷新；三项就绪后 RESULT_OK 回到首页直接开跑。
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var overlayDot: View
    private lateinit var overlayStatus: TextView
    private lateinit var overlayAction: TextView
    private lateinit var devDot: View
    private lateinit var devStatus: TextView
    private lateinit var devAction: TextView
    private lateinit var devActionDirect: TextView
    private lateinit var mockDot: View
    private lateinit var mockStatus: TextView
    private lateinit var mockAction: TextView
    private lateinit var primary: TextView

    /** 刚从系统开发者选项页返回，用于判断开关到底有没有打开 */
    private var jumpedForDevOptions = false

    /** 刚从「关于手机」返回，用于自动接力跳到开发者选项 */
    private var jumpedForDeviceInfo = false

    /** 自动接力次数上限，避免系统页打不开时来回弹 */
    private var autoJumpCount = 0

    /** 刚从悬浮窗设置页返回：若仍未开启，多半被系统「受限设置」拦住了 */
    private var jumpedForOverlay = false

    /** 刚从应用信息页返回，用于二次确认是否已解除限制 */
    private var jumpedForAppInfo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)
        ThemeHelper.padForSystemBars(findViewById(R.id.setupRoot))
        findViewById<android.widget.TextView>(R.id.stepDevAppHint)?.text =
            getString(R.string.setup_mock_app_hint, getString(R.string.app_name))

        scroll = findViewById(R.id.setupWizardScroll)
        overlayDot = findViewById(R.id.stepOverlayDot)
        overlayStatus = findViewById(R.id.stepOverlayStatus)
        overlayAction = findViewById(R.id.stepOverlayAction)
        devDot = findViewById(R.id.stepDevDot)
        devStatus = findViewById(R.id.stepDevStatus)
        devAction = findViewById(R.id.stepDevAction)
        devActionDirect = findViewById(R.id.stepDevActionDirect)
        mockDot = findViewById(R.id.stepMockDot)
        mockStatus = findViewById(R.id.stepMockStatus)
        mockAction = findViewById(R.id.stepMockAction)
        primary = findViewById(R.id.btnWizardPrimary)
        // 用页内定位说明而不是品牌口诀：品牌名写死的「软件信息 / 编译编号」
        // 经常和实际设置页措辞对不上，用户照着找会找不到。
        findViewById<TextView>(R.id.stepDevHint).text =
            SetupGuard.locateVersionHint() + "\n" + SetupGuard.tapVersionFeedbackHint()

        findViewById<View>(R.id.btnWizardClose).setOnClickListener { finish() }
        overlayAction.setOnClickListener { openOverlaySettings() }
        devAction.setOnClickListener { openDeviceInfo() }
        devActionDirect.setOnClickListener { openDevSettings() }
        findViewById<TextView>(R.id.stepDevManual).setOnClickListener {
            SetupGuard.confirmManualDevOptions(this)
            toast("已记录，请继续选择模拟位置应用")
            refresh()
        }
        mockAction.setOnClickListener {
            if (SetupGuard.check(this).devOptions) openMockSettings() else openDeviceInfo()
        }
        findViewById<TextView>(R.id.btnCopyMockKeyword).setOnClickListener { copyMockKeyword() }
        primary.setOnClickListener {
            val state = SetupGuard.check(this)
            if (state.ready) {
                setResult(RESULT_OK)
                finish()
                return@setOnClickListener
            }
            when (state.firstMissing) {
                SetupGuard.Step.OVERLAY -> openOverlaySettings()
                SetupGuard.Step.DEV_OPTIONS -> {
                    // 第二次点主按钮时已去过「关于手机」，直接尝试打开开发者选项
                    if (SetupGuard.hasVisitedDeviceInfo(this)) openDevSettings() else openDeviceInfo()
                }
                SetupGuard.Step.MOCK_APP -> {
                    if (state.devOptions) openMockSettings() else openDeviceInfo()
                }
                null -> Unit
            }
        }
        findViewById<TextView>(R.id.btnSandbox).setOnClickListener {
            runCatching { startActivity(Intent(this, DemoPreviewActivity::class.java)) }
        }

    }

    override fun onResume() {
        super.onResume()
        // 注意：**不要**在这里无条件 hide 横幅。
        //
        // 用户从系统设置按返回键回到本页的那一刻，正是他最需要对照提示的时候
        // （「刚才那个页面要我点哪个词来着？」）。原先第一行就是 hide()，
        // 结果横幅在最有用的瞬间消失。现在只在真正完成当前步骤后才收（见 refresh）。
        refresh()
        if (jumpedForOverlay) {
            jumpedForOverlay = false
            // 去了悬浮窗设置仍没开：绝大多数是侧载应用的「受限设置」弹窗，
            // 系统不会告诉 App 自己被限制，只能靠「返回后状态未变」来推断。
            if (!SetupGuard.check(this).overlay) showOverlayRestrictedDialog()
        }
        if (jumpedForAppInfo) {
            jumpedForAppInfo = false
            if (!SetupGuard.check(this).overlay) showAfterAppInfoDialog()
        }
        if (jumpedForDevOptions) {
            jumpedForDevOptions = false
            val state = SetupGuard.check(this)
            // 系统页在开发者选项未开启时会瞬间自关，用户什么都看不到；
            // 返回后仍未识别到，就用对话框讲清原因和下一步（toast 太容易错过）
            if (!state.devOptions) showDevOptionsStillClosedDialog()
        }
        if (jumpedForDeviceInfo) {
            jumpedForDeviceInfo = false
            if (autoJumpCount < 2) {
                autoJumpCount++
                // 点完版本号回来：直接接力跳到开发者选项，用户不用自己找
                openDevSettings()
            } else {
                toast("仍未识别到开发者选项，请按卡片里的说明再操作一次")
            }
        }
    }

    /**
     * 开发者选项仍未开启时的说明框。
     *
     * 措辞刻意不指责用户：99% 的情况不是「你没点对」，而是
     * 「系统页被自动关掉了，所以你根本没看到任何东西」。先解释现象，
     * 再给下一步，最后给「我已经开了，重新检测」的出口。
     */
    private fun showDevOptionsStillClosedDialog() {
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(this)
            .setTitle("开发者选项还没开启")
            .setMessage(
                "刚才那个系统页面会自己关掉，这是正常现象 —— " +
                    "因为开发者选项还没打开，系统不提供这个入口。\n\n" +
                    "正确的顺序是：\n" +
                    "1. 去「关于手机」\n" +
                    "2. 往下滑到页面底部，找到「版本号」\n" +
                    "3. 连续点它 7 次，直到看到「已进入开发者模式」\n\n" +
                    "完成后开发者选项会自动出现在设置里，回来这里点「下一步」即可。"
            )
            // 只留两个按钮：三个按钮时 AlertDialog 会把 neutral 排到最下面，
            // 主操作反而不在最显眼的位置。
            .setPositiveButton("去「关于手机」") { _, _ -> openDeviceInfo() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    override fun onDestroy() {
        SetupHintBanner.hide()
        super.onDestroy()
    }

    private fun refresh() {
        val state = SetupGuard.check(this)
        // 三项全绿才收横幅：那说明用户已经照着提示做完了，
        // 提示留着的唯一作用就是干扰。中途返回则保留（见 onResume 注释）。
        if (state.ready) SetupHintBanner.hide()

        paint(overlayDot, overlayStatus, overlayAction, state.overlay, "已开启", "未开启（可选）")
        if (!state.overlay) {
            // 悬浮窗不再是硬门槛：用中性色提示「可选」，避免和必须项的红色混淆
            overlayStatus.setTextColor(getColor(R.color.pf_text_secondary))
            overlayDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.pf_text_secondary)
            )
        }
        paint(devDot, devStatus, devAction, state.devOptions, "已开启（自动跳过）", "未开启")
        paint(mockDot, mockStatus, mockAction, state.mockApp, "已选择：${getString(R.string.app_name)}", "未选择")
        if (!state.devOptions) mockStatus.text = "先开启开发者选项"

        devActionDirect.visibility = if (state.devOptions) View.GONE else View.VISIBLE
        val manualRow = findViewById<TextView>(R.id.stepDevManual)
        manualRow.visibility = if (state.devOptions) View.GONE else View.VISIBLE
        if (!state.devOptions && SetupGuard.isManualDevConfirmed(this)) {
            devStatus.text = "已开启（手动确认）"
            devStatus.setTextColor(getColor(R.color.pf_ok))
        }
        devAction.text = if (SetupGuard.hasVisitedDeviceInfo(this)) {
            "1. 再次前往「关于手机」点版本号"
        } else {
            "1. 去「关于手机」连续点版本号 7 次"
        }

        primary.text = when {
            state.ready -> "配置完成，立即开跑"
            state.firstMissing == SetupGuard.Step.DEV_OPTIONS -> "下一步：开启开发者选项"
            state.firstMissing == SetupGuard.Step.MOCK_APP -> "下一步：选择模拟位置应用"
            else -> "下一步"
        }

        val first = state.firstMissing
        if (first != null) {
            val cardId = when (first) {
                SetupGuard.Step.OVERLAY -> R.id.stepOverlayCard
                SetupGuard.Step.DEV_OPTIONS -> R.id.stepDevCard
                SetupGuard.Step.MOCK_APP -> R.id.stepMockCard
            }
            findViewById<View>(cardId).post {
                scroll.smoothScrollTo(0, findViewById<View>(cardId).top - dp(70))
            }
        }
    }

    private fun paint(
        dot: View,
        status: TextView,
        action: TextView,
        done: Boolean,
        doneText: String,
        todoText: String
    ) {
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (done) R.color.pf_ok else R.color.pf_bad)
        )
        status.text = if (done) doneText else todoText
        status.setTextColor(getColor(if (done) R.color.pf_ok else R.color.pf_bad))
        action.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun stepNumber(step: SetupGuard.Step?): Int = when (step) {
        SetupGuard.Step.OVERLAY -> 1
        SetupGuard.Step.DEV_OPTIONS -> 2
        SetupGuard.Step.MOCK_APP -> 3
        null -> 0
    }

    private fun openOverlaySettings() {
        jumpedForOverlay = true
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.isSuccess
        if (!ok) {
            jumpedForOverlay = false
            toast("无法打开悬浮窗设置，请手动在系统设置中开启")
        }
    }

    /**
     * 系统「受限设置」说明。
     *
     * 侧载安装的应用在 Android 13+ 与多数国产 ROM 上会被冻结悬浮窗等敏感能力，
     * 开关旁边会弹出「部分功能已被限制」。App 无法查询或绕过该状态，只能把用户
     * 准确带到「应用信息 → 右上角 ⋮」并给出对应品牌的菜单名。
     */
    private fun showOverlayRestrictedDialog() {
        val app = getString(R.string.app_name)
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(this)
            .setTitle("悬浮窗仍未开启")
            .setMessage(
                "如果你刚才看到了「$app 的部分功能已被限制」，这是 Android 对「非应用商店安装」的保护：" +
                    "侧载应用会先被冻结悬浮窗等敏感能力，不是 $app 在索取额外权限，也不是安装失败。\n\n" +
                    "解除方法：\n" +
                    SetupGuard.restrictionMenuHint(this) + "\n\n" +
                    "点「解除设置限制 / 解除限制 / 允许受限制的设置」后，回到本页重新开启悬浮窗即可。\n\n" +
                    "如果刚才没有看到任何限制提示，直接回到悬浮窗开关再试一次。"
            )
            .setPositiveButton("打开应用信息") { _, _ -> openAppInfo() }
            .setNegativeButton("暂不解除，用通知栏控制") { _, _ ->
                refresh()
                if (SetupGuard.check(this).ready) {
                    toast("已切换为通知栏控制：暂停 / 收尾都在通知栏里")
                }
            }
            .show()
    }

    private fun showAfterAppInfoDialog() {
        val app = getString(R.string.app_name)
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(this)
            .setTitle("解除后回来点「重新检测」")
            .setMessage(
                "请确认在「$app」应用信息页点过右上角「⋮ / 更多」，并选择了" +
                    "「解除设置限制 / 解除应用限制 / 允许受限制的设置」。\n\n" +
                    "如果菜单里没有这一项，说明你的系统没有限制，直接重新开启悬浮窗即可。"
            )
            .setPositiveButton("重新检测") { _, _ ->
                refresh()
                if (!SetupGuard.check(this).overlay) toast("悬浮窗仍未开启，请再检查一次")
            }
            .setNeutralButton("再打开应用信息") { _, _ -> openAppInfo() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun openAppInfo() {
        jumpedForAppInfo = true
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.isSuccess
        if (!ok) {
            jumpedForAppInfo = false
            toast("无法打开应用信息页，请手动到系统设置里查找")
        }
    }

    private fun openDeviceInfo() {
        SetupGuard.markDeviceInfoVisited(this)
        // 先把指引讲清楚再跳转：实测悬浮横幅在系统设置页上不可见
        // （APPLICATION_OVERLAY 被系统 Activity 压住），而「关于本机」首屏
        // 只有设备名称/手机号码/机主，「版本号」在下面一屏。
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(this)
            .setTitle("接下来要做的 3 件事")
            .setMessage(
                "马上会打开「关于手机」页面，请按这个顺序操作：\n\n" +
                    "1. 手指往上滑，滚到页面最底部\n" +
                    "2. 找到「版本号」\n" +
                    "   （有的手机叫「Build 号」「编译编号」「内部版本号」「OS 版本」）\n" +
                    "3. 连续点它 7 次\n\n" +
                    "点对了会看到屏幕底部倒数「再点 N 次即可进入开发者模式」。\n" +
                    "看到「已进入开发者模式」就成功了，按返回键回来。"
            )
            .setPositiveButton("明白了，去操作") { _, _ -> launchDeviceInfo() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun launchDeviceInfo() {
        jumpedForDeviceInfo = true
        val ok = runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }.isSuccess
        if (!ok) {
            jumpedForDeviceInfo = false
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { toast("无法打开系统设置") }
        }
    }

    /** 直接打开开发者选项；未开启时系统页会自关，用对话框讲清原因 */
    private fun openDevSettings() {
        SetupHintBanner.show(
            this,
            "往下滑到底部 → 点「选择模拟位置信息应用」→ 选「${getString(R.string.app_name)}」"
        )
        jumpedForDevOptions = true
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess
        if (!ok) {
            jumpedForDevOptions = false
            showDevOptionsStillClosedDialog()
        }
    }

    /** 「选择模拟位置应用」的前置说明：这一项在开发者选项页最底部，且要二次确认。 */
    private fun openMockSettings() {
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(this)
            .setTitle("接下来要做的 2 件事")
            .setMessage(
                "马上会打开「开发者选项」页面，请按这个顺序操作：\n\n" +
                    "1. 一直往下滑，滑到页面最底部\n" +
                    "2. 找到「选择模拟位置信息应用」并点它\n" +
                    "3. 在弹出的列表里选「${getString(R.string.app_name)}」\n\n" +
                    "选完会回到开发者选项页，然后按返回键回来这里。\n" +
                    "找不到这一项？页面顶部搜索框里粘贴「模拟位置」即可。"
            )
            .setPositiveButton("明白了，去操作") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure { toast("无法打开开发者选项") }
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun copyMockKeyword() {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("模拟位置", "模拟位置"))
            toast("已复制，去系统设置搜索框粘贴即可")
        }
    }

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(this, message)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
