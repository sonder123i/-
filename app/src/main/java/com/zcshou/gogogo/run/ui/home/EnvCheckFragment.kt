package com.zcshou.gogogo.run.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.AdvancedPrefs
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.ShizukuWifiHelper
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * Tab 3：环境体检 —— 模拟位置 / 悬浮窗 / 电池白名单 / 真实 Wi-Fi 连接态。
 * 极客项（后台扫描、Shizuku）收进底部「高级选项」，默认折叠。
 */
class EnvCheckFragment : Fragment() {

    private data class EnvRow(
        val root: View,
        val checker: () -> Boolean,
        val okHint: String,
        val failHint: String,
        var jump: (() -> Unit)? = null
    )

    private val rows = mutableListOf<EnvRow>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_env_check, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        addRow(
            view, R.id.rowMock, "模拟位置授权",
            { EnvGuardManager.isMockLocationAllowed(requireContext()) },
            "已授权本应用注入模拟位置",
            "未授权：开发者选项 → 选择模拟位置应用",
            jump = { openIntent(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        )
        addRow(
            view, R.id.rowOverlay, "悬浮窗权限",
            { EnvGuardManager.isOverlayAllowed(requireContext()) },
            "已允许悬浮窗，巡航胶囊可用",
            "未允许：将无法显示进度胶囊",
            jump = {
                openIntent(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
        )
        addRow(
            view, R.id.rowBattery, "忽略电池优化",
            { EnvGuardManager.isIgnoringBatteryOptimizations(requireContext()) },
            "已加入白名单，长跑不易被冻结",
            "未加入白名单：后台可能被系统冻结",
            jump = { openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        )
        addRow(
            view, R.id.rowWifi, "Wi-Fi 连接",
            { !EnvGuardManager.isWifiActivelyConnected(requireContext()) },
            "当前未连接 Wi-Fi，开跑不会弹提醒",
            "已连接 Wi-Fi：开跑前会提醒关闭，避免定位闪回",
            jump = { openIntent(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        )

        val targetAppRow = view.findViewById<View>(R.id.rowTargetApp)
        targetAppRow.findViewById<TextView>(R.id.envTitle).text = "目标跑步 App"
        paintDot(targetAppRow.findViewById(R.id.envDot), null)
        targetAppRow.setOnClickListener { pickTargetApp() }

        val themeRow = view.findViewById<View>(R.id.rowTheme)
        themeRow.findViewById<TextView>(R.id.envTitle).text = "视觉主题"
        paintDot(themeRow.findViewById(R.id.envDot), null)
        themeRow.setOnClickListener { showThemeDialog() }

        val renderRow = view.findViewById<View>(R.id.rowRenderQuality)
        renderRow.findViewById<TextView>(R.id.envTitle).text = "渲染质量"
        paintDot(renderRow.findViewById(R.id.envDot), null)
        renderRow.setOnClickListener { showRenderQualityDialog() }

        val aboutRow = view.findViewById<View>(R.id.rowAbout)
        aboutRow.findViewById<TextView>(R.id.envTitle).text = "关于步频流"
        aboutRow.findViewById<TextView>(R.id.envSubtitle).text = "版本信息 · 开源许可与致谢"
        paintDot(aboutRow.findViewById(R.id.envDot), null)
        aboutRow.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        setupAdvanced(view)

        view.findViewById<Button>(R.id.btnRefreshEnv).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun setupAdvanced(root: View) {
        val group = root.findViewById<View>(R.id.advancedGroup)
        val toggle = root.findViewById<TextView>(R.id.btnAdvancedToggle)
        toggle.setOnClickListener {
            val show = group.visibility != View.VISIBLE
            group.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "高级与极客选项 ⌃" else "高级与极客选项 ⌄"
        }

        val scanRow = root.findViewById<View>(R.id.rowWifiScan)
        scanRow.findViewById<TextView>(R.id.envTitle).text = "后台 Wi-Fi 扫描"
        scanRow.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "可在系统「WLAN 高级设置」中关闭「始终允许扫描」",
                Toast.LENGTH_LONG
            ).show()
            openIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        val intervalRow = root.findViewById<View>(R.id.rowInjectInterval)
        intervalRow.findViewById<TextView>(R.id.envTitle).text = "坐标注入频率"
        intervalRow.setOnClickListener { showInjectionIntervalDialog() }

        val shizukuRow = root.findViewById<View>(R.id.rowShizuku)
        shizukuRow.findViewById<TextView>(R.id.envTitle).text = "Shizuku 自动断网（实验性）"
        val switch = shizukuRow.findViewById<SwitchCompat>(R.id.advancedSwitch)
        switch.isChecked = AdvancedPrefs.isShizukuAutoWifiEnabled(requireContext())
        switch.setOnCheckedChangeListener { button, checked ->
            if (checked && !EnvGuardManager.isShizukuReady()) {
                button.isChecked = false
                if (ShizukuWifiHelper.isAvailable()) {
                    ShizukuWifiHelper.requestPermission(7001)
                    Toast.makeText(requireContext(), "请在 Shizuku 弹窗中允许授权后重试", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "未安装 Shizuku，无法启用该实验功能", Toast.LENGTH_SHORT).show()
                }
                return@setOnCheckedChangeListener
            }
            AdvancedPrefs.setShizukuAutoWifiEnabled(requireContext(), checked)
        }
    }

    private fun addRow(
        root: View,
        includeId: Int,
        title: String,
        checker: () -> Boolean,
        okHint: String,
        failHint: String,
        jump: (() -> Unit)? = null
    ) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.envTitle).text = title
        row.setOnClickListener { jump?.invoke() }
        rows.add(EnvRow(row, checker, okHint, failHint, jump))
    }

    private fun showThemeDialog() {
        val context = requireContext()
        val modes = arrayOf(
            ThemeHelper.ThemeMode.DARK,
            ThemeHelper.ThemeMode.LIGHT,
            ThemeHelper.ThemeMode.SYSTEM
        )
        val labels = arrayOf("深海光学（暗色）", "冰川棱镜（浅色）", "跟随系统")
        val checked = modes.indexOf(ThemeHelper.themeMode(context)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("视觉主题")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                ThemeHelper.applyThemeMode(context, modes[which])
                // uiMode 变化时 AppCompat 会自动重建 Activity；未重建的场景在这里补刷文案
                view?.post { if (isAdded) refresh() }
            }
            .show()
    }

    private fun showRenderQualityDialog() {
        val context = requireContext()
        val qualities = arrayOf(
            DisplayPrefs.RenderQuality.OPTICAL,
            DisplayPrefs.RenderQuality.BALANCED
        )
        val labels = arrayOf("极致光学", "省电平衡")
        val checked = qualities.indexOf(DisplayPrefs.quality(context)).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("渲染质量")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                DisplayPrefs.setQuality(context, qualities[which])
                DeepRunGlassManager.refreshAll()
                refresh()
                Toast.makeText(context, "已切换：${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refresh() {
        rows.forEach { row ->
            val ok = runCatching { row.checker() }.getOrDefault(false)
            paintDot(row.root.findViewById(R.id.envDot), ok)
            row.root.findViewById<TextView>(R.id.envSubtitle).text =
                if (ok) row.okHint else row.failHint
        }

        val root = view ?: return
        val targetAppRow = root.findViewById<View>(R.id.rowTargetApp)
        val targetPkg = RunPrefs.targetApp(requireContext())
        targetAppRow.findViewById<TextView>(R.id.envSubtitle).text = if (targetPkg == null) {
            "未绑定：开跑时不会自动拉起运动 App"
        } else {
            "开跑 5s 预热时自动拉起：${appLabel(targetPkg)}"
        }

        val themeRow = root.findViewById<View>(R.id.rowTheme)
        themeRow.findViewById<TextView>(R.id.envSubtitle).text =
            when (ThemeHelper.themeMode(requireContext())) {
                ThemeHelper.ThemeMode.DARK -> "深海光学 · 暗色（OLED 省电）"
                ThemeHelper.ThemeMode.LIGHT -> "冰川棱镜 · 浅色（户外强光防眩）"
                ThemeHelper.ThemeMode.SYSTEM -> "跟随系统昼夜切换"
            }

        val renderRow = root.findViewById<View>(R.id.rowRenderQuality)
        val quality = DisplayPrefs.quality(requireContext())
        val tier = DeepRunGlassManager.tier(requireContext())
        val downgrade = DeepRunGlassManager.downgradeReason(requireContext())
        renderRow.findViewById<TextView>(R.id.envSubtitle).text = when {
            quality == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡 · 最高 ${tier.title}"
            downgrade == null -> "极致光学 · ${tier.detail}"
            else -> "极致光学 · 当前 ${tier.title}（$downgrade）"
        }

        val scanRow = root.findViewById<View>(R.id.rowWifiScan)
        val scanOn = EnvGuardManager.isWifiScanAlwaysAvailable(requireContext())
        val scanDot = scanRow.findViewById<TextView>(R.id.envDot)
        scanDot.text = "●"
        scanDot.setTextColor(if (scanOn) 0xFFFFB300.toInt() else 0xFF8A94A6.toInt())
        scanRow.findViewById<TextView>(R.id.envSubtitle).text =
            if (scanOn) "系统「始终允许扫描」开启，仅提示，不影响开跑" else "后台扫描已关闭"

        val intervalRow = root.findViewById<View>(R.id.rowInjectInterval)
        intervalRow.findViewById<TextView>(R.id.envSubtitle).text =
            "当前 ${AdvancedPrefs.injectionIntervalMs(requireContext())}ms · 200ms 更顺滑，1000ms 更省电"

        val shizukuRow = root.findViewById<View>(R.id.rowShizuku)
        val switch = shizukuRow.findViewById<SwitchCompat>(R.id.advancedSwitch)
        val enabled = AdvancedPrefs.isShizukuAutoWifiEnabled(requireContext())
        if (switch.isChecked != enabled && EnvGuardManager.isShizukuReady()) {
            switch.isChecked = enabled
        }
        shizukuRow.findViewById<TextView>(R.id.envSubtitle).text = when {
            !ShizukuWifiHelper.isAvailable() -> "未安装 Shizuku（普通用户无需开启）"
            !EnvGuardManager.isShizukuReady() -> "已安装未授权：点击开关可申请授权"
            enabled -> "已开启：开跑前自动关 Wi-Fi，跑完恢复"
            else -> "已就绪：开启后才会自动关 Wi-Fi"
        }
    }

    private fun pickTargetApp() {
        val pm = requireContext().packageManager
        val self = requireContext().packageName
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second }
            .toList()
        val labels = (listOf("不绑定") + apps.map { it.second }).toTypedArray()
        val current = RunPrefs.targetApp(requireContext())
        val checked = apps.indexOfFirst { it.first == current } + 1
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("开跑时自动拉起的目标 App")
            .setSingleChoiceItems(labels, if (checked > 0) checked else 0) { dialog, which ->
                RunPrefs.setTargetApp(requireContext(), if (which == 0) null else apps[which - 1].first)
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showInjectionIntervalDialog() {
        val context = requireContext()
        val current = AdvancedPrefs.injectionIntervalMs(context).toInt()
        val label = TextView(context).apply {
            text = "当前 ${current}ms"
            setTextColor(context.getColor(R.color.pf_text_primary))
            textSize = 13f
        }
        val seek = android.widget.SeekBar(context).apply {
            max = 800
            progress = current - 200
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(label)
            addView(seek)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "当前 ${200 + progress}ms"
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
        })
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("坐标注入频率")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                AdvancedPrefs.setInjectionIntervalMs(context, (200 + seek.progress).toLong())
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun paintDot(dot: TextView, ok: Boolean?) {
        dot.text = "●"
        dot.setTextColor(
            when (ok) {
                true -> 0xFF22C55E.toInt()
                false -> 0xFFEF4444.toInt()
                null -> 0xFF8A94A6.toInt()
            }
        )
    }

    private fun openIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), "跳转失败，请手动到系统设置中配置", Toast.LENGTH_SHORT).show()
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
