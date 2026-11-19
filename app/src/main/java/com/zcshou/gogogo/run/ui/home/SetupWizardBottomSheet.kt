package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.guard.SetupHintBanner

/**
 * 首次两步式小白向导：悬浮窗 + 模拟位置应用。
 * 返回 App 时通过 onResume 自动复检并推进；完整版向导仍可一键打开。
 */
class SetupWizardBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(): SetupWizardBottomSheet = SetupWizardBottomSheet()
    }

    private lateinit var overlayDot: View
    private lateinit var overlayStatus: TextView
    private lateinit var overlayAction: TextView
    private lateinit var mockDot: View
    private lateinit var mockStatus: TextView
    private lateinit var devHint: TextView
    private lateinit var deviceInfoBtn: TextView
    private lateinit var devSettingsBtn: TextView
    private lateinit var copyMockBtn: TextView
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_setup_guide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (view as? com.example.liquidglass.LiquidGlassView)?.let {
            com.zcshou.gogogo.run.ui.glass.SheetGlass.attach(this, it)
        }
        overlayDot = view.findViewById(R.id.guideOverlayDot)
        overlayStatus = view.findViewById(R.id.guideOverlayStatus)
        overlayAction = view.findViewById(R.id.guideOverlayAction)
        mockDot = view.findViewById(R.id.guideMockDot)
        mockStatus = view.findViewById(R.id.guideMockStatus)
        devHint = view.findViewById(R.id.guideDevHint)
        deviceInfoBtn = view.findViewById(R.id.guideDeviceInfo)
        devSettingsBtn = view.findViewById(R.id.guideDevSettings)
        copyMockBtn = view.findViewById(R.id.guideCopyMock)
        primary = view.findViewById(R.id.guidePrimary)

        overlayAction.setOnClickListener { openOverlaySettings() }
        deviceInfoBtn.setOnClickListener { openDeviceInfo() }
        devSettingsBtn.setOnClickListener { showMockAppGuideDialog() }
        copyMockBtn.setOnClickListener { copyMockKeyword() }
        primary.setOnClickListener { onPrimary() }
        view.findViewById<View>(R.id.btnGuideClose).setOnClickListener { dismissAllowingStateLoss() }
        view.findViewById<TextView>(R.id.guideSandbox).setOnClickListener {
            runCatching { startActivity(Intent(requireContext(), DemoPreviewActivity::class.java)) }
            dismissAllowingStateLoss()
        }
        view.findViewById<TextView>(R.id.guideFullWizard).setOnClickListener {
            runCatching { startActivity(Intent(requireContext(), SetupWizardActivity::class.java)) }
            dismissAllowingStateLoss()
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.28f)
            // 窗口动画会冻住玻璃采样，进场交给 view 层
            setWindowAnimations(0)
        }
        // MaterialComponents 的 design_bottom_sheet 会自带白色 shape 背景，
        // 等布局完成后再清掉从根布局到 dialog decor 之间的所有底色，
        // 露出根布局的顶部圆角玻璃面板。
        view?.post {
            var parent: View? = view?.parent as? View
            while (parent != null && parent !== dialog?.window?.decorView) {
                parent.background = null
                parent = parent.parent as? View
            }
            dialog?.window?.decorView?.background = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::overlayDot.isInitialized) return
        refresh()
        // 从系统页返回后**不要**立刻收横幅：新手正需要它来对照下一步。
        // 只有真正完成了当前步骤才收起（见 refresh 里的 ready 分支）。
        if (jumpedForOverlay) {
            jumpedForOverlay = false
            if (!SetupGuard.check(requireContext()).overlay) showOverlayRestrictedDialog()
        }
        if (jumpedForAppInfo) {
            jumpedForAppInfo = false
            if (!SetupGuard.check(requireContext()).overlay) showAfterAppInfoDialog()
        }
        if (jumpedForDevOptions) {
            jumpedForDevOptions = false
            if (!SetupGuard.check(requireContext()).devOptions) {
                // 系统页在开发者选项未开启时会瞬间自关 —— 用户什么都看不到就被弹回来。
                // 这是新手最容易卡死的一步，必须用对话框明确说清「刚才那次点击无效，
                // 因为开关还没开」，而不是留一条 30 秒后消失的 toast。
                showDevOptionsStillClosedDialog()
            }
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
     * 这里的措辞刻意不指责用户：99% 的情况不是「你没点对」，而是
     * 「系统页被自动关掉了，所以你根本没看到任何东西」。先解释现象，
     * 再给下一步，最后给一个「我已经开了，重新检测」的出口。
     */
    private fun showDevOptionsStillClosedDialog() {
        val context = context ?: return
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(context)
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
            // 屏幕上变成「去关于手机 / 稍后再说 / 我已开启」三段错位，
            // 主操作反而不在最显眼的位置。
            .setPositiveButton("去「关于手机」") { _, _ -> openDeviceInfo() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun refresh() {
        val context = context ?: return
        val state = SetupGuard.check(context)
        overlayDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            context.getColor(
                when {
                    state.overlay -> R.color.pf_ok
                    else -> R.color.pf_text_secondary
                }
            )
        )
        overlayStatus.text = if (state.overlay) "已开启" else "未开启（可选）"
        overlayStatus.setTextColor(context.getColor(if (state.overlay) R.color.pf_ok else R.color.pf_text_secondary))
        overlayAction.visibility = if (state.overlay) View.GONE else View.VISIBLE

        mockDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            context.getColor(if (state.mockApp) R.color.pf_ok else R.color.pf_bad)
        )
        mockStatus.text = when {
            state.mockApp -> "已选择：${context.getString(R.string.app_name)}"
            !state.devOptions -> "先开启开发者选项"
            else -> "未选择"
        }
        mockStatus.setTextColor(context.getColor(if (state.mockApp) R.color.pf_ok else R.color.pf_bad))

        devHint.text = if (state.devOptions) {
            "开发者选项已就绪：直接选择「${context.getString(R.string.app_name)}」"
        } else {
            // 页内定位 + 动作 + 成功反馈，三句缺一不可：
            // 「往下滑」解决找不到，「点 7 次」是动作，「会倒数」是确认自己点对了。
            SetupGuard.locateVersionHint() + "\n" +
                SetupGuard.tapVersionFeedbackHint()
        }
        deviceInfoBtn.visibility = if (state.devOptions) View.GONE else View.VISIBLE
        devSettingsBtn.visibility = View.VISIBLE
        devSettingsBtn.text = if (state.devOptions) "直达开发者选项" else "尝试直达开发者选项"
        copyMockBtn.visibility = View.VISIBLE

        val readyCount = (if (state.devOptions) 1 else 0) + (if (state.mockApp) 1 else 0)
        // 分母固定为核心能力数：悬浮窗是可选项，不再参与「已就绪 X / 共 N」
        val totalCount = 2
        primary.text = if (state.ready) {
            "配置完成，开始使用"
        } else {
            "下一步（已就绪 $readyCount / 共 $totalCount 项核心能力）"
        }
    }

    private fun onPrimary() {
        val context = context ?: return
        val state = SetupGuard.check(context)
        if (state.ready) {
            com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                context,
                "基础环境已就绪，可以开跑了"
            )
            dismissAllowingStateLoss()
            return
        }
        when (state.firstMissing) {
            SetupGuard.Step.OVERLAY -> openOverlaySettings()
            SetupGuard.Step.DEV_OPTIONS -> if (!state.devOptions) openDeviceInfo() else refresh()
            SetupGuard.Step.MOCK_APP -> showMockAppGuideDialog()
            null -> refresh()
        }
    }

    private fun openOverlaySettings() {
        val context = context ?: return
        jumpedForOverlay = true
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }.isSuccess
        if (!ok) {
            jumpedForOverlay = false
            toast("无法打开悬浮窗设置，请手动在系统设置中开启")
        }
    }

    /** 侧载应用的「受限设置」说明，见 SetupWizardActivity 同名方法 */
    private fun showOverlayRestrictedDialog() {
        val context = context ?: return
        val app = context.getString(R.string.app_name)
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(context)
            .setTitle("悬浮窗仍未开启")
            .setMessage(
                "如果你刚才看到了「$app 的部分功能已被限制」，这是 Android 对「非应用商店安装」的保护：" +
                    "侧载应用会先被冻结悬浮窗等敏感能力，不是 $app 在索取额外权限，也不是安装失败。\n\n" +
                    "解除方法：\n" +
                    SetupGuard.restrictionMenuHint(context) + "\n\n" +
                    "点「解除设置限制 / 解除限制 / 允许受限制的设置」后，回到本页重新开启悬浮窗即可。\n\n" +
                    "如果刚才没有看到任何限制提示，直接回到悬浮窗开关再试一次。"
            )
            .setPositiveButton("打开应用信息") { _, _ -> openAppInfo() }
            .setNegativeButton("暂不解除，用通知栏控制") { _, _ ->
                refresh()
                if (SetupGuard.check(context).ready) {
                    toast("已切换为通知栏控制：暂停 / 收尾都在通知栏里")
                    dismissAllowingStateLoss()
                }
            }
            .show()
    }

    private fun showAfterAppInfoDialog() {
        val context = context ?: return
        val app = context.getString(R.string.app_name)
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(context)
            .setTitle("解除后回来点「重新检测」")
            .setMessage(
                "请确认在「$app」应用信息页点过右上角「⋮ / 更多」，并选择了" +
                    "「解除设置限制 / 解除应用限制 / 允许受限制的设置」。\n\n" +
                    "如果菜单里没有这一项，说明你的系统没有限制，直接重新开启悬浮窗即可。"
            )
            .setPositiveButton("重新检测") { _, _ ->
                refresh()
                if (!SetupGuard.check(context).overlay) toast("悬浮窗仍未开启，请再检查一次")
            }
            .setNeutralButton("再打开应用信息") { _, _ -> openAppInfo() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun openAppInfo() {
        val context = context ?: return
        jumpedForAppInfo = true
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }.isSuccess
        if (!ok) {
            jumpedForAppInfo = false
            toast("无法打开应用信息页，请手动到系统设置里查找")
        }
    }

    private fun openDeviceInfo() {
        SetupGuard.markDeviceInfoVisited(requireContext())
        // **先把指引讲清楚，再跳转**。
        //
        // 原来靠跳转后的悬浮横幅提示，但实测横幅在系统设置页上不可见
        // （APPLICATION_OVERLAY 被系统 Activity 压住），用户到「关于本机」
        // 之后什么都看不到 —— 而这一页首屏只有设备名称/手机号码/机主，
        // 「版本号」在下面一屏，不知道要往下滑就彻底卡住。
        //
        // 所以改成 App 内弹窗前置说明：用户带着这条信息进系统页。
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(requireContext())
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
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }.isSuccess
        if (!ok) {
            jumpedForDeviceInfo = false
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { toast("无法打开系统设置") }
        }
    }

    private fun openDevSettings() {
        jumpedForDevOptions = true
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess
        if (!ok) {
            jumpedForDevOptions = false
            // 打不开通常意味着开关还没开：直接给对话框，而不是 toast
            showDevOptionsStillClosedDialog()
        }
    }

    /** 「选择模拟位置应用」的前置说明：这一项在开发者选项页最底部，且要二次确认。 */
    private fun showMockAppGuideDialog() {
        com.zcshou.gogogo.run.ui.glass.GlassDialogs.readingDialog(requireContext())
            .setTitle("接下来要做的 2 件事")
            .setMessage(
                "马上会打开「开发者选项」页面，请按这个顺序操作：\n\n" +
                    "1. 一直往下滑，滑到页面最底部\n" +
                    "2. 找到「选择模拟位置信息应用」并点它\n" +
                    "3. 在弹出的列表里选「${getString(R.string.app_name)}」\n\n" +
                    "选完会回到开发者选项页，然后按返回键回来这里。\n" +
                    "找不到这一项？页面顶部搜索框里粘贴「模拟位置」即可。"
            )
            .setPositiveButton("明白了，去操作") { _, _ -> openDevSettings() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun copyMockKeyword() {
        val context = context ?: return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("模拟位置", "模拟位置"))
            toast("已复制，去系统设置搜索框粘贴即可")
        }
    }

    private fun toast(message: String) {
        context?.let { com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(it, message) }
    }
}
