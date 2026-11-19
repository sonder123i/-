package com.zcshou.gogogo.run.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.liquidglass.ScrollEdgeBlurView
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.AdvancedPrefs
import com.zcshou.gogogo.run.data.prefs.DisplayPrefs
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.ShizukuWifiHelper
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.gogogo.run.update.UpdateConfig
import com.zcshou.gogogo.run.update.UpdateManager
import kotlinx.coroutines.launch

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
    private var lastGlassRefreshAt = 0L

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
            "未允许：仍可开跑，暂停/收尾在通知栏；点此开启胶囊",
            jump = {
                // 走完整向导：侧载安装的「受限设置」需要在应用信息里解除，
                // 直接跳系统悬浮窗页只会弹出限制提示，用户拿不到下一步。
                openIntent(Intent(requireContext(), SetupWizardActivity::class.java))
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

        val updateRow = view.findViewById<View>(R.id.rowUpdate)
        updateRow.findViewById<TextView>(R.id.envTitle).text = "应用更新"
        updateRow.findViewById<TextView>(R.id.envSubtitle).text =
            "当前版本 v${BuildConfig.VERSION_NAME} · 点击检查更新"
        paintDot(updateRow.findViewById(R.id.envDot), null)
        updateRow.setOnClickListener {
            val manager = UpdateManager.getInstance(requireContext())
            if (!UpdateConfig.enabled) {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    requireContext(), "当前构建未配置更新源"
                )
                return@setOnClickListener
            }
            val state = manager.state.value
            if (state.active ||
                state.phase == UpdateManager.Phase.PAUSED ||
                state.phase == UpdateManager.Phase.READY ||
                state.check == UpdateManager.Check.AVAILABLE ||
                state.check == UpdateManager.Check.CACHED
            ) {
                manager.showDownload()
            } else {
                manager.checkForUpdate(manual = true)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.getInstance(requireContext()).state.collect { state ->
                    updateRow.findViewById<TextView>(R.id.envSubtitle).text =
                        updateSubtitle(state)
                    paintDot(updateRow.findViewById(R.id.envDot), updateDot(state))
                }
            }
        }

        val aboutRow = view.findViewById<View>(R.id.rowAbout)
        aboutRow.findViewById<TextView>(R.id.envTitle).text = "关于${getString(R.string.app_name)}"
        aboutRow.findViewById<TextView>(R.id.envSubtitle).text = "版本信息 · 开源许可与致谢"
        paintDot(aboutRow.findViewById(R.id.envDot), null)
        aboutRow.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        setupAdvanced(view)
        applyScrollInsets(view)

        view.findViewById<TextView>(R.id.btnRefreshEnv).setOnClickListener { refresh() }
    }

    /** 全屏沉浸式：ScrollView 自己消化状态栏/导航栏 inset，底部留白防止最后一项被遮挡。 */
    private fun applyScrollInsets(root: View) {
        val scroll = root.findViewById<View>(R.id.envScroll)
        val scrim = root.findViewById<View>(R.id.topScrim)
        val edgeBlur = root.findViewById<ScrollEdgeBlurView>(R.id.topEdgeBlur)
        edgeBlur.edge = ScrollEdgeBlurView.Edge.TOP
        edgeBlur.maxBlurRadius = 22f * resources.displayMetrics.density
        edgeBlur.bindScrollView(scroll)
        val baseTop = dp(6)
        // 与 LiquidTabBar.NavMetrics.contentInsetDp 同源，改轨道几何必须同步这里。
        val baseBottom = dp(96)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                target.paddingLeft,
                baseTop + bars.top,
                target.paddingRight,
                baseBottom + bars.bottom
            )
            edgeBlur.layoutParams?.let {
                it.height = bars.top + dp(36)
                edgeBlur.layoutParams = it
            }
            scrim.layoutParams?.let {
                it.height = bars.top + dp(12)
                scrim.layoutParams = it
            }
            insets
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            edgeBlur.visibility = if (scrollY > dp(6)) View.VISIBLE else View.INVISIBLE
            refreshGlassOnScroll()
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    /** 滚动时实时更新玻璃背景采样（80ms 节流），保证底部导航的模糊透视连续。 */
    private fun refreshGlassOnScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassRefreshAt < 80) return
        lastGlassRefreshAt = now
        GlassRenderGate.requestRefresh(160L)
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
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0,
                if (show) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                0
            )
        }

        val scanRow = root.findViewById<View>(R.id.rowWifiScan)
        scanRow.findViewById<TextView>(R.id.envTitle).text = "后台 Wi-Fi 扫描"
        scanRow.setOnClickListener {
            com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                requireContext(),
                "可在系统「WLAN 高级设置」中关闭「始终允许扫描」",
                long = true
            )
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
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                        requireContext(),
                        "请在 Shizuku 弹窗中允许授权后重试"
                    )
                } else {
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                        requireContext(),
                        "未安装 Shizuku，无法启用该实验功能"
                    )
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
        val labels = arrayOf("曜石黑（深色）", "日光白（浅色）", "跟随系统")
        val checked = modes.indexOf(ThemeHelper.themeMode(context)).coerceAtLeast(0)
        GlassDialogs.builder(context)
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
        val labels = arrayOf("液态玻璃（实时模糊）", "省电平衡（磨砂）")
        val checked = qualities.indexOf(DisplayPrefs.quality(context)).coerceAtLeast(0)
        GlassDialogs.builder(context)
            .setTitle("渲染质量")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                DisplayPrefs.setQuality(context, qualities[which])
                DeepRunGlassManager.refreshAll()
                refresh()
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    context,
                    "已切换：${labels[which]}"
                )
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
                ThemeHelper.ThemeMode.DARK -> "曜石黑 · 深色（OLED 纯黑省电）"
                ThemeHelper.ThemeMode.LIGHT -> "日光白 · 浅色（户外强光防眩）"
                ThemeHelper.ThemeMode.SYSTEM -> "跟随系统昼夜切换"
            }

        val renderRow = root.findViewById<View>(R.id.rowRenderQuality)
        val quality = DisplayPrefs.quality(requireContext())
        val tier = DeepRunGlassManager.tier(requireContext())
        val downgrade = DeepRunGlassManager.downgradeReason(requireContext())
        renderRow.findViewById<TextView>(R.id.envSubtitle).text = when {
            quality == DisplayPrefs.RenderQuality.BALANCED -> "省电平衡 · 当前 ${tier.title}"
            downgrade == null -> "液态玻璃 · ${tier.detail}"
            else -> "液态玻璃 · 当前 ${tier.title}（$downgrade）"
        }

        val scanRow = root.findViewById<View>(R.id.rowWifiScan)
        val scanOn = EnvGuardManager.isWifiScanAlwaysAvailable(requireContext())
        paintDot(
            scanRow.findViewById(R.id.envDot),
            if (scanOn) null else true
        )
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
        GlassDialogs.builder(requireContext())
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
        GlassDialogs.builder(context)
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

    /**
     * 6dp 精致状态圆点：仅异常用 Apple Red，正常用 Apple Green，其余中性灰。
     * 不再使用大号彩色 ● 字符，避免在玻璃卡上显得廉价。
     */
    private fun updateSubtitle(state: UpdateManager.State): String {
        val current = "当前版本 v${BuildConfig.VERSION_NAME}"
        return when {
            state.active -> "正在下载 ${state.progress}% · 点击查看"
            state.phase == UpdateManager.Phase.PAUSED ->
                "已暂停 ${state.progress}% · 点击继续"
            state.phase == UpdateManager.Phase.READY -> "安装包已就绪 · 点击安装"
            state.phase == UpdateManager.Phase.FAILED ->
                state.message.ifBlank { "下载失败 · 点击重试" }
            state.check == UpdateManager.Check.CHECKING -> "正在检查更新…"
            state.check == UpdateManager.Check.AVAILABLE ->
                "发现 v${state.manifest?.versionName} · 点击查看"
            state.check == UpdateManager.Check.UP_TO_DATE -> "已是最新 · v${BuildConfig.VERSION_NAME}"
            state.check == UpdateManager.Check.CACHED ->
                state.message.ifBlank { "显示上次验证的更新信息" }
            state.check == UpdateManager.Check.FAILED ->
                state.message.ifBlank { "检查失败 · 点击重试" }
            else -> "$current · 点击检查更新"
        }
    }

    private fun updateDot(state: UpdateManager.State): Boolean? = when {
        state.active || state.phase == UpdateManager.Phase.PAUSED -> null
        state.phase == UpdateManager.Phase.READY -> true
        state.check == UpdateManager.Check.AVAILABLE -> false
        state.check == UpdateManager.Check.FAILED -> false
        state.check == UpdateManager.Check.UP_TO_DATE -> true
        else -> null
    }

    private fun paintDot(dot: View, ok: Boolean?) {
        (dot as? com.zcshou.gogogo.run.ui.widget.StatusOrbView)?.setState(ok)
    }

    private fun openIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    requireContext(),
                    "跳转失败，请手动到系统设置中配置"
                )
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
