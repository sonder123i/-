package com.zcshou.gogogo.run.ui.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import com.zcshou.gogogo.run.ui.home.FreeRoamController
import com.zcshou.gogogo.run.ui.home.RunHost
import java.util.Locale
import kotlin.math.hypot

/**
 * 统一悬浮窗：收起态巡航胶囊；展开态自动跑圈控制岛 / 紧凑战术摇杆盘。
 * 窗口拖动仅绑定在药丸与顶部把手栏，摇杆圆盘独占触摸，互不抢事件。
 */
class FloatingCapsuleView @JvmOverloads constructor(
    context: Context,
    private val host: RunHost? = null,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private const val IDLE_DIM_DELAY_MS = 3_000L
        private const val DIM_ALPHA = 0.45f
        private const val FULL_ALPHA = 1.0f
        private const val GHOST_HIDE_MS = 5_000L
    }

    private val capsuleBar: LinearLayout
    private val ring: RingProgressView
    private val capsuleText: TextView
    private val actions: LinearLayout
    private val paceText: TextView
    private val lapText: TextView
    private val pauseButton: TextView
    private val finishButton: TextView
    private val hubStatus: TextView
    private val hubDot: TextView
    private val hubHeader: LinearLayout
    private val roamPanel: LinearLayout
    private val hubDragBar: TextView
    private val joystick: TacticalJoystickView
    private val roamLockButton: TextView

    /** 拖拽回调：(位移 dx, 位移 dy, 是否松手) */
    var dragHandler: ((Int, Int, Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var inRoamMode = false
    private var gearSpeed = 3.6f
    private var selectedGear = RoamSpeeds.Gear.RUN
    private var expanded = false
    private var pulseAnimator: ObjectAnimator? = null
    private var idleRunnable: Runnable? = null
    private var ghostRunnable: Runnable? = null
    private var stepsWarningState = false
    private var blurFallbackOn = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.floating_capsule, this, true)
        capsuleBar = findViewById(R.id.capsuleBar)
        ring = findViewById(R.id.capsuleRing)
        capsuleText = findViewById(R.id.capsuleText)
        actions = findViewById(R.id.capsuleActions)
        paceText = findViewById(R.id.capsulePace)
        lapText = findViewById(R.id.capsuleLap)
        pauseButton = findViewById(R.id.btnCapsulePause)
        finishButton = findViewById(R.id.btnCapsuleFinish)
        hubStatus = findViewById(R.id.hubStatus)
        hubDot = findViewById(R.id.hubDot)
        hubHeader = findViewById(R.id.hubHeader)
        roamPanel = findViewById(R.id.capsuleRoamPanel)
        hubDragBar = findViewById(R.id.hubDragBar)
        joystick = findViewById(R.id.hubJoystick)
        roamLockButton = findViewById(R.id.btnRoamLock)

        attachWindowDrag(capsuleBar)
        attachWindowDrag(hubHeader)
        attachWindowDrag(hubDragBar)

        capsuleBar.setOnClickListener {
            if (inRoamMode) {
                if (roamPanel.visibility == VISIBLE) collapseRoam() else expandRoam()
            } else {
                toggleExpanded()
            }
        }
        capsuleBar.setOnLongClickListener {
            ghostHide()
            true
        }
        findViewById<TextView>(R.id.btnHubCollapse).setOnClickListener { collapseActions() }
        findViewById<TextView>(R.id.btnRoamCollapse).setOnClickListener { collapseRoam() }
        roamLockButton.setOnClickListener {
            joystick.toggleLockFromButton()
            updateLockChip()
        }
        pauseButton.setOnClickListener {
            host?.pauseResume()
            scheduleIdleDim()
        }
        findViewById<TextView>(R.id.btnCapsuleDetour).setOnClickListener {
            host?.pickDetour()
            scheduleIdleDim()
        }
        finishButton.setOnClickListener {
            host?.finishEarly()
            scheduleIdleDim()
        }
        findViewById<TextView>(R.id.btnCapsuleManual).setOnClickListener {
            beginManualTakeover()
        }
        findViewById<TextView>(R.id.btnCapsuleReturn).setOnClickListener {
            endManualTakeover()
        }
        findViewById<TextView>(R.id.btnHubStopSim).setOnClickListener { confirmStopSimulation() }
        findViewById<TextView>(R.id.btnHubStopSimRoam).setOnClickListener { confirmStopSimulation() }
        setupHubGear()
        gearSpeed = RoamSpeeds.speed(context, selectedGear)
        joystick.listener = object : TacticalJoystickView.Listener {
            override fun onSteer(bearingDeg: Float, strength: Float) {
                if (strength <= 0.02f) {
                    FreeRoamController.stopMotion()
                    return
                }
                FreeRoamController.drive(
                    bearingDeg.toDouble(),
                    (gearSpeed * strength).toDouble()
                )
            }

            override fun onLockChanged(locked: Boolean) {
                updateLockChip()
                scheduleIdleDim()
            }
        }

        updateLockChip()

        startPulse()
        scheduleIdleDim()
    }

    /** 窗口拖动只绑定在这些把手上，摇杆区域不受影响 */
    private fun attachWindowDrag(handle: View) {
        handle.isClickable = true
        handle.setOnTouchListener(object : OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        animate().cancel()
                        alpha = FULL_ALPHA
                        scheduleIdleDim()
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!moved && hypot(dx, dy) > touchSlop) {
                            moved = true
                            v.cancelLongPress()
                        }
                        if (moved) {
                            dragHandler?.invoke(dx.toInt(), dy.toInt(), false)
                            downX = event.rawX
                            downY = event.rawY
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) {
                            moved = false
                            dragHandler?.invoke(0, 0, true)
                            return true
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            // 直接分发点击并取消长按，避免摇杆/胶囊被误判为长按隐身
                            v.cancelLongPress()
                            v.isPressed = false
                            v.performClick()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun setupHubGear() {
        data class GearChip(val view: TextView, val gear: RoamSpeeds.Gear)
        val chips = listOf(
            GearChip(findViewById(R.id.hubGearWalk), RoamSpeeds.Gear.WALK),
            GearChip(findViewById(R.id.hubGearJog), RoamSpeeds.Gear.RUN),
            GearChip(findViewById(R.id.hubGearBike), RoamSpeeds.Gear.BIKE),
            GearChip(findViewById(R.id.hubGearCar), RoamSpeeds.Gear.CAR)
        )
        fun paint() {
            chips.forEach { chip ->
                val on = chip.gear == selectedGear
                chip.view.setTextColor(
                    context.getColor(if (on) R.color.pf_volt else R.color.pf_text_secondary)
                )
                chip.view.setTypeface(
                    null,
                    if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                chip.view.setBackgroundResource(if (on) R.drawable.bg_tab_selected else 0)
            }
        }
        chips.forEach { chip ->
            chip.view.setOnClickListener {
                selectedGear = chip.gear
                gearSpeed = RoamSpeeds.speed(context, chip.gear)
                if (inRoamMode) capsuleText.text = "手动接管 · ${chip.gear.label}"
                paint()
            }
            chip.view.setOnLongClickListener {
                editGearSpeed(chip.gear) {
                    if (selectedGear == chip.gear) gearSpeed = it
                }
                true
            }
        }
        paint()
    }

    private fun editGearSpeed(gear: RoamSpeeds.Gear, onSaved: (Float) -> Unit) {
        FreeRoamController.stopMotion()
        joystick.unlock()
        host?.setOverlayHidden(true)
        val current = RoamSpeeds.speed(context, gear)
        val input = EditText(context).apply {
            setText(current.toString())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle("${gear.label}速度（m/s）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                RoamSpeeds.setSpeed(context, gear, value)
                onSaved(value.coerceIn(0.2f, 40f))
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { host?.setOverlayHidden(false) }
            .showOverlaid()
    }

    /**
     * 悬浮窗模式下（本视图为 TYPE_APPLICATION_OVERLAY 窗口）对话框必须提升窗口层级，
     * 否则会被 overlay 层压住不可见/不可交互；在页面内嵌模式下保持普通窗口。
     * 同款处理见 RoamHubView。
     */
    private fun AlertDialog.Builder.showOverlaid() {
        val dialog = create()
        val lp = layoutParams as? WindowManager.LayoutParams
        if (lp?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateLockChip() {
        roamLockButton.text = if (joystick.isLocked) "🔒" else "🔓"
        roamLockButton.setTextColor(
            context.getColor(if (joystick.isLocked) R.color.pf_volt else R.color.pf_text_secondary)
        )
    }

    /** V4.0：跨窗模糊被 ROM 禁用时切换 94% 高密度磨砂，杜绝透字。 */
    fun setCrossWindowBlurEnabled(enabled: Boolean) {
        val fallback = !enabled
        if (blurFallbackOn == fallback) return
        blurFallbackOn = fallback
        applyCapsuleBackground()
    }

    private fun applyCapsuleBackground() {
        capsuleBar.setBackgroundResource(
            when {
                stepsWarningState -> R.drawable.bg_capsule_warn
                blurFallbackOn -> R.drawable.bg_capsule_solid
                else -> R.drawable.bg_capsule
            }
        )
    }

    fun update(state: RunRuntimeState) {
        val pace = if (state.paceSecPerKm > 0) {
            String.format(Locale.US, "%d'%02d\"", state.paceSecPerKm / 60, state.paceSecPerKm % 60)
        } else {
            "--"
        }
        if (!inRoamMode) {
            stepsWarningState = state.stepsWarning
            applyCapsuleBackground()
            if (state.stepsWarning) {
                capsuleText.text = "⚠️ 请放入口袋"
                capsuleText.setTextColor(context.getColor(R.color.pf_amber))
                hubStatus.text = "⚠️ 未检测到步数，请放入口袋"
                hubDot.setTextColor(context.getColor(R.color.pf_amber))
            } else {
                capsuleText.setTextColor(context.getColor(R.color.pf_text_primary))
                capsuleText.text = if (state.phase == RunPhase.PRE_START_SETTLE) {
                    String.format(Locale.US, "预热 %ds", state.settleRemainSec)
                } else {
                    String.format(Locale.US, "%.2f/%.2fkm %s", state.accumulatedKm, state.targetKm, pace)
                }
                hubStatus.text = when (state.phase) {
                    RunPhase.PRE_START_SETTLE -> "预热中 · 请切到跑步 App"
                    RunPhase.RUNNING -> "模拟运行中 · 已接管"
                    RunPhase.PAUSED -> "已暂停 · 虚拟定位保持"
                    RunPhase.DETOUR_POINT -> "绕路打卡中"
                    RunPhase.COOLDOWN_WALK -> "慢走收尾中"
                    else -> "模拟运行中"
                }
                hubDot.setTextColor(
                    context.getColor(if (state.phase == RunPhase.PAUSED) R.color.pf_amber else R.color.pf_ok)
                )
            }
        }
        ring.setProgress(if (state.targetMeters > 0) {
            (state.accumulatedMeters / state.targetMeters).toFloat()
        } else {
            0f
        })
        paceText.text = pace
        lapText.text = state.currentLapIndex.toString()
        pauseButton.text = if (state.phase == RunPhase.PAUSED) "继续" else "暂停"
        finishButton.isEnabled = state.phase == RunPhase.RUNNING || state.phase == RunPhase.DETOUR_POINT
    }

    // ---------------- 展开 / 收起 ----------------

    private fun toggleExpanded() {
        if (actions.visibility == VISIBLE) collapseActions() else expandActions()
    }

    private fun expandActions() {
        expanded = true
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        actions.visibility = VISIBLE
        actions.alpha = 0f
        actions.scaleX = 0.9f
        actions.scaleY = 0.9f
        actions.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun collapseActions() {
        if (actions.visibility != VISIBLE) return
        expanded = false
        actions.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(140)
            .withEndAction { actions.visibility = GONE }
            .start()
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun expandRoam() {
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        roamPanel.visibility = VISIBLE
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun collapseRoam() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        roamPanel.visibility = GONE
        roamPanel.alpha = 1f
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    /** 点击悬浮窗外面时调用：展开态自动收起为小胶囊 */
    fun collapseIfExpanded(): Boolean {
        if (inRoamMode && roamPanel.visibility == VISIBLE) {
            collapseRoam()
            return true
        }
        if (!inRoamMode && actions.visibility == VISIBLE) {
            collapseActions()
            return true
        }
        return false
    }

    // ---------------- 手动接管 / 结束模拟 ----------------

    private fun beginManualTakeover() {
        val running = host?.currentState()?.phase
        if (running == RunPhase.RUNNING || running == RunPhase.DETOUR_POINT) {
            host?.pauseResume()
        }
        host?.beginManualOverride()
        FreeRoamController.attach(context)
        inRoamMode = true
        expanded = false
        idleRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = FULL_ALPHA
        actions.visibility = GONE
        roamPanel.visibility = VISIBLE
        capsuleText.text = "手动接管 · ${selectedGear.label}"
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun endManualTakeover() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        val target = host?.currentState()?.position
        inRoamMode = false
        roamPanel.visibility = GONE
        actions.visibility = GONE
        expanded = false
        capsuleText.text = "正在回归跑道…"
        if (target != null) {
            // 先平滑飞回跑道暂停点，避免瞬移
            FreeRoamController.teleport(target.lat, target.lng)
            postDelayed({
                host?.endManualOverride()
                if (host?.currentState()?.phase == RunPhase.PAUSED) {
                    host.pauseResume()
                }
            }, 900)
        } else {
            host?.endManualOverride()
            if (host?.currentState()?.phase == RunPhase.PAUSED) {
                host.pauseResume()
            }
        }
        post { host?.relayoutOverlay() }
        scheduleIdleDim()
    }

    private fun confirmStopSimulation() {
        host?.setOverlayHidden(true)
        AlertDialog.Builder(context)
            .setTitle("结束模拟定位？")
            .setMessage("将停止巡航与虚拟定位，移除测试定位并恢复手机真实 GPS。")
            .setPositiveButton("结束并关闭") { _, _ -> host?.stopSimulation() }
            .setNegativeButton("取消") { _, _ -> host?.setOverlayHidden(false) }
            .setOnCancelListener { host?.setOverlayHidden(false) }
            .showOverlaid()
    }

    // ---------------- 闲置半隐 / 长按隐身 ----------------

    private fun scheduleIdleDim() {
        idleRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable {
            if (actions.visibility == VISIBLE || roamPanel.visibility == VISIBLE) {
                animate().cancel()
                alpha = FULL_ALPHA
            } else {
                animate().alpha(DIM_ALPHA).setDuration(400).start()
            }
        }
        idleRunnable = runnable
        postDelayed(runnable, IDLE_DIM_DELAY_MS)
    }

    private fun ghostHide() {
        ghostRunnable?.let { removeCallbacks(it) }
        animate().alpha(0f).setDuration(150).start()
        val runnable = Runnable {
            animate().alpha(FULL_ALPHA).setDuration(200).start()
            scheduleIdleDim()
        }
        ghostRunnable = runnable
        postDelayed(runnable, GHOST_HIDE_MS)
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        val animator = ObjectAnimator.ofInt(this, "pulseAlpha", 255, 70)
        animator.duration = 1100
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        pulseAnimator = animator
        animator.start()
    }

    fun setPulseAlpha(alpha: Int) = ring.setPulseAlpha(alpha)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            animate().cancel()
            alpha = FULL_ALPHA
            scheduleIdleDim()
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
        scheduleIdleDim()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        idleRunnable?.let { removeCallbacks(it) }
        ghostRunnable?.let { removeCallbacks(it) }
        super.onDetachedFromWindow()
    }
}
