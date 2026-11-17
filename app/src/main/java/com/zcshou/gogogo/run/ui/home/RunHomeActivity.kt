package com.zcshou.gogogo.run.ui.home

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.guard.EnvGuardManager
import com.zcshou.gogogo.run.guard.RunScheduleNotifier
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.service.RunSessionService
import com.zcshou.gogogo.run.service.RoamService
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import com.zcshou.gogogo.run.ui.overlay.FloatingCapsuleView
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.service.ServiceGo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * 新版首页：我的跑圈 / 环境体检双 Tab。
 * 引擎托管在 RunSessionService（定时/后台可跑），本页只发命令、订阅状态、挂载胶囊。
 */
class RunHomeActivity : AppCompatActivity(), RunHost {

    companion object {
        private const val DETOUR_METERS = 150.0
        private const val REQ_NOTIFICATIONS = 1001
        private const val STATE_TAB = "selected_tab"
        private const val TAB_AUTO = "tab_auto"
        private const val TAB_ROAM = "tab_roam"
        private const val TAB_ENV = "tab_env"
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
        private const val ACTION_QUICK_RUN = "default_run"
        private const val ACTION_ROAM = "roam"
        private const val ACTION_STOP = "stop"
        private val DETOUR_OPTIONS = listOf(
            "东侧 150m" to 90.0,
            "南侧 150m" to 180.0,
            "西侧 150m" to 270.0,
            "北侧 150m" to 0.0
        )
    }

    private lateinit var repository: RunRepository
    private lateinit var homeStatus: TextView
    private lateinit var tabRoutes: TextView
    private lateinit var tabRoam: TextView
    private lateinit var tabEnv: TextView
    private var selectedTab = 0
    private var lastState = RunRuntimeState()

    private var latestState = RunRuntimeState()
    private var pendingLaunchAfterWifi: (() -> Unit)? = null

    private var capsule: FloatingCapsuleView? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var pendingStartAfterSetup: (() -> Unit)? = null

    private val setupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                pendingStartAfterSetup?.invoke()
            }
            pendingStartAfterSetup = null
            refreshSetupBanner()
        }

    fun openSetupWizard(onReady: (() -> Unit)? = null) {
        if (isFinishing) return
        pendingStartAfterSetup = onReady
        runCatching {
            setupLauncher.launch(Intent(this, SetupWizardActivity::class.java))
        }.onFailure {
            pendingStartAfterSetup = null
            toast("无法打开配置向导")
        }
    }

    private fun refreshSetupBanner() {
        (supportFragmentManager.findFragmentByTag(TAB_AUTO) as? RouteListFragment)
            ?.refreshSetupBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        // 进入首页即代表已通过欢迎页，之后不再重复展示
        getSharedPreferences("KEY_ACCEPT_AGREEMENT", MODE_PRIVATE).edit()
            .putBoolean("KEY_ONBOARDED", true)
            .apply()
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_run_home)
        ThemeHelper.padForSystemBars(findViewById(R.id.homeRoot))
        repository = RunRepository(applicationContext)
        homeStatus = findViewById(R.id.homeStatus)
        tabRoutes = findViewById(R.id.tabRoutes)
        tabRoam = findViewById(R.id.tabRoam)
        tabEnv = findViewById(R.id.tabEnv)

        tabRoutes.setOnClickListener { selectTab(0) }
        tabRoam.setOnClickListener { selectTab(1) }
        tabEnv.setOnClickListener { selectTab(2) }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, RouteListFragment(), TAB_AUTO)
                .add(R.id.fragmentContainer, FreeRoamFragment(), TAB_ROAM)
                .add(R.id.fragmentContainer, EnvCheckFragment(), TAB_ENV)
                .commitNow()
        }
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: 0)
        if (intent.getBooleanExtra(RoamService.EXTRA_OPEN_ROAM, false)) {
            intent.removeExtra(RoamService.EXTRA_OPEN_ROAM)
            selectTab(1)
        }

        observeSession()
        handleAutoStartIntent(intent)
        handleShortcutIntent(intent)
        handleQuickActionShortcut(intent)
        maybeShowSetupGuide()
    }

    private fun maybeShowSetupGuide() {
        if (SetupGuard.isReady(this)) return
        val prefs = getSharedPreferences("pf_guide", MODE_PRIVATE)
        if (prefs.getBoolean("auto_guide_shown", false)) return
        prefs.edit().putBoolean("auto_guide_shown", true).apply()
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!SetupGuard.isReady(this)) {
                SetupWizardBottomSheet.newInstance()
                    .show(supportFragmentManager, "setup_guide")
            }
        }, 700)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(RoamService.EXTRA_OPEN_ROAM, false)) {
            intent.removeExtra(RoamService.EXTRA_OPEN_ROAM)
            selectTab(1)
        }
        handleAutoStartIntent(intent)
        handleShortcutIntent(intent)
        handleQuickActionShortcut(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            capsule?.collapseIfExpanded() == true
        ) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingLaunchAfterWifi
        if (pending != null && !EnvGuardManager.isWifiActivelyConnected(this)) {
            pendingLaunchAfterWifi = null
            pending()
        }
    }

    override fun onDestroy() {
        hideCapsule()
        super.onDestroy()
    }

    // ---------------- RunHost ----------------

    override fun startRun(
        route: RunRouteEntity,
        targetKm: Float,
        paceSecPerKm: Int,
        laneDrift: Boolean,
        cooldown: Boolean,
        reverseDirection: Boolean
    ) {
        if (!SetupGuard.isReady(this)) {
            openSetupWizard {
                startRun(route, targetKm, paceSecPerKm, laneDrift, cooldown, reverseDirection)
            }
            return
        }
        val launch: () -> Unit = {
            ensureNotificationPermission()
            RoamService.stop(this)
            ContextCompat.startForegroundService(
                this,
                RunSessionService.startIntent(
                    this, route.id, targetKm, paceSecPerKm, laneDrift, cooldown,
                    linkedPkg = com.zcshou.gogogo.run.data.prefs.RunPrefs.targetApp(this),
                    reverseDirection = reverseDirection
                )
            )
            // 记忆本次参数，支撑下次 1 步直达
            lifecycleScope.launch {
                runCatching {
                    repository.updateRoute(
                        route.copy(
                            lastTargetDistanceKm = targetKm,
                            lastTargetPaceSecPerKm = paceSecPerKm,
                            lastSmartJitterEnabled = laneDrift
                        )
                    )
                }
            }
            toast("已发车")
            Unit
        }
        if (EnvGuardManager.isWifiActivelyConnected(this)) {
            AlertDialog.Builder(this)
                .setTitle("当前已连接 Wi-Fi")
                .setMessage("Wi-Fi 可能让定位闪回真实位置，建议暂时关闭后再开跑。")
                .setPositiveButton("去关闭 Wi-Fi") { _, _ ->
                    pendingLaunchAfterWifi = launch
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("忽略并直接开跑") { _, _ -> launch() }
                .setOnCancelListener { pendingLaunchAfterWifi = null }
                .show()
        } else {
            launch()
        }
    }

    override fun pauseResume() {
        sendCommand(RunSessionService.ACTION_TOGGLE_PAUSE)
    }

    override fun pickDetour() {
        val position = latestState.position
        if (position == null) {
            toast("尚未获得当前位置")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_detour_map, null)
        val mapView = view.findViewById<com.baidu.mapapi.map.MapView>(R.id.detourMap)
        val map = mapView.map
        val bd = com.zcshou.gogogo.run.engine.CoordTransformUtil.wgs84ToBd09(position.lat, position.lng)
        val center = com.baidu.mapapi.model.LatLng(bd.lat, bd.lng)
        runCatching {
            map.setMapStatus(com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngZoom(center, 18f))
            map.addOverlay(com.baidu.mapapi.map.MarkerOptions().position(center).title("当前位置"))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("临时插点 · 300m 范围")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        map.setOnMapClickListener(object : com.baidu.mapapi.map.BaiduMap.OnMapClickListener {
            override fun onMapClick(tapped: com.baidu.mapapi.model.LatLng) {
                val wgs = com.zcshou.gogogo.run.engine.CoordTransformUtil
                    .bd09ToWgs84(tapped.latitude, tapped.longitude)
                ContextCompat.startForegroundService(
                    this@RunHomeActivity,
                    RunSessionService.detourIntent(this@RunHomeActivity, wgs.lat, wgs.lng)
                )
                toast("已插入绕路点，立即出发")
                dialog.dismiss()
            }

            override fun onMapPoiClick(poi: com.baidu.mapapi.map.MapPoi) = Unit
        })
        dialog.setOnShowListener { runCatching { mapView.onResume() } }
        dialog.setOnDismissListener {
            runCatching { mapView.onPause() }
            runCatching { mapView.onDestroy() }
        }
        dialog.show()
    }

    override fun finishEarly() {
        sendCommand(RunSessionService.ACTION_FINISH_EARLY)
    }

    override fun abortRun() {
        sendCommand(RunSessionService.ACTION_ABORT)
    }

    override fun isServiceReady(): Boolean = RunSessionHolder.sessionActive

    override fun currentState(): RunRuntimeState = latestState

    override fun relayoutOverlay() {
        val view = capsule ?: return
        val wm = windowManager ?: return
        val params = overlayParams ?: return
        view.postDelayed({
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            runCatching { wm.updateViewLayout(view, params) }
            view.postDelayed({
                params.x = params.x.coerceIn(0, (screenWidth() - view.width).coerceAtLeast(0))
                params.y = params.y.coerceIn(
                    statusBarHeight(),
                    (screenHeight() - view.height - navBarHeight()).coerceAtLeast(statusBarHeight())
                )
                runCatching { wm.updateViewLayout(view, params) }
            }, 40)
        }, 60)
    }

    override fun stopSimulation() {
        sendCommand(RunSessionService.ACTION_ABORT)
        FreeRoamController.release()
        RoamService.stop(this)
        runCatching { stopService(Intent(this, ServiceGo::class.java)) }
        hideCapsule()
        notifySimulationStopped()
        toast("模拟定位已停止，真实 GPS 已恢复")
    }

    override fun beginManualOverride() {
        sendCommand(RunSessionService.ACTION_MANUAL_TAKEOVER)
    }

    override fun endManualOverride() {
        sendCommand(RunSessionService.ACTION_MANUAL_RELEASE)
    }

    override fun setOverlayHidden(hidden: Boolean) {
        val view = capsule ?: return
        view.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        if (!hidden) relayoutOverlay()
    }

    private fun notifySimulationStopped() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val channelId = "pf_notice"
            manager.createNotificationChannel(
                NotificationChannel(channelId, "步频流提示", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("模拟定位已停止")
                .setContentText("真实 GPS 已恢复")
                .setAutoCancel(true)
                .build()
            manager.notify(99, notification)
        }
    }

    // ---------------- 内部 ----------------

    private fun sendCommand(action: String) {
        if (!RunSessionHolder.sessionActive) return
        ContextCompat.startForegroundService(this, RunSessionService.commandIntent(this, action))
    }

    private fun observeSession() {
        lifecycleScope.launch {
            RunSessionHolder.state.collect { state ->
                latestState = state
                renderHeader(state)
                renderCapsule(state)
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    /** 桌面快捷方式 1 步开跑 */
    private fun handleShortcutIntent(intent: Intent?) {
        val routeId = intent?.getLongExtra(
            com.zcshou.gogogo.run.guard.RunShortcutManager.EXTRA_SHORTCUT_ROUTE_ID, -1
        ) ?: return
        if (routeId <= 0) return
        intent.removeExtra(com.zcshou.gogogo.run.guard.RunShortcutManager.EXTRA_SHORTCUT_ROUTE_ID)
        lifecycleScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            startRun(
                route,
                route.lastTargetDistanceKm,
                route.lastTargetPaceSecPerKm,
                route.lastSmartJitterEnabled,
                true
            )
        }
    }

    /** 桌面固定快捷方式：一键发车 / 自由漫游 / 停止模拟 */
    private fun handleQuickActionShortcut(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_SHORTCUT_ACTION) ?: return
        intent.removeExtra(EXTRA_SHORTCUT_ACTION)
        when (action) {
            ACTION_ROAM -> selectTab(1)
            ACTION_STOP -> stopSimulation()
            ACTION_QUICK_RUN -> lifecycleScope.launch {
                val route = repository.observeRoutes().firstOrNull()?.firstOrNull()
                if (route == null) {
                    toast("请先创建一条跑圈路线")
                    return@launch
                }
                startRun(
                    route,
                    route.lastTargetDistanceKm,
                    route.lastTargetPaceSecPerKm,
                    route.lastSmartJitterEnabled,
                    true
                )
            }
        }
    }

    /** 定时降级通知点按进入时自动开跑 */
    private fun handleAutoStartIntent(intent: Intent?) {
        val routeId = intent?.getLongExtra(RunScheduleNotifier.EXTRA_AUTO_ROUTE_ID, -1) ?: return
        if (routeId <= 0) return
        val km = intent.getFloatExtra(RunScheduleNotifier.EXTRA_AUTO_KM, 3.0f)
        val pace = intent.getIntExtra(RunScheduleNotifier.EXTRA_AUTO_PACE, 340)
        val linkedPkg = intent.getStringExtra(RunScheduleNotifier.EXTRA_AUTO_LINKED_PKG)
        intent.removeExtra(RunScheduleNotifier.EXTRA_AUTO_ROUTE_ID)
        lifecycleScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            if (!SetupGuard.isReady(this@RunHomeActivity)) {
                openSetupWizard()
                return@launch
            }
            if (linkedPkg != null) {
                // 定时任务绑定了跑步 App：直接带包名下发给服务
                RoamService.stop(this@RunHomeActivity)
                ContextCompat.startForegroundService(
                    this@RunHomeActivity,
                    RunSessionService.startIntent(
                        this@RunHomeActivity, route.id, km, pace, true, true, linkedPkg
                    )
                )
                toast("已发车，正在拉起关联 App")
            } else {
                startRun(route, km, pace, true, true)
            }
        }
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        val tags = arrayOf(TAB_AUTO, TAB_ROAM, TAB_ENV)
        supportFragmentManager.beginTransaction().apply {
            tags.forEachIndexed { i, tag ->
                val fragment = supportFragmentManager.findFragmentByTag(tag)
                    ?: return@forEachIndexed
                if (i == index) show(fragment) else hide(fragment)
            }
            commit()
        }
        listOf(tabRoutes, tabRoam, tabEnv).forEachIndexed { i, tab ->
            val selected = i == index
            tab.setTextColor(
                getColor(if (selected) R.color.pf_volt else R.color.pf_text_secondary)
            )
            tab.setTypeface(
                null,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else 0)
        }
        renderHeader(lastState)
    }

    private fun renderHeader(state: RunRuntimeState) {
        lastState = state
        val idle = state.phase == RunPhase.IDLE || state.phase == RunPhase.FINISHED
        homeStatus.visibility =
            if (idle || selectedTab == 1) android.view.View.GONE else android.view.View.VISIBLE
        homeStatus.text = when (state.phase) {
            RunPhase.IDLE -> "待命 · 选择路线后一键开跑"
            RunPhase.ENV_GUARD -> "发车前体检…"
            RunPhase.PRE_START_SETTLE -> String.format(
                java.util.Locale.US,
                "预热中 · %d s 后起跑 · 请切到跑步 App 准备",
                state.settleRemainSec
            )
            RunPhase.FINISHED -> "已结束 · ${state.message.orEmpty()}"
            else -> String.format(
                java.util.Locale.US,
                "%s · 已跑 %.2f / %.2f km · 配速 %d'%02d\"",
                when (state.phase) {
                    RunPhase.RUNNING -> "跑圈中"
                    RunPhase.PAUSED -> "已暂停"
                    RunPhase.DETOUR_POINT -> "绕路打卡"
                    RunPhase.COOLDOWN_WALK -> "慢走收尾"
                    else -> state.phase.name
                },
                state.accumulatedKm,
                state.targetKm,
                state.paceSecPerKm / 60,
                state.paceSecPerKm % 60
            )
        }
    }

    private fun renderCapsule(state: RunRuntimeState) {
        val active = state.phase == RunPhase.ENV_GUARD ||
            state.phase == RunPhase.PRE_START_SETTLE ||
            state.phase == RunPhase.RUNNING ||
            state.phase == RunPhase.DETOUR_POINT ||
            state.phase == RunPhase.PAUSED ||
            state.phase == RunPhase.COOLDOWN_WALK
        if (active) showCapsule() else hideCapsule()
        capsule?.update(state)
    }

    private fun showCapsule() {
        if (capsule != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = FloatingCapsuleView(ThemeHelper.themedContext(this), this)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("pf_overlay", Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            x = dp(16)
            y = prefs.getInt("y", dp(140))
                .coerceIn(statusBarHeight(), screenHeight() - dp(120))
        }
        view.dragHandler = { dx, dy, end ->
            params.x = (params.x + dx).coerceIn(0, (screenWidth() - view.width).coerceAtLeast(0))
            params.y = (params.y + dy).coerceIn(
                statusBarHeight(),
                (screenHeight() - view.height - navBarHeight()).coerceAtLeast(statusBarHeight())
            )
            runCatching { wm.updateViewLayout(view, params) }
            if (end) snapToEdge(view, wm, params)
        }
        // 悬浮窗禁用跨窗模糊：Android 12+ 会为 blurBehindRadius 创建全屏 Dim Layer，
        // 输入分发器据此把窗外触摸全部判为 untrusted 丢弃，导致下层页面无法操作。
        view.setCrossWindowBlurEnabled(false)
        runCatching { wm.addView(view, params) }.onSuccess {
            capsule = view
            windowManager = wm
            overlayParams = params
            view.post {
                if (prefs.getInt("side", 1) == 1 && params.x < screenWidth() / 2) {
                    params.x = (screenWidth() - view.width - dp(8)).coerceAtLeast(dp(8))
                    runCatching { wm.updateViewLayout(view, params) }
                }
                relayoutOverlay()
            }
        }
    }

    /** 松手后弹簧吸附到最近左右边缘，并记住纵向高度 */
    private fun snapToEdge(
        view: android.view.View,
        wm: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        OverlayGlassStyle.springSnapToEdgeX(view, wm, params) {
            val side = if (params.x + view.width / 2 < screenWidth() / 2) 0 else 1
            getSharedPreferences("pf_overlay", Context.MODE_PRIVATE).edit()
                .putInt("side", side)
                .putInt("y", params.y)
                .apply()
        }
    }

    private fun hideCapsule() {
        val view = capsule ?: return
        OverlayGlassStyle.cancelSnap(view)
        runCatching { windowManager?.removeView(view) }
        capsule = null
        windowManager = null
        overlayParams = null
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int =
        resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(24)

    private fun navBarHeight(): Int =
        resources.getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(48)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}