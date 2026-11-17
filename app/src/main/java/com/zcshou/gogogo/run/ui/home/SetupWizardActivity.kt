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
import android.widget.Toast
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
    private lateinit var overlayDot: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayAction: TextView
    private lateinit var devDot: TextView
    private lateinit var devStatus: TextView
    private lateinit var devAction: TextView
    private lateinit var devActionDirect: TextView
    private lateinit var mockDot: TextView
    private lateinit var mockStatus: TextView
    private lateinit var mockAction: TextView
    private lateinit var primary: TextView

    /** 刚从系统开发者选项页返回，用于判断开关到底有没有打开 */
    private var jumpedForDevOptions = false

    /** 刚从「关于手机」返回，用于自动接力跳到开发者选项 */
    private var jumpedForDeviceInfo = false

    /** 自动接力次数上限，避免系统页打不开时来回弹 */
    private var autoJumpCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)
        ThemeHelper.padForSystemBars(findViewById(R.id.setupRoot))

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
        findViewById<TextView>(R.id.stepDevHint).text = SetupGuard.developerHint()

        findViewById<TextView>(R.id.btnWizardClose).setOnClickListener { finish() }
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
        SetupHintBanner.hide()
        refresh()
        if (jumpedForDevOptions) {
            jumpedForDevOptions = false
            val state = SetupGuard.check(this)
            // 系统页在开发者选项未开启时会瞬间自关，用户什么都看不到；
            // 返回后仍未识别到，就直接提示改走「关于手机」或手动确认
            if (!state.devOptions) {
                toast("未检测到开发者选项，请先在「关于手机」连点版本号 7 次")
            }
        }
        if (jumpedForDeviceInfo) {
            jumpedForDeviceInfo = false
            if (autoJumpCount < 2) {
                autoJumpCount++
                // 点完版本号回来：直接接力跳到开发者选项，用户不用自己找
                openDevSettings()
            } else {
                toast("开发者选项仍未开启，请按提示手动点「② 进入开发者选项」")
            }
        }
    }

    override fun onDestroy() {
        SetupHintBanner.hide()
        super.onDestroy()
    }

    private fun refresh() {
        val state = SetupGuard.check(this)
        paint(overlayDot, overlayStatus, overlayAction, state.overlay, "已开启", "未开启")
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
            "① 再次前往「关于手机」点版本号"
        } else {
            "① 去「关于手机」连续点版本号 7 次"
        }

        primary.text = if (state.ready) "🎉 配置完成，立即开跑！"
        else "下一步：完成第 ${stepNumber(state.firstMissing)} 项"
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
        dot: TextView,
        status: TextView,
        action: TextView,
        done: Boolean,
        doneText: String,
        todoText: String
    ) {
        dot.setTextColor(getColor(if (done) R.color.pf_ok else R.color.pf_amber))
        status.text = if (done) doneText else todoText
        status.setTextColor(getColor(if (done) R.color.pf_ok else R.color.pf_amber))
        action.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun stepNumber(step: SetupGuard.Step?): Int = when (step) {
        SetupGuard.Step.OVERLAY -> 1
        SetupGuard.Step.DEV_OPTIONS -> 2
        SetupGuard.Step.MOCK_APP -> 3
        null -> 0
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast("无法打开悬浮窗设置，请手动在系统设置中开启") }
    }

    private fun openDeviceInfo() {
        SetupGuard.markDeviceInfoVisited(this)
        SetupHintBanner.show(this, "👆 ${SetupGuard.developerHint()}")
        jumpedForDeviceInfo = true
        val ok = runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }.isSuccess
        if (!ok) {
            jumpedForDeviceInfo = false
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { toast("无法打开系统设置") }
        }
    }

    /** 直接打开开发者选项；未开启时系统页打不开，提示回去点版本号 */
    private fun openDevSettings() {
        SetupHintBanner.show(this, "找到「选择模拟位置信息应用」，选中「${getString(R.string.app_name)}」")
        jumpedForDevOptions = true
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess
        if (!ok) {
            jumpedForDevOptions = false
            toast("开发者选项尚未开启，请先点「① 去关于手机」连点版本号 7 次")
        }
    }

    private fun openMockSettings() {
        SetupHintBanner.show(
            this,
            "往下滑到底部 → 点「选择模拟位置信息应用」→ 选「${getString(R.string.app_name)}」"
        )
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { toast("无法打开开发者选项") }
    }

    private fun copyMockKeyword() {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("模拟位置", "模拟位置"))
            toast("已复制，去系统设置搜索框粘贴即可")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
