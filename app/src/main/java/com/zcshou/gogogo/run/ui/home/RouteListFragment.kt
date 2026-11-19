package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.BrandConfig
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RouteShareCodec
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.guard.RunShortcutManager
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.ui.editor.RouteEditorActivity
import com.example.liquidglass.ScrollEdgeBlurView
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.glass.DeepRunGlassManager
import com.zcshou.gogogo.run.ui.glass.GlassDialogs
import com.zcshou.gogogo.run.ui.glass.GlassRenderGate
import com.zcshou.gogogo.run.ui.overlay.RouteThumbnailView
import com.zcshou.gogogo.run.ui.widget.LensChipTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tab 1：云迹 —— 本周概览 + 横向路线切换条 + 当前主路线卡 + 路线工坊。
 */
class RouteListFragment : Fragment() {

    private companion object {
        const val PREFS = "pf_home"
        const val KEY_SELECTED = "selected_route_id"
    }

    private lateinit var repository: RunRepository
    private lateinit var emptyView: View
    private lateinit var mainCard: View
    private lateinit var chipScroll: View
    private lateinit var chipScrollView: HorizontalScrollView
    private lateinit var chipRow: LinearLayout
    private lateinit var chipTrack: LensChipTrack
    private lateinit var weekKm: TextView
    private lateinit var weekCount: TextView
    private lateinit var setupBanner: TextView
    private lateinit var routeCountLabel: TextView
    private lateinit var routeListHeader: View
    private lateinit var workshopTitle: View
    private lateinit var workshopCard: View

    private lateinit var thumb: RouteThumbnailView
    private lateinit var nameText: TextView
    private lateinit var metaText: TextView
    private lateinit var scheduleText: TextView
    private lateinit var quickStartBtn: TextView
    private lateinit var shareBtn: View
    private lateinit var tuneBtn: View
    private lateinit var menuBtn: View

    private var routes: List<RunRouteEntity> = emptyList()
    private var schedules: List<RunScheduleEntity> = emptyList()
    private var selectedRouteId = 0L
    private var pendingExport: RunRouteEntity? = null
    private var lastRunPhase: RunPhase? = null
    private var lastGlassRefreshAt = 0L

    private val exportGpxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            val route = pendingExport
            pendingExport = null
            if (uri == null || route == null) return@registerForActivityResult
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val points = TrackJsonCodec.decode(route.denseWaypointsJson)
                        val gpx = RouteSources.toGpx(route.name, points)
                        appContext.contentResolver.openOutputStream(uri)?.use {
                            it.write(gpx.toByteArray(Charsets.UTF_8))
                        } ?: error("empty stream")
                    }.isSuccess
                }
                toast(if (ok) "已导出 GPX：${route.name}" else "GPX 导出失败")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_route_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repository = RunRepository(requireContext())
        emptyView = view.findViewById(R.id.routeEmpty)
        mainCard = view.findViewById(R.id.routeMainCard)
        chipScroll = view.findViewById(R.id.routeChipScroll)
        chipScrollView = view.findViewById(R.id.routeChipScrollView)
        chipRow = view.findViewById(R.id.routeChipRow)
        chipTrack = LensChipTrack(chipRow)
        weekKm = view.findViewById(R.id.weekKm)
        weekCount = view.findViewById(R.id.weekCount)
        setupBanner = view.findViewById(R.id.setupBanner)
        routeCountLabel = view.findViewById(R.id.routeCountLabel)
        routeListHeader = view.findViewById(R.id.routeListHeader)
        workshopTitle = view.findViewById(R.id.routeWorkshopTitle)
        workshopCard = view.findViewById(R.id.workshopCard)

        thumb = mainCard.findViewById(R.id.routeThumb)
        nameText = mainCard.findViewById(R.id.routeName)
        metaText = mainCard.findViewById(R.id.routeMeta)
        scheduleText = mainCard.findViewById(R.id.routeSchedule)
        quickStartBtn = mainCard.findViewById(R.id.btnQuickStart)
        shareBtn = mainCard.findViewById(R.id.btnShareCode)
        tuneBtn = mainCard.findViewById(R.id.btnTune)
        menuBtn = mainCard.findViewById(R.id.routeMenu)

        selectedRouteId = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED, 0L)

        applyScrollInsets(view)

        view.findViewById<TextView>(R.id.btnNewRoute).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<TextView>(R.id.btnQuickPreset).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<TextView>(R.id.btnQuickFree).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_FREE)
        }
        view.findViewById<TextView>(R.id.btnEmptyCreate).setOnClickListener {
            openEditor(RouteEditorActivity.MODE_PRESET)
        }
        view.findViewById<View>(R.id.btnImportShare).setOnClickListener { importShareCode() }
        view.findViewById<View>(R.id.btnEmptyImport).setOnClickListener { importShareCode() }
        setupBanner.setOnClickListener {
            (activity as? RunHomeActivity)?.openSetupWizard()
        }
        view.findViewById<View>(R.id.weekCard).setOnClickListener {
            HistoryReportDialog.show(requireContext(), repository)
        }
        refreshSetupBanner()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeRoutes().collect {
                        routes = it
                        RunShortcutManager.syncShortcuts(requireContext(), it)
                        render()
                    }
                }
                launch {
                    repository.observeSchedules().collect {
                        schedules = it
                        render()
                    }
                }
                launch {
                    val weekStart = weekStartMillis()
                    combine(
                        repository.observeCompletedMetersSince(weekStart),
                        repository.observeCompletedCountSince(weekStart)
                    ) { meters, count -> meters to count }
                        .collect { (meters, count) ->
                            weekKm.text = String.format(Locale.US, "%.2f km", meters / 1000.0)
                            weekCount.text = "$count 次"
                        }
                }
                launch {
                    // V4.0：声呐只在相位切换时触发（state 每个 tick 都会发新值，
                    // 直接 collect 会让 5s 扫描每秒被重置）。省电冻结由 RunSessionService 统一写入。
                    RunSessionHolder.state.collect { state ->
                        if (state.phase == lastRunPhase) return@collect
                        lastRunPhase = state.phase
                        when (state.phase) {
                            RunPhase.PRE_START_SETTLE -> {
                                thumb.startSonar(5000)
                                thumb.setCometRunning(false)
                            }
                            RunPhase.RUNNING,
                            RunPhase.PAUSED,
                            RunPhase.DETOUR_POINT,
                            RunPhase.COOLDOWN_WALK,
                            RunPhase.ENV_GUARD -> {
                                thumb.stopSonar()
                                thumb.setCometRunning(false)
                            }
                            else -> {
                                thumb.stopSonar()
                                thumb.setCometRunning(true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupBanner()
        // 页面可见时恢复巡航光点；相位为巡航中则不启动（0fps）
        val phase = lastRunPhase
        thumb.setCometRunning(phase == null || phase == RunPhase.IDLE || phase == RunPhase.FINISHED)
    }

    override fun onPause() {
        thumb.setCometRunning(false)
        super.onPause()
    }

    /**
     * 首页全屏沉浸式布局：
     * ScrollView 自己消化状态栏与导航栏 inset，内容可以滚到状态栏/悬浮导航下方，
     * 由底部玻璃导航栏产生真实的模糊透视；同时保证最后一项不被导航栏遮挡。
     */
    private fun applyScrollInsets(root: View) {
        val scroll = root.findViewById<View>(R.id.homeScroll)
        val scrim = root.findViewById<View>(R.id.topScrim)
        val edgeBlur = root.findViewById<ScrollEdgeBlurView>(R.id.topEdgeBlur)
        edgeBlur.edge = ScrollEdgeBlurView.Edge.TOP
        edgeBlur.maxBlurRadius = 22f * resources.displayMetrics.density
        edgeBlur.bindScrollView(scroll)
        val baseTop = dp(6)
        // 与 LiquidTabBar.NavMetrics.contentInsetDp 同源：96dp 是 20:9 手势条下的基线，
        // 各页再各自加 bars.bottom。改轨道几何必须同步这里。
        val baseBottom = dp(96)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                target.paddingLeft,
                baseTop + bars.top,
                target.paddingRight,
                baseBottom + bars.bottom
            )
            // 模糊带比状态栏略高：静止时隐藏，滚动后内容进入该带才会得到完整的渐进虚化
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
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // 静止时大标题保持清晰；一旦内容开始滚入状态栏再开启 Scroll Edge 模糊
            edgeBlur.visibility = if (scrollY > dp(6)) View.VISIBLE else View.INVISIBLE
            refreshGlassOnScroll()
            (activity as? RunHomeActivity)?.onHomeScrolled(scrollY - oldScrollY, scrollY)
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    /**
     * 滚动时让玻璃重新采样背景：底部导航与页面卡片会实时糊住滑过的内容，
     * 80ms 节流避免每个滚动帧都做一次整窗快照。
     */
    private fun refreshGlassOnScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGlassRefreshAt < 80) return
        lastGlassRefreshAt = now
        // 与编辑器 / 自由漫游的 onMapStatusChange 同款：滚动期间顺延玻璃刷新窗口
        GlassRenderGate.requestRefresh(160L)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            thumb.setCometRunning(false)
        } else {
            refreshSetupBanner()
            val phase = lastRunPhase
            thumb.setCometRunning(
                phase == null || phase == RunPhase.IDLE || phase == RunPhase.FINISHED
            )
        }
    }

    fun refreshSetupBanner() {
        if (!::setupBanner.isInitialized) return
        val state = SetupGuard.check(requireContext())
        setupBanner.visibility = if (state.ready) View.GONE else View.VISIBLE
        if (!state.ready) {
            // 「3 项」而不是「授权」：其中开发者选项并不是权限，叫「授权」会让用户
            // 去系统权限页找，找不到就放弃了。统一成「配置」。
            setupBanner.text = "还差 ${state.missing.size} 项基础配置 · 点此完成 >"
        }
    }

    private fun weekStartMillis(): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun render() {
        val selected = routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()
        if (selected != null && selected.id != selectedRouteId) {
            selectedRouteId = selected.id
            persistSelection()
        }
        val empty = routes.isEmpty()
        routeCountLabel.text = if (empty) "尚未添加" else "${routes.size} 条路线"
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        mainCard.visibility = if (selected == null) View.GONE else View.VISIBLE
        chipScroll.visibility = if (empty) View.GONE else View.VISIBLE
        // 空状态卡片已经承载了「新建 / 导入口令」两个入口，重复入口全部收起
        routeListHeader.visibility = if (empty) View.GONE else View.VISIBLE
        workshopTitle.visibility = if (empty) View.GONE else View.VISIBLE
        workshopCard.visibility = if (empty) View.GONE else View.VISIBLE
        renderChips(selected?.id ?: 0L)
        selected?.let { bindMainCard(it) }
    }

    private fun renderChips(selectedId: Long) {
        // 选中态切换时复用已有 chip，不触发重新布局，透镜弹簧才能连续滑动
        val reuse = chipRow.childCount == routes.size && routes.isNotEmpty()
        if (!reuse) chipRow.removeAllViews()
        val chips = ArrayList<TextView>(routes.size)
        var selectedIndex = 0
        routes.forEachIndexed { index, route ->
            val on = route.id == selectedId
            if (on) selectedIndex = index
            val chip = if (reuse) {
                chipRow.getChildAt(index) as TextView
            } else {
                TextView(requireContext()).apply {
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(7), dp(13), dp(7))
                    // 选中透镜由 LensChipTrack 绘制，chip 自身只保留曜石黑 / 纯白字色
                    background = null
                    setOnClickListener { v ->
                        val id = v.tag as? Long ?: return@setOnClickListener
                        if (selectedRouteId == id) return@setOnClickListener
                        selectedRouteId = id
                        persistSelection()
                        render()
                    }
                }
            }
            chip.tag = route.id
            chip.text = chipLabel(route.name, route.singleLapMeters)
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.pf_volt else R.color.pf_text_secondary
                )
            )
            chip.setTypeface(
                null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            if (!reuse) {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                chipRow.addView(chip, lp)
            }
            chips.add(chip)
        }
        chipTrack.bind(chips, selectedIndex, animate = reuse)
        // 让选中的路线透镜完整落在轨道可视区内（两侧各预留 16dp，避免贴边截断）
        val selectedChip = chips.getOrNull(selectedIndex)
        if (selectedChip != null) {
            chipRow.post {
                val viewport = chipScrollView.width
                if (viewport <= 0) return@post
                val trailing = dp(16)
                val current = chipScrollView.scrollX
                val target = when {
                    selectedChip.left < current ->
                        (selectedChip.left - trailing).coerceAtLeast(0)
                    selectedChip.right + trailing > current + viewport ->
                        selectedChip.right + trailing - viewport
                    else -> current
                }
                val maxScroll = (chipRow.width - viewport).coerceAtLeast(0)
                chipScrollView.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
            }
        }
    }

    private fun bindMainCard(route: RunRouteEntity) {
        val schedule = schedules.firstOrNull { it.routeId == route.id }
        // V4.1：示范路线标题统一精简为「400m 标准田径场」，兼容旧库中带「（示范）」「· 示范」的名字，
        // 单行不再出现省略号截断。
        nameText.text = if (route.buildMode == "DEMO") {
            route.name
                .replace(" · 示范", "")
                .replace("（示范）", "")
                .replace("(示范)", "")
                .trim()
        } else {
            route.name
        }
        metaText.text = String.format(
            Locale.US,
            "单圈 %.0f m · %s · %s",
            route.singleLapMeters,
            when (route.buildMode) {
                "ROAD_SNAP" -> "路网贴合"
                "GPX_IMPORT" -> "GPX 导入"
                "ROAM_RECORD" -> "漫游录制"
                "PRESET_TRACK" -> "操场套模"
                "FREE_DRAW" -> "自由手绘"
                "DEMO" -> "示范跑道"
                "SHARE_CODE" -> "口令导入"
                else -> "逐点连线"
            },
            if (route.isClockwise) "顺时针" else "逆时针"
        )
        thumb.setRoute(TrackJsonCodec.decode(route.denseWaypointsJson))
        quickStartBtn.text = String.format(
            Locale.US,
            "开始跑圈 · %.1f km · %d'%02d\"",
            route.lastTargetDistanceKm,
            route.lastTargetPaceSecPerKm / 60,
            route.lastTargetPaceSecPerKm % 60
        )
        scheduleText.text = if (schedule?.isScheduleEnabled == true) {
            String.format(
                Locale.US,
                "定时 %02d:%02d ±%dmin · 已启用",
                schedule.windowStartMinutes / 60,
                schedule.windowStartMinutes % 60,
                schedule.windowJitterMinutes
            )
        } else {
            "定时：未配置（点击设置）"
        }
        quickStartBtn.setOnClickListener { quickStart(route) }
        (quickStartBtn as? com.zcshou.gogogo.run.ui.widget.SpecularButton)
            ?.sonarOnLongPress = true
        shareBtn.setOnClickListener { shareRouteCode(route) }
        mainCard.findViewById<View>(R.id.routeHeader).setOnClickListener {
            openRoutePreview(route)
        }
        tuneBtn.setOnClickListener {
            QuickStartBottomSheet.newInstance(route.id, route.name)
                .show(parentFragmentManager, "quick_start")
        }
        scheduleText.setOnClickListener { showScheduleDialog(route, schedule) }
        menuBtn.setOnClickListener { showRouteMenu(route) }
    }

    private fun openRoutePreview(route: RunRouteEntity) {
        startActivity(
            Intent(requireContext(), RoutePreviewActivity::class.java)
                .putExtra(RoutePreviewActivity.EXTRA_ROUTE_ID, route.id)
        )
    }

    private fun showRouteMenu(route: RunRouteEntity) {
        val popup = PopupMenu(requireContext(), menuBtn)
        popup.menu.add(0, 6, 0, "查看路线")
        popup.menu.add(0, 1, 1, "重命名")
        popup.menu.add(0, 2, 2, "复制一份")
        popup.menu.add(0, 3, 3, "导出 GPX 备份")
        popup.menu.add(0, 5, 4, getString(R.string.route_share_copy, BrandConfig.SHARE_SCHEME))
        popup.menu.add(0, 4, 5, "删除路线")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                6 -> openRoutePreview(route)
                1 -> renameRoute(route)
                2 -> duplicateRoute(route)
                3 -> exportRoute(route)
                4 -> deleteRouteWithUndo(route)
                5 -> copyShareCode(route)
            }
            true
        }
        popup.show()
    }

    private fun renameRoute(route: RunRouteEntity) {
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(route.name)
            setSelection(text.length)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        GlassDialogs.builder(requireContext())
            .setTitle("重命名路线")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.updateRoute(route.copy(name = name))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun duplicateRoute(route: RunRouteEntity) {
        val name = uniqueCopyName(route.name)
        viewLifecycleOwner.lifecycleScope.launch {
            val id = repository.saveRoute(
                route.copy(id = 0, name = name, createdAt = System.currentTimeMillis())
            )
            selectedRouteId = id
            persistSelection()
            toast("已复制为「$name」")
        }
    }

    private fun uniqueCopyName(base: String): String {
        val sibling = routes.count { it.name == "$base 副本" || it.name.startsWith("$base 副本") }
        return if (sibling == 0) "$base 副本" else "$base 副本${sibling + 1}"
    }

    private fun exportRoute(route: RunRouteEntity) {
        pendingExport = route
        val safe = route.name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
        val stamp = SimpleDateFormat("MMdd", Locale.US).format(Date())
        exportGpxLauncher.launch("$safe-$stamp.gpx")
    }

    private fun copyShareCode(route: RunRouteEntity) {
        val code = routeShareCode(route)
        if (code == null) {
            toast("轨迹数据不足，无法生成口令")
            return
        }
        if (copyShareText(route, code)) {
            toast("口令已复制，好友粘贴到「导入口令」即可导入")
        } else {
            toast("口令复制失败")
        }
    }

    /** 路线卡上的显式分享入口：直接拉起系统分享面板，发给微信 / QQ 等任意应用 */
    private fun shareRouteCode(route: RunRouteEntity) {
        val code = routeShareCode(route)
        if (code == null) {
            toast("轨迹数据不足，无法生成口令")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)}跑圈口令 · ${route.name}")
            putExtra(Intent.EXTRA_TEXT, shareMessage(route, code))
        }
        val opened = runCatching {
            startActivity(Intent.createChooser(send, "分享跑圈口令"))
        }.isSuccess
        if (!opened) {
            if (copyShareText(route, code)) {
                toast("没有可分享的应用，口令已复制")
            } else {
                toast("没有可分享的应用，口令复制失败")
            }
        }
    }

    private fun routeShareCode(route: RunRouteEntity): String? {
        val points = TrackJsonCodec.decode(route.denseWaypointsJson)
        if (points.size < 2) return null
        return runCatching { RouteShareCodec.encode(route.name, points, route.isClockwise) }.getOrNull()
    }

    /** 分享文案带一句使用说明，好友复制整段即可被 [RouteShareCodec.decode] 识别 */
    private fun shareMessage(route: RunRouteEntity, code: String): String = buildString {
        val app = getString(R.string.app_name)
        append("【$app】跑圈路线「${route.name}」\n")
        append("复制整段口令，打开${app}首页点「导入口令」粘贴即可导入（不上传服务器）：\n")
        append(code)
    }

    private fun copyShareText(route: RunRouteEntity, code: String): Boolean {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(
            "${BrandConfig.SHARE_SCHEME} 路线口令",
            shareMessage(route, code)
        )
        // 口令内含完整路线坐标（位置历史），API 33+ 标记为敏感内容，避免被剪贴板读取方顺带收割
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle(1).apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching { cm.setPrimaryClip(clip) }.isSuccess
    }

    private fun importShareCode() {
        // 好友口令常见路径是「聊天里复制整段 → 打开 App 导入」，先自动读取剪贴板省一步粘贴。
        val clipCode = readClipboardShareCode()
        val input = EditText(requireContext()).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            hint = "粘贴 ${BrandConfig.SHARE_PREFIX}... 口令"
            if (clipCode != null) {
                setText(clipCode)
                setSelection(text.length)
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 5
        }
        GlassDialogs.builder(requireContext())
            .setTitle("导入跑圈口令")
            .setMessage(
                if (clipCode != null) {
                    "已读取剪贴板中的口令，点「导入路线」即可。口令只包含路线坐标与名称，不上传服务器"
                } else {
                    "直接粘贴好友发来的整段聊天内容即可。口令只包含路线坐标与名称，不上传服务器"
                }
            )
            .setView(input)
            .setPositiveButton("导入路线") { _, _ ->
                val shared = RouteShareCodec.decode(input.text.toString())
                if (shared == null) {
                    toast("口令无效或已损坏，请粘贴完整口令")
                    return@setPositiveButton
                }
                if (shared.points.size > 2000) {
                    toast("路线点数过多，无法导入")
                    return@setPositiveButton
                }
                val lap = TrackMathUtils.cumulativeDistances(shared.points).last()
                if (lap < 20.0) {
                    toast("路线过短，无法导入")
                    return@setPositiveButton
                }
                val name = uniqueImportName(shared.name)
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = repository.saveRoute(
                        RunRouteEntity(
                            name = name,
                            buildMode = "SHARE_CODE",
                            singleLapMeters = lap,
                            isClosedLoop = true,
                            isClockwise = shared.clockwise,
                            denseWaypointsJson = TrackJsonCodec.encode(shared.points),
                            anchorNodesJson = "[]",
                            baseAltitudeMeters = 50.0
                        )
                    )
                    selectedRouteId = id
                    persistSelection()
                    toast("已导入「$name」· ${String.format(Locale.US, "%.0f m", lap)}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readClipboardShareCode(): String? = runCatching {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            ?: return@runCatching null
        RouteShareCodec.extractCode(text)
    }.getOrNull()

    private fun uniqueImportName(base: String): String {
        val name = base.take(24)
        if (routes.none { it.name == name }) return name
        return "$name（口令）"
    }

    private fun deleteRouteWithUndo(route: RunRouteEntity) {
        val removedSchedules = schedules.filter { it.routeId == route.id }
        viewLifecycleOwner.lifecycleScope.launch {
            repository.deleteRoute(route)
            removedSchedules.forEach { repository.deleteSchedule(it) }
            if (selectedRouteId == route.id) {
                selectedRouteId = 0L
                persistSelection()
            }
            Snackbar.make(requireView(), "已删除「${route.name}」", 5000)
                .setAction("撤销") {
                    viewLifecycleOwner.lifecycleScope.launch {
                        // saveRoute 是重新插入、会生成新 id：撤销时必须把关联的
                        // 定时计划回写到新 routeId，否则计划永久指向已删除的路线
                        val restoredId = repository.saveRoute(route)
                        removedSchedules.forEach { repository.saveSchedule(it.copy(routeId = restoredId)) }
                        selectedRouteId = restoredId
                        persistSelection()
                        toast("已恢复「${route.name}」")
                    }
                }
                .show()
        }
    }

    private fun persistSelection() {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SELECTED, selectedRouteId).apply()
    }

    private fun formatLap(meters: Double): String =
        if (meters >= 1000) String.format(Locale.US, "%.1fkm", meters / 1000.0)
        else String.format(Locale.US, "%.0fm", meters)

    /**
     * 胶囊文案去重：
     * 历史路线名可能已经包含「· 317m」，直接再拼一次会变成「317m · 317m」。
     * 先剥掉名称末尾重复的周长段，再决定是否补一次；名称里已经出现周长时不再追加。
     */
    private fun chipLabel(name: String, singleLapMeters: Double): String {
        val lap = formatLap(singleLapMeters)
        var base = name.trim()
        val suffix = " · $lap"
        while (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trim()
        }
        if (base.isEmpty()) return lap
        return if (base.contains(lap)) base else "$base · $lap"
    }

    private fun openEditor(mode: String) {
        startActivity(
            Intent(requireContext(), RouteEditorActivity::class.java)
                .putExtra(RouteEditorActivity.EXTRA_INITIAL_MODE, mode)
        )
    }

    /** 1 步直达：用路线记忆参数直接发车，并回写为最近一次参数 */
    private fun quickStart(route: RunRouteEntity) {
        val host = activity as? RunHost ?: return
        host.startRun(
            route = route,
            targetKm = route.lastTargetDistanceKm,
            paceSecPerKm = route.lastTargetPaceSecPerKm,
            laneDrift = route.lastSmartJitterEnabled,
            cooldown = true
        )
    }

    private fun showScheduleDialog(route: RunRouteEntity, schedule: RunScheduleEntity?) {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val switchEnable = view.findViewById<android.widget.Switch>(R.id.swEnable)
        val switchDaily = view.findViewById<android.widget.Switch>(R.id.swDaily)
        val timePicker = view.findViewById<android.widget.TimePicker>(R.id.timePicker)
        val editJitter = view.findViewById<android.widget.EditText>(R.id.etJitter)
        val switchWifi = view.findViewById<android.widget.Switch>(R.id.swWifiIsolate)
        val tvLinkedApp = view.findViewById<android.widget.TextView>(R.id.tvLinkedApp)
        var linkedPkg = schedule?.linkedTargetAppPkg
        tvLinkedApp.text = if (linkedPkg == null) {
            "关联跑步 App：未选择（点击选择）"
        } else {
            "关联跑步 App：${appLabel(linkedPkg)}"
        }
        tvLinkedApp.setOnClickListener {
            showAppPicker(linkedPkg) { picked ->
                linkedPkg = picked
                tvLinkedApp.text = if (picked == null) {
                    "关联跑步 App：未选择（点击选择）"
                } else {
                    "关联跑步 App：${appLabel(picked)}"
                }
            }
        }

        val startMinutes = schedule?.windowStartMinutes ?: 1110
        switchEnable.isChecked = schedule?.isScheduleEnabled == true
        switchDaily.isChecked = schedule == null || schedule.repeatDaysMask == 0
        timePicker.setIs24HourView(true)
        timePicker.hour = startMinutes / 60
        timePicker.minute = startMinutes % 60
        editJitter.setText((schedule?.windowJitterMinutes ?: 8).toString())
        switchWifi.isChecked = schedule?.autoIsolateWifi ?: true

        GlassDialogs.builder(context)
            .setTitle("定时开跑 · ${route.name}")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val jitter = editJitter.text.toString().toIntOrNull()?.coerceIn(0, 60) ?: 8
                val startMin = timePicker.hour * 60 + timePicker.minute
                val mask = if (switchDaily.isChecked) 0 else (schedule?.repeatDaysMask ?: 0b0111110)
                val entity = (schedule ?: RunScheduleEntity(
                    routeId = route.id,
                    title = route.name,
                    targetDistanceKm = route.lastTargetDistanceKm
                )).copy(
                    isScheduleEnabled = switchEnable.isChecked,
                    windowStartMinutes = startMin,
                    windowJitterMinutes = jitter,
                    repeatDaysMask = mask,
                    autoIsolateWifi = switchWifi.isChecked,
                    linkedTargetAppPkg = linkedPkg
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = if (entity.id == 0L) {
                        repository.saveSchedule(entity)
                    } else {
                        repository.updateSchedule(entity)
                        entity.id
                    }
                    val saved = entity.copy(id = id)
                    val next = com.zcshou.gogogo.run.guard.RunScheduleAlarmManager
                        .scheduleNext(context, saved)
                    val message = when {
                        next == null -> "定时已关闭"
                        !com.zcshou.gogogo.run.guard.EnvGuardManager.canScheduleExactAlarms(context) ->
                            "已设定（近似）：${formatTime(next)}"
                        else -> "已设定：${formatTime(next)} 开跑"
                    }
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(context, message, long = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 列出可启动的第三方 App 供关联（排除自身与系统组件） */
    private fun showAppPicker(current: String?, onPicked: (String?) -> Unit) {
        val pm = requireContext().packageManager
        val self = requireContext().packageName
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second }
            .toList()
        val labels = (listOf("不关联") + apps.map { it.second }).toTypedArray()
        val checked = apps.indexOfFirst { it.first == current } + 1
        GlassDialogs.builder(requireContext())
            .setTitle("关联跑步 App（开跑时自动拉起）")
            .setSingleChoiceItems(labels, if (checked > 0) checked else 0) { dialog, which ->
                onPicked(if (which == 0) null else apps[which - 1].first)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(java.util.Date(millis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(requireContext(), message)
}
