package com.zcshou.gogogo.run.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.prefs.RoamSpeeds
import com.zcshou.gogogo.run.ui.home.FreeRoamController
import java.util.Locale
import kotlin.math.hypot

/**
 * 原版式常驻漫游摇杆：顶部拖动条 + 定位/隐藏，中间大球摇杆，
 * 四档速度（长按改数值），底部状态与结束。由 RoamService 托管。
 */
class RoamHubView(context: Context) : LinearLayout(context) {

    var dragHandler: ((Int, Int, Boolean) -> Unit)? = null
    var onLocate: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val joystick: TacticalJoystickView
    private val statusText: TextView
    private val dragBar: TextView
    private val lockButton: ImageView
    private val gearChips = mutableListOf<Pair<TextView, RoamSpeeds.Gear>>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var selectedGear = RoamSpeeds.Gear.RUN
    private var gearSpeed = 3.6f
    private var lastBearing = 0f
    private var hubPanel: View
    private var hubLook: Boolean? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.roam_hub, this, true)
        hubPanel = findViewById(R.id.hubPanel)
        joystick = findViewById(R.id.roamHubJoystick)
        statusText = findViewById(R.id.roamHubStatus)
        dragBar = findViewById(R.id.roamDragBar)
        lockButton = findViewById(R.id.btnRoamHubLock)

        attachWindowDrag(dragBar)

        findViewById<View>(R.id.btnRoamHubLocate).setOnClickListener { onLocate?.invoke() }
        findViewById<View>(R.id.btnRoamHubHide).setOnClickListener { onHide?.invoke() }
        findViewById<TextView>(R.id.btnRoamHubStop).setOnClickListener { confirmStop() }
        lockButton.setOnClickListener {
            joystick.toggleLockFromButton()
            updateLockChip()
        }

        setupGears()
        gearSpeed = RoamSpeeds.speed(context, selectedGear)
        joystick.listener = object : TacticalJoystickView.Listener {
            override fun onSteer(bearingDeg: Float, strength: Float) {
                lastBearing = bearingDeg
                if (strength <= 0.02f) {
                    FreeRoamController.stopMotion()
                } else {
                    FreeRoamController.attach(context)
                    FreeRoamController.drive(
                        bearingDeg.toDouble(),
                        (gearSpeed * strength).toDouble()
                    )
                }
                refreshStatus()
            }

            override fun onLockChanged(locked: Boolean) {
                updateLockChip()
                refreshStatus()
            }
        }
        updateLockChip()
        applyPanelGlass(translucent = true)
        refreshStatus()
    }

    /** V6.6：跨窗模糊可用时半透，被 ROM 禁用时换成更实的兜底，杜绝透字。 */
    fun setCrossWindowBlurEnabled(enabled: Boolean) {
        if (hubLook == enabled) return
        hubLook = enabled
        applyPanelGlass(enabled)
    }

    /**
     * 摇杆盘底片：棱镜虹彩玻璃。
     * 悬浮窗采不到下层像素，材质厚度只能靠薄膜 + 镜面高光 + 冷银细边画出来。
     */
    private fun applyPanelGlass(translucent: Boolean) {
        hubPanel.background = PrismGlassDrawable(
            context,
            22f * resources.displayMetrics.density
        ).also { it.alpha = if (translucent) 236 else 255 }
    }

    private fun updateLockChip() {
        lockButton.setImageResource(
            if (joystick.isLocked) R.drawable.ic_lock_line else R.drawable.ic_lock_open_line
        )
        val color = context.getColor(
            if (joystick.isLocked) R.color.pf_volt else R.color.pf_text_secondary
        )
        lockButton.imageTintList = ColorStateList.valueOf(color)
    }

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

    private fun setupGears() {
        gearChips.clear()
        gearChips += findViewById<TextView>(R.id.roamGearWalk) to RoamSpeeds.Gear.WALK
        gearChips += findViewById<TextView>(R.id.roamGearRun) to RoamSpeeds.Gear.RUN
        gearChips += findViewById<TextView>(R.id.roamGearBike) to RoamSpeeds.Gear.BIKE
        gearChips += findViewById<TextView>(R.id.roamGearCar) to RoamSpeeds.Gear.CAR
        gearChips.forEach { (view, gear) ->
            view.setOnClickListener {
                selectedGear = gear
                gearSpeed = RoamSpeeds.speed(context, gear)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                paintGears()
                refreshStatus()
                if (joystick.isLocked) {
                    FreeRoamController.attach(context)
                    FreeRoamController.drive(lastBearing.toDouble(), gearSpeed.toDouble())
                }
            }
            view.setOnLongClickListener {
                editGearSpeed(gear)
                true
            }
        }
        paintGears()
    }

    private fun paintGears() {
        gearChips.forEach { (view, gear) ->
            val on = gear == selectedGear
            // 悬浮窗自己的色板（dr_overlay_*）随主题翻转，不要混用 App 内的 pf_*，
            // 否则深色主题下会出现「黑底 + 黑字」。
            view.setTextColor(
                context.getColor(
                    if (on) R.color.dr_overlay_ice else R.color.dr_overlay_text_secondary
                )
            )
            view.setTypeface(
                null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            view.setBackgroundResource(
                if (on) R.drawable.bg_overlay_chip_selected else R.drawable.bg_round_dark
            )
        }
    }

    fun updateStatus(speedMps: Double) {
        refreshStatus(speedMps)
    }

    private fun refreshStatus(speedMps: Double = FreeRoamController.state.value.speedMps) {
        val lock = if (joystick.isLocked) "锁定 · " else ""
        val speed = String.format(Locale.US, "%.1f m/s", if (speedMps > 0.05) speedMps else gearSpeed)
        statusText.text = "$lock${selectedGear.label} $speed"
    }

    private fun editGearSpeed(gear: RoamSpeeds.Gear) {
        FreeRoamController.stopMotion()
        joystick.unlock()
        val current = RoamSpeeds.speed(context, gear)
        val input = EditText(context).apply {
            com.zcshou.gogogo.run.ui.widget.DialogStyler.styleInput(this)
            setText(current.toString())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("${gear.label}速度（m/s）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().toFloatOrNull() ?: return@setPositiveButton
                RoamSpeeds.setSpeed(context, gear, value)
                if (gear == selectedGear) gearSpeed = value.coerceIn(0.2f, 40f)
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun confirmStop() {
        FreeRoamController.stopMotion()
        joystick.unlock()
        val dialog = AlertDialog.Builder(context)
            .setTitle("结束模拟定位？")
            .setMessage("将停止漫游并移除虚拟定位，恢复手机真实 GPS。")
            .setPositiveButton("结束并关闭") { _, _ -> onStop?.invoke() }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
