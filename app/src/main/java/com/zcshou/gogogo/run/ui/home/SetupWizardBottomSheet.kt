package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var overlayDot: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayAction: TextView
    private lateinit var mockDot: TextView
    private lateinit var mockStatus: TextView
    private lateinit var devHint: TextView
    private lateinit var deviceInfoBtn: TextView
    private lateinit var devSettingsBtn: TextView
    private lateinit var copyMockBtn: TextView
    private lateinit var primary: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_setup_guide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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
        devSettingsBtn.setOnClickListener { openDevSettings() }
        copyMockBtn.setOnClickListener { copyMockKeyword() }
        primary.setOnClickListener { onPrimary() }
        view.findViewById<TextView>(R.id.btnGuideClose).setOnClickListener { dismissAllowingStateLoss() }
        view.findViewById<TextView>(R.id.guideFullWizard).setOnClickListener {
            runCatching { startActivity(Intent(requireContext(), SetupWizardActivity::class.java)) }
            dismissAllowingStateLoss()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayDot.isInitialized) refresh()
    }

    private fun refresh() {
        val context = context ?: return
        val state = SetupGuard.check(context)
        overlayDot.setTextColor(context.getColor(if (state.overlay) R.color.pf_ok else R.color.pf_amber))
        overlayStatus.text = if (state.overlay) "已开启" else "未开启"
        overlayStatus.setTextColor(context.getColor(if (state.overlay) R.color.pf_ok else R.color.pf_amber))
        overlayAction.visibility = if (state.overlay) View.GONE else View.VISIBLE

        mockDot.setTextColor(context.getColor(if (state.mockApp) R.color.pf_ok else R.color.pf_amber))
        mockStatus.text = when {
            state.mockApp -> "已选择：${context.getString(R.string.app_name)}"
            !state.devOptions -> "先开启开发者选项"
            else -> "未选择"
        }
        mockStatus.setTextColor(context.getColor(if (state.mockApp) R.color.pf_ok else R.color.pf_amber))

        devHint.text = if (state.devOptions) {
            "开发者选项已就绪：直接选择「${context.getString(R.string.app_name)}」"
        } else {
            SetupGuard.developerHint()
        }
        deviceInfoBtn.visibility = if (state.devOptions) View.GONE else View.VISIBLE
        devSettingsBtn.visibility = View.VISIBLE
        devSettingsBtn.text = if (state.devOptions) "直达开发者选项" else "尝试直达开发者选项"
        copyMockBtn.visibility = View.VISIBLE

        val readyCount = (if (state.overlay) 1 else 0) + (if (state.mockApp) 1 else 0)
        primary.text = if (state.ready) "✅ 配置完成，开始使用" else "刷新检测（已就绪 $readyCount/2）"
    }

    private fun onPrimary() {
        val context = context ?: return
        val state = SetupGuard.check(context)
        if (state.ready) {
            Toast.makeText(context, "基础环境已就绪，可以开跑了", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
            return
        }
        when (state.firstMissing) {
            SetupGuard.Step.OVERLAY -> openOverlaySettings()
            SetupGuard.Step.DEV_OPTIONS -> if (!state.devOptions) openDeviceInfo() else refresh()
            SetupGuard.Step.MOCK_APP -> openDevSettings()
            null -> refresh()
        }
    }

    private fun openOverlaySettings() {
        val context = context ?: return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }.onFailure { toast("无法打开悬浮窗设置，请手动在系统设置中开启") }
    }

    private fun openDeviceInfo() {
        SetupGuard.markDeviceInfoVisited(requireContext())
        SetupHintBanner.show(requireContext(), "👆 ${SetupGuard.developerHint()}")
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { toast("无法打开系统设置") }
        }
    }

    private fun openDevSettings() {
        SetupHintBanner.show(
            requireContext(),
            "找到「选择模拟位置信息应用」，选中「${getString(R.string.app_name)}」"
        )
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess
        if (!ok) toast("开发者选项尚未开启，请先在「关于手机」连点版本号 7 次")
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
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}
