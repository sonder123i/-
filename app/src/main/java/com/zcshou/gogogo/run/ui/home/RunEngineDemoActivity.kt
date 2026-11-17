package com.zcshou.gogogo.run.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.RunCruiseEngine
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.engine.TrackFactory
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.service.ServiceGo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Phase 2 引擎演示驱动页（后续 Phase 4 由 RunHomeActivity 正式替代）。
 * 合成 400m 操场轨迹，驱动 RunCruiseEngine 跑圈并实时展示进度。
 */
class RunEngineDemoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_KM = "auto_km"
        const val EXTRA_AUTO_DETOUR_AFTER_SEC = "auto_detour_after_sec"
        const val EXTRA_AUTO_ABORT_AFTER_SEC = "auto_abort_after_sec"
        private val CENTER = TrackMathUtils.LatLng(36.667662, 117.027707)
        private const val DEMO_ROUTE_ID = 9001L
        private const val DEMO_PACE_SEC_PER_KM = 340
    }

    private lateinit var statusText: TextView
    private lateinit var repository: RunRepository
    private var engine: RunCruiseEngine? = null
    private var pendingStart: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ServiceGo.ServiceGoBinder ?: return
            val cruise = RunCruiseEngine(applicationContext, binder.injector, repository)
            engine = cruise
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    cruise.state.collect { render(it) }
                }
            }
            pendingStart?.invoke()
            pendingStart = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_run_engine_demo)
        repository = RunRepository(applicationContext)
        statusText = findViewById(R.id.demoStatus)

        findViewById<Button>(R.id.btnStartShort).setOnClickListener { startRun(0.05f) }
        findViewById<Button>(R.id.btnStart3km).setOnClickListener { startRun(3.0f) }
        findViewById<Button>(R.id.btnPauseToggle).setOnClickListener { togglePause() }
        findViewById<Button>(R.id.btnDetour).setOnClickListener { insertDetour() }
        findViewById<Button>(R.id.btnFinishEarly).setOnClickListener { engine?.finishEarly() }
        findViewById<Button>(R.id.btnAbort).setOnClickListener { engine?.abort() }
        findViewById<Button>(R.id.btnOpenEditor).setOnClickListener {
            startActivity(Intent(this, com.zcshou.gogogo.run.ui.editor.RouteEditorActivity::class.java))
        }

        scheduleAutoDrive(intent)
    }

    /**
     * 调试用自动驱动：adb am start --ef auto_km 3.0 --ei auto_detour_after_sec 15
     * 依次自动开跑、插点、可选中止，便于无人值守验证状态机。
     */
    private fun scheduleAutoDrive(intent: Intent?) {
        val intent = intent ?: return
        val autoKm = intent.getFloatExtra(EXTRA_AUTO_KM, 0f)
        if (autoKm <= 0f) return
        val detourAfter = intent.getIntExtra(EXTRA_AUTO_DETOUR_AFTER_SEC, -1)
        val abortAfter = intent.getIntExtra(EXTRA_AUTO_ABORT_AFTER_SEC, -1)
        lifecycleScope.launch {
            delay(800)
            startRun(autoKm)
            if (detourAfter > 0) {
                delay(detourAfter * 1000L)
                insertDetour()
            }
            if (abortAfter > 0) {
                delay(abortAfter * 1000L)
                engine?.abort()
            }
        }
    }

    override fun onDestroy() {
        engine?.release()
        if (engine != null) {
            runCatching { unbindService(connection) }
        }
        super.onDestroy()
    }

    private fun startRun(targetKm: Float) {
        val begin: () -> Unit = {
            val (json, length) = TrackFactory.denseJson(CENTER)
            val route = RunRouteEntity(
                id = DEMO_ROUTE_ID,
                name = "演示 400m 标准圈",
                buildMode = "TRACK_SPLINE",
                singleLapMeters = length,
                denseWaypointsJson = json,
                anchorNodesJson = "[]"
            )
            engine?.start(
                route = route,
                targetKm = targetKm,
                targetPaceSecPerKm = DEMO_PACE_SEC_PER_KM,
                distanceJitterMeters = 0,
                enableLaneDrift = true,
                enableCooldownWalk = true
            )
            Unit
        }
        if (engine != null) {
            begin()
            return
        }
        pendingStart = begin
        val intent = Intent(this, ServiceGo::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        startForegroundService(intent)
    }

    private fun togglePause() {
        val cruise = engine ?: return
        when (cruise.state.value.phase) {
            RunPhase.PAUSED -> cruise.resume()
            RunPhase.RUNNING -> cruise.pause()
            else -> Unit
        }
    }

    private fun insertDetour() {
        val target = TrackMathUtils.destinationPoint(CENTER, 90.0, 120.0)
        engine?.insertDetour(target.lat, target.lng)
    }

    private fun render(state: RunRuntimeState) {
        val pace = if (state.paceSecPerKm > 0) {
            String.format(Locale.US, "%d'%02d\"", state.paceSecPerKm / 60, state.paceSecPerKm % 60)
        } else {
            "--"
        }
        statusText.text = buildString {
            appendLine("阶段: ${state.phase}")
            appendLine(String.format(Locale.US, "里程: %.2f / %.2f km", state.accumulatedKm, state.targetKm))
            appendLine("圈数: ${state.currentLapIndex}")
            appendLine(String.format(Locale.US, "速度: %.2f m/s  配速: %s", state.speedMps, pace))
            appendLine("用时: ${state.elapsedSec} s")
            state.position?.let {
                appendLine(String.format(Locale.US, "坐标: %.6f, %.6f", it.lat, it.lng))
            }
            state.message?.let { appendLine("消息: $it") }
        }
    }
}