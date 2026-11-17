package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.model.LatLng as BdLatLng
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.data.prefs.RunPrefs
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.CoordTransformUtil
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.toWgsJson
import com.zcshou.gogogo.run.guard.RealLocationSampler
import com.zcshou.gogogo.run.service.RoamService
import com.zcshou.gogogo.run.service.RunSessionHolder
import com.zcshou.gogogo.run.ui.editor.RouteSources
import com.zcshou.gogogo.run.ui.overlay.FiberPositionDot
import com.zcshou.gogogo.run.ui.overlay.FiberTrail
import com.zcshou.gogogo.run.ui.theme.MapStyleHelper
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 2：自由漫游 —— 全屏地图、搜索、长按空降、荧光尾迹。
 */
class FreeRoamFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var baiduMap: BaiduMap
    private lateinit var coordText: TextView
    private lateinit var hint: TextView
    private lateinit var targetBar: LinearLayout
    private lateinit var targetText: TextView
    private lateinit var searchInput: EditText
    private lateinit var recordButton: TextView
    private lateinit var followChip: TextView
    private lateinit var console: LinearLayout
    private lateinit var consoleChip: TextView
    private lateinit var favScroll: View
    private lateinit var favRow: LinearLayout
    private lateinit var smoothSwitch: Switch
    private lateinit var repository: RunRepository

    private var autoLocated = false

    private val suggestionSearch by lazy { SuggestionSearch.newInstance() }
    private var targetMarker: Marker? = null
    private var trailOverlay: FiberTrail? = null
    private var positionMarker: Marker? = null
    private var positionIcon: BitmapDescriptor? = null
    private var pendingTarget: TrackMathUtils.LatLng? = null
    private var lastTrailSize = -1
    private var centeredOnce = false

    private var followEnabled = true
    private var lastFollowAt = 0L
    private var lastHudPos: TrackMathUtils.LatLng? = null
    private var roamMeters = 0.0
    private var roamStartedAt = 0L

    private var recording = false
    private var recordStart = 0L
    private var lastRecordLabel = 0L
    private val recorded = mutableListOf<TrackMathUtils.LatLng>()
    private var lastRecordedPoint: TrackMathUtils.LatLng? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locateReal() else toast("未授权定位权限")
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_free_roam, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mapView = view.findViewById(R.id.roamMap)
        baiduMap = mapView.map
        MapStyleHelper.applyForTheme(mapView)
        coordText = view.findViewById(R.id.roamCoord)
        hint = view.findViewById(R.id.roamHint)
        targetBar = view.findViewById(R.id.roamTargetBar)
        targetText = view.findViewById(R.id.roamTargetText)
        searchInput = view.findViewById(R.id.roamSearchInput)
        recordButton = view.findViewById(R.id.btnRoamRecord)
        followChip = view.findViewById(R.id.btnFollow)
        console = view.findViewById(R.id.roamConsole)
        consoleChip = view.findViewById(R.id.roamConsoleChip)
        favScroll = view.findViewById(R.id.roamFavScroll)
        favRow = view.findViewById(R.id.roamFavRow)
        smoothSwitch = view.findViewById(R.id.switchSmoothWalk)
        repository = RunRepository(requireContext())
        positionIcon = FiberPositionDot.icon(requireContext())

        mapView.showZoomControls(false)

        baiduMap.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
            override fun onMapClick(point: BdLatLng) = dropTarget(point)
            override fun onMapPoiClick(poi: com.baidu.mapapi.map.MapPoi) = dropTarget(poi.position)
        })
        baiduMap.setOnMapLongClickListener(object : BaiduMap.OnMapLongClickListener {
            override fun onMapLongClick(point: BdLatLng) = dropTarget(point)
        })
        baiduMap.setOnMapTouchListener { event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && followEnabled) {
                followEnabled = false
                updateFollowChip()
            }
        }

        view.findViewById<TextView>(R.id.btnRoamTeleport).setOnClickListener { confirmTeleport() }
        view.findViewById<TextView>(R.id.btnRoamCancel).setOnClickListener { clearTarget() }
        view.findViewById<TextView>(R.id.btnRoamSearch).setOnClickListener { doSearch() }
        view.findViewById<TextView>(R.id.btnRoamLocate).setOnClickListener { locateReal() }
        view.findViewById<TextView>(R.id.btnLocateMe).setOnClickListener { locateReal() }
        followChip.setOnClickListener {
            followEnabled = !followEnabled
            updateFollowChip()
            if (followEnabled) {
                FreeRoamController.state.value.position?.let { followCamera(it, force = true) }
            }
        }
        hint.setOnClickListener {
            RoamService.start(requireContext())
            toast("悬浮摇杆已显示")
        }
        recordButton.setOnClickListener { if (recording) stopRecording() else startRecording() }
        view.findViewById<TextView>(R.id.btnRoamStopSim).setOnClickListener {
            (activity as? RunHost)?.stopSimulation()
            toast("模拟定位已停止，真实 GPS 已恢复")
        }
        view.findViewById<TextView>(R.id.btnRoamCollapsePanel).setOnClickListener { collapseConsole() }
        view.findViewById<TextView>(R.id.btnRoamConsoleCollapse).setOnClickListener { collapseConsole() }
        consoleChip.setOnClickListener { expandConsole() }
        view.findViewById<TextView>(R.id.btnRoamStar).setOnClickListener { saveFavorite() }
        smoothSwitch.isChecked = RunPrefs.isSmoothWalkEnabled(requireContext())
        smoothSwitch.setOnCheckedChangeListener { _, checked ->
            RunPrefs.setSmoothWalkEnabled(requireContext(), checked)
        }
        searchInput.setOnEditorActionListener { _, _, _ ->
            doSearch()
            true
        }

        setupSearch()
        centerOnDevice()
        updateFollowChip()
        renderFavorites()
        autoLocateOnce()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FreeRoamController.state.collect { state ->
                    val pos = state.position
                    updateHud(pos, state)
                    updatePositionMarker(pos)
                    if (pos != null) {
                        if (!centeredOnce || followEnabled) {
                            followCamera(pos, force = !centeredOnce)
                        }
                        centeredOnce = true
                    }
                    drawTrail(state.trail)
                    trackRecording(pos)
                }
            }
        }
    }

    private fun updateHud(pos: TrackMathUtils.LatLng?, state: FreeRoamController.RoamState) {
        if (pos == null) {
            roamMeters = 0.0
            roamStartedAt = 0L
            lastHudPos = null
            coordText.text = "WGS --, -- · 漫游 0 m · 00:00 · 未接管"
            return
        }
        if (roamStartedAt == 0L) roamStartedAt = SystemClock.elapsedRealtime()
        val last = lastHudPos
        if (last != null) {
            val d = TrackMathUtils.distanceMeters(last, pos)
            if (d in 0.2..30.0) roamMeters += d
        }
        lastHudPos = pos
        val elapsed = (SystemClock.elapsedRealtime() - roamStartedAt) / 1000
        coordText.text = String.format(
            Locale.US,
            "WGS %.5f, %.5f · 漫游 %.0f m · %02d:%02d · 海拔 %.0f m · %s",
            pos.lat, pos.lng,
            roamMeters, elapsed / 60, elapsed % 60,
            state.baseAltitude,
            if (state.speedMps > 0.05) String.format(Locale.US, "%.1f m/s", state.speedMps) else "待命"
        )
    }

    private fun updatePositionMarker(pos: TrackMathUtils.LatLng?) {
        if (pos == null) {
            positionMarker?.remove()
            positionMarker = null
            return
        }
        val bd = toBd(pos)
        val marker = positionMarker
        if (marker == null) {
            positionMarker = baiduMap.addOverlay(
                MarkerOptions()
                    .position(bd)
                    .icon(positionIcon ?: BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding))
                    .anchor(0.5f, 0.5f)
                    .zIndex(20)
            ) as? Marker
        } else {
            marker.position = bd
        }
    }

    private fun followCamera(pos: TrackMathUtils.LatLng, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFollowAt < 200) return
        lastFollowAt = now
        baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(toBd(pos)))
    }

    private fun updateFollowChip() {
        followChip.text = if (followEnabled) "跟随中" else "自由视角"
        followChip.setTextColor(requireContext().getColor(if (followEnabled) R.color.pf_volt else R.color.pf_text_secondary))
    }

    override fun onResume() {
        super.onResume()
        runCatching { mapView.onResume() }
        // 不再进入本页就自动拉起常驻悬浮摇杆：点地图上的「显示悬浮摇杆」提示
        // 或使用录制/空降等动作时，服务会自行启动并显示摇杆。
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            autoLocateOnce()
            renderFavorites()
        }
    }

    override fun onPause() {
        runCatching { mapView.onPause() }
        super.onPause()
    }

    override fun onDestroyView() {
        runCatching { suggestionSearch.destroy() }
        trailOverlay = null
        targetMarker = null
        positionMarker = null
        runCatching { mapView.onDestroy() }
        super.onDestroyView()
    }

    // ---------------- 交互 ----------------

    private fun showTarget(bd: BdLatLng, lat: Double, lng: Double) {
        targetMarker?.remove()
        targetMarker = baiduMap.addOverlay(
            MarkerOptions()
                .position(bd)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding))
                .title("空降目标")
        ) as? Marker
        targetText.text = String.format(Locale.US, "目标：%.5f, %.5f", lat, lng)
        targetBar.visibility = View.VISIBLE
        hint.visibility = View.GONE
    }

    private fun clearTarget() {
        pendingTarget = null
        targetMarker?.remove()
        targetMarker = null
        targetBar.visibility = View.GONE
        hint.visibility = View.VISIBLE
    }

    private fun confirmTeleport() {
        val target = pendingTarget ?: return
        if (RunSessionHolder.sessionActive) {
            toast("自动跑圈进行中，请先结束当前跑圈")
            return
        }
        RoamService.start(requireContext())
        FreeRoamController.attach(requireContext())
        val from = FreeRoamController.state.value.position
        val distance = from?.let { TrackMathUtils.distanceMeters(it, target) } ?: 0.0
        if (RunPrefs.isSmoothWalkEnabled(requireContext()) && from != null && distance <= 300.0) {
            val speed = RoamSpeeds.speed(requireContext(), RoamSpeeds.Gear.RUN)
            FreeRoamController.glideTo(target.lat, target.lng, speed.toDouble())
            toast(String.format(Locale.US, "平滑走过去 · 约 %.0f m", distance))
        } else {
            FreeRoamController.teleport(target.lat, target.lng)
            toast("已空降到该位置")
        }
        centerMap(target.lat, target.lng, 17f)
        clearTarget()
    }

    private fun locateReal(silent: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            if (!silent) locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (!silent) toast("正在获取真实位置…")
            val loc = RealLocationSampler.sample(requireContext())
            if (loc == null) {
                if (!silent) toast("暂未获取到真实位置，请检查定位权限")
                return@launch
            }
            centerMap(loc.latitude, loc.longitude, 17.5f)
            if (loc.hasAltitude()) {
                FreeRoamController.updateBaseAltitude(loc.altitude)
            }
            if (!silent) toast("已定位到当前真实位置")
        }
    }

    private fun autoLocateOnce() {
        if (autoLocated) return
        autoLocated = true
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) locateReal(silent = true) else centerOnDevice()
    }

    private fun collapseConsole() {
        console.visibility = View.GONE
        consoleChip.visibility = View.VISIBLE
    }

    private fun expandConsole() {
        console.visibility = View.VISIBLE
        consoleChip.visibility = View.GONE
    }

    private fun saveFavorite() {
        val point = pendingTarget ?: FreeRoamController.state.value.position
        if (point == null) {
            toast("还没有可收藏的位置，请先在地图上落点")
            return
        }
        val defaultName = String.format(Locale.US, "地点 %.4f,%.4f", point.lat, point.lng)
        val input = EditText(requireContext()).apply {
            setText(defaultName)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("收藏这个地点")
            .setMessage("最多保存 5 个常用地标，点击胶囊即可一键飞跃")
            .setView(input)
            .setPositiveButton("收藏") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                RunPrefs.addFavorite(
                    requireContext(),
                    RunPrefs.Favorite(name.take(12), point.lat, point.lng)
                )
                renderFavorites()
                toast("已收藏「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderFavorites() {
        favRow.removeAllViews()
        val favorites = RunPrefs.favorites(requireContext())
        favScroll.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favorites.forEach { favorite ->
            val chip = TextView(requireContext()).apply {
                text = "⭐ ${favorite.name}"
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(120)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.pf_text_primary))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_secondary_button)
                setOnClickListener { centerMap(favorite.lat, favorite.lng, 17f) }
                setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除收藏？")
                        .setMessage(favorite.name)
                        .setPositiveButton("删除") { _, _ ->
                            RunPrefs.removeFavorite(requireContext(), favorite.name)
                            renderFavorites()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            favRow.addView(chip, lp)
        }
    }

    // ---------------- 录制为路线 ----------------

    private fun startRecording() {
        if (RunSessionHolder.sessionActive) {
            toast("自动跑圈进行中，暂不可录制")
            return
        }
        RoamService.start(requireContext())
        FreeRoamController.attach(requireContext())
        recorded.clear()
        lastRecordedPoint = null
        recording = true
        recordStart = SystemClock.elapsedRealtime()
        lastRecordLabel = 0
        recordButton.text = "■ 结束录制 · 0s"
        recordButton.setTextColor(0xFFEF4444.toInt())
        toast("开始录制：用摇杆漫游或空降移动")
    }

    private fun trackRecording(pos: TrackMathUtils.LatLng?) {
        if (!recording) return
        if (pos != null) {
            val last = lastRecordedPoint
            if (last == null || TrackMathUtils.distanceMeters(last, pos) >= 0.6) {
                recorded.add(pos)
                lastRecordedPoint = pos
            }
        }
        val secs = (SystemClock.elapsedRealtime() - recordStart) / 1000
        if (secs != lastRecordLabel) {
            lastRecordLabel = secs
            recordButton.text = "■ 结束录制 · ${secs}s · ${recorded.size}点"
        }
    }

    private fun stopRecording() {
        recording = false
        recordButton.text = "● 录制为路线"
        recordButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.pf_amber))
        if (recorded.size < 8) {
            toast("轨迹太短，请多移动一会儿再结束")
            return
        }
        val smoothed = TrackMathUtils.smooth3(recorded.toList())
        var path = RouteSources.simplifyRdp(smoothed, 0.8)
        if (path.size < 3) path = smoothed
        var closed = RouteSources.closeLoopIfNeeded(path, 60.0).second
        if (!closed && path.size >= 3) {
            // 单程漫游：沿原路折返补成闭环，保证巡航可循环
            path = path + path.reversed().drop(1)
            closed = true
        }
        if (path.size < 3) {
            toast("录制点太少，无法成圈")
            return
        }
        val meters = TrackMathUtils.cumulativeDistances(path).last()
        val clockwise = RouteSources.isClockwise(path)
        val defaultName = SimpleDateFormat("MM-dd HH:mm 漫游", Locale.CHINA).format(Date())
        val input = EditText(requireContext()).apply {
            setText(defaultName)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("保存漫游路线")
            .setMessage(
                String.format(
                    Locale.US,
                    "单圈 %.0f m · %d 点 · %s",
                    meters, path.size, if (closed) "闭环" else "未闭环"
                )
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                val entity = RunRouteEntity(
                    name = name,
                    buildMode = "ROAM_RECORD",
                    singleLapMeters = meters,
                    isClosedLoop = closed,
                    isClockwise = clockwise,
                    denseWaypointsJson = path.toWgsJson(),
                    anchorNodesJson = "[]",
                    baseAltitudeMeters = FreeRoamController.state.value.baseAltitude
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = repository.saveRoute(entity)
                    toast(String.format(Locale.US, "已保存 #%d：%.0f m，可在自动跑圈中使用", id, meters))
                    FreeRoamController.clearTrail()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 搜索 ----------------

    private fun setupSearch() {
        suggestionSearch.setOnGetSuggestionResultListener { result ->
            val all = result?.allSuggestions.orEmpty()
            if (all.isEmpty()) {
                toast("没有搜到结果")
                return@setOnGetSuggestionResultListener
            }
            val labels = all.map { info ->
                listOfNotNull(info.key, info.district, info.address).joinToString(" · ")
            }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("选择地点")
                .setItems(labels) { _, which ->
                    val pt = all[which].pt ?: return@setItems
                    baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(pt, 17f))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doSearch() {
        val kw = searchInput.text.toString().trim()
        if (kw.isEmpty()) {
            toast("请输入搜索关键词")
            return
        }
        suggestionSearch.requestSuggestion(
            SuggestionSearchOption().keyword(kw).city("全国")
        )
        hideKeyboard()
    }

    private fun hideKeyboard() {
        runCatching {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
    }

    // ---------------- 地图绘制 ----------------

    private fun centerOnDevice() {
        val real = lastDeviceLocation() ?: return
        centerMap(real.first, real.second, 16f)
    }

    private fun centerMap(lat: Double, lng: Double, zoom: Float) {
        val bd = CoordTransformUtil.wgs84ToBd09(lat, lng)
        baiduMap.setMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(BdLatLng(bd.lat, bd.lng), zoom)
        )
    }

    private fun drawTrail(trail: List<TrackMathUtils.LatLng>) {
        if (trail.size == lastTrailSize) return
        lastTrailSize = trail.size
        if (trail.size < 2) {
            trailOverlay?.remove()
            trailOverlay = null
            return
        }
        val bdPoints = trail.map { toBd(it) }
        val overlay = trailOverlay
        if (overlay == null) {
            trailOverlay = FiberTrail.create(
                baiduMap, bdPoints,
                resources.displayMetrics.density,
                ThemeHelper.isDark(requireContext())
            )
        } else {
            overlay.update(bdPoints)
        }
    }

    private fun toBd(p: TrackMathUtils.LatLng): BdLatLng {
        val b = CoordTransformUtil.wgs84ToBd09(p.lat, p.lng)
        return BdLatLng(b.lat, b.lng)
    }

    private fun dropTarget(point: BdLatLng) {
        val wgs = CoordTransformUtil.bd09ToWgs84(point.latitude, point.longitude)
        pendingTarget = TrackMathUtils.LatLng(wgs.lat, wgs.lng)
        showTarget(point, wgs.lat, wgs.lng)
    }

    private fun lastDeviceLocation(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val lm = requireContext()
                .getSystemService(Context.LOCATION_SERVICE) as LocationManager
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
