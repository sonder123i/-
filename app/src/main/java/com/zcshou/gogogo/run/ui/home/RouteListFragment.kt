package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.google.android.material.snackbar.Snackbar
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RouteShareCodec
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.guard.RunShortcutManager
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.ui.editor.RouteEditorActivity
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.overlay.RouteThumbnailView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tab 1：步频流 —— 本周概览 + 横向路线切换条 + 当前主路线卡 + 路线工坊。
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
    private lateinit var chipRow: LinearLayout
    private lateinit var weekKm: TextView
    private lateinit var weekCount: TextView
    private lateinit var setupBanner: TextView
    private lateinit var routeCountLabel: TextView

    private lateinit var thumb: RouteThumbnailView
    private lateinit var nameText: TextView
    private lateinit var metaText: TextView
    private lateinit var scheduleText: TextView
    private lateinit var quickStartBtn: TextView
    private lateinit var tuneBtn: TextView
    private lateinit var menuBtn: TextView

    private var routes: List<RunRouteEntity> = emptyList()
    private var schedules: List<RunScheduleEntity> = emptyList()
    private var selectedRouteId = 0L
    private var pendingExport: RunRouteEntity? = null
    private var lastRunPhase: RunPhase? = null

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
        chipRow = view.findViewById(R.id.routeChipRow)
        weekKm = view.findViewById(R.id.weekKm)
        weekCount = view.findViewById(R.id.weekCount)
        setupBanner = view.findViewById(R.id.setupBanner)
        routeCountLabel = view.findViewById(R.id.routeCountLabel)

        thumb = mainCard.findViewById(R.id.routeThumb)
        nameText = mainCard.findViewById(R.id.routeName)
        metaText = mainCard.findViewById(R.id.routeMeta)
        scheduleText = mainCard.findViewById(R.id.routeSchedule)
        quickStartBtn = mainCard.findViewById(R.id.btnQuickStart)
        tuneBtn = mainCard.findViewById(R.id.btnTune)
        menuBtn = mainCard.findViewById(R.id.routeMenu)

        selectedRouteId = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED, 0L)

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
        view.findViewById<TextView>(R.id.btnImportShare).setOnClickListener { importShareCode() }
        setupBanner.setOnClickListener {
            (activity as? RunHomeActivity)?.openSetupWizard()
        }
        view.findViewById<View>(R.id.weekCard).setOnClickListener {
            HistoryReportDialog.show(requireContext(), repository)
        }
        refreshSetupBanner()

        viewLifecycleOwner.lifecycleScope.launch {
            repository.seedDemoRouteIfNeeded()
        }

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
                            RunPhase.PRE_START_SETTLE -> thumb.startSonar(5000)
                            else -> thumb.stopSonar()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupBanner()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshSetupBanner()
    }

    fun refreshSetupBanner() {
        if (!::setupBanner.isInitialized) return
        val state = SetupGuard.check(requireContext())
        setupBanner.visibility = if (state.ready) View.GONE else View.VISIBLE
        if (!state.ready) {
            setupBanner.text = "首次使用需完成 ${state.missing.size} 项基础授权 · 立即配置 >"
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
        routeCountLabel.text = "${routes.size} 条路线"
        emptyView.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
        mainCard.visibility = if (selected == null) View.GONE else View.VISIBLE
        chipScroll.visibility = if (routes.isEmpty()) View.GONE else View.VISIBLE
        renderChips(selected?.id ?: 0L)
        selected?.let { bindMainCard(it) }
    }

    private fun renderChips(selectedId: Long) {
        chipRow.removeAllViews()
        routes.forEach { route ->
            val on = route.id == selectedId
            val chip = TextView(requireContext()).apply {
                text = "${route.name} · ${formatLap(route.singleLapMeters)}"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (on) R.color.pf_volt else R.color.pf_text_secondary
                    )
                )
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (on) R.drawable.bg_tab_selected else R.drawable.bg_secondary_button
                )
                setOnClickListener {
                    if (selectedRouteId == route.id) return@setOnClickListener
                    selectedRouteId = route.id
                    persistSelection()
                    render()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            chipRow.addView(chip, lp)
        }
    }

    private fun bindMainCard(route: RunRouteEntity) {
        val schedule = schedules.firstOrNull { it.routeId == route.id }
        nameText.text = route.name
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
            "▶ %.1fkm · %d'%02d\"",
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
        tuneBtn.setOnClickListener {
            QuickStartBottomSheet.newInstance(route.id, route.name)
                .show(parentFragmentManager, "quick_start")
        }
        scheduleText.setOnClickListener { showScheduleDialog(route, schedule) }
        menuBtn.setOnClickListener { showRouteMenu(route) }
    }

    private fun showRouteMenu(route: RunRouteEntity) {
        val popup = PopupMenu(requireContext(), menuBtn)
        popup.menu.add(0, 1, 0, "重命名")
        popup.menu.add(0, 2, 1, "复制一份")
        popup.menu.add(0, 3, 2, "导出 GPX 备份")
        popup.menu.add(0, 5, 3, "复制 DEEPRUN:// 分享口令")
        popup.menu.add(0, 4, 4, "删除路线")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
            setText(route.name)
            setSelection(text.length)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(requireContext())
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
        val points = TrackJsonCodec.decode(route.denseWaypointsJson)
        if (points.size < 2) {
            toast("轨迹数据不足，无法生成口令")
            return
        }
        runCatching {
            val code = RouteShareCodec.encode(route.name, points, route.isClockwise)
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("DEEPRUN 路线口令", code)
            // 口令内含完整路线坐标（位置历史），API 33+ 标记为敏感内容，避免被剪贴板读取方顺带收割
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle(1).apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            toast("口令已复制，发给室友即可零服务器导入")
        }.onFailure { toast("口令生成失败") }
    }

    private fun importShareCode() {
        val input = EditText(requireContext()).apply {
            hint = "粘贴 DEEPRUN://... 口令"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 4
        }
        AlertDialog.Builder(requireContext())
            .setTitle("从分享口令导入")
            .setMessage("口令仅包含路线坐标与名称，不上传任何服务器")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val shared = RouteShareCodec.decode(input.text.toString())
                if (shared == null) {
                    toast("口令无效或已损坏")
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
                    toast("已导入口令路线：${String.format(Locale.US, "%.0f m", lap)}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

        AlertDialog.Builder(context)
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
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
        AlertDialog.Builder(requireContext())
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
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}
