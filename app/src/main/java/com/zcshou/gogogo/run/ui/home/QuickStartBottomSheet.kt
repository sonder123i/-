package com.zcshou.gogogo.run.ui.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.repository.RunRepository
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 一键开跑参数微调弹窗：目标公里数 / 配速 / 变道 / 慢走收尾。
 */
class QuickStartBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ROUTE_ID = "route_id"
        private const val ARG_ROUTE_NAME = "route_name"

        private const val KM_MIN = 0.5f
        private const val KM_STEP = 0.1f
        private const val KM_MAX_PROGRESS = 95

        private const val PACE_MIN = 240
        private const val PACE_STEP = 5
        private const val PACE_MAX_PROGRESS = 48

        fun newInstance(routeId: Long, routeName: String): QuickStartBottomSheet =
            QuickStartBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROUTE_ID, routeId)
                    putString(ARG_ROUTE_NAME, routeName)
                }
            }
    }

    private lateinit var repository: RunRepository

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.28f)
            // window 动画是 SurfaceFlinger 层的变换，view 树不会重绘，
            // 玻璃会按动画前的位置采样（折射被冻住）；进场改由下面的 view 动画负责。
            setWindowAnimations(0)
        }
        // 弹出参数微调面板时，底层主视图同步轻微缩小并压暗，营造 iOS 模态景深
        springHostScale(0.96f)
        // MaterialComponents 的 design_bottom_sheet 会自带白色 shape 背景，
        // 等布局完成后再清掉从根布局到 dialog decor 之间的所有底色，
        // 露出根布局的顶部圆角玻璃面板。
        view?.post {
            var parent: View? = view?.parent as? View
            while (parent != null && parent !== dialog?.window?.decorView) {
                parent.background = null
                parent = parent.parent as? View
            }
            dialog?.window?.decorView?.background = null
            animateSheetIn()
        }
        // 拖拽用的是 offsetTopAndBottom，同样不会重录子视图，滑动过程中逐帧重绘玻璃
        (view?.parent as? View)?.let { sheet ->
            (sheet.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.behavior
                ?.let { behavior ->
                    (behavior as? com.google.android.material.bottomsheet.BottomSheetBehavior<*>)
                        ?.addBottomSheetCallback(object :
                            com.google.android.material.bottomsheet.BottomSheetBehavior
                            .BottomSheetCallback() {
                            override fun onStateChanged(view: View, newState: Int) = Unit

                            override fun onSlide(sheet: View, slideOffset: Float) {
                                view?.invalidate()
                            }
                        })
                }
        }
    }

    /** 玻璃面板从下方滑入：动的是 view 自身，每帧重绘才能让折射跟着走。 */
    private fun animateSheetIn() {
        val glass = view ?: return
        glass.animate().cancel()
        glass.translationY = glass.height.toFloat()
        glass.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .setUpdateListener { glass.invalidate() }
            .start()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        springHostScale(1f)
        super.onDismiss(dialog)
    }

    private fun springHostScale(target: Float) {
        val host = activity?.findViewById<View>(R.id.homeRoot) ?: return
        host.pivotX = host.width / 2f
        host.pivotY = host.height / 2f
        listOf(SpringAnimation.SCALE_X, SpringAnimation.SCALE_Y).forEach { property ->
            SpringAnimation(host, property, target).apply {
                setSpring(
                    SpringForce(target).apply {
                        dampingRatio = 0.72f
                        stiffness = 380f
                    }
                )
                start()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_quick_start, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (view as? com.example.liquidglass.LiquidGlassView)?.let {
            com.zcshou.gogogo.run.ui.glass.SheetGlass.attach(this, it)
        }
        repository = RunRepository(requireContext())
        val routeId = requireArguments().getLong(ARG_ROUTE_ID)
        val routeName = requireArguments().getString(ARG_ROUTE_NAME).orEmpty()

        val title = view.findViewById<TextView>(R.id.sheetTitle)
        val kmLabel = view.findViewById<TextView>(R.id.kmLabel)
        val paceLabel = view.findViewById<TextView>(R.id.paceLabel)
        val seekKm = view.findViewById<SeekBar>(R.id.seekKm)
        val seekPace = view.findViewById<SeekBar>(R.id.seekPace)
        val switchDrift = view.findViewById<Switch>(R.id.switchDrift)
        val switchReverse = view.findViewById<Switch>(R.id.switchReverse)
        val switchCooldown = view.findViewById<Switch>(R.id.switchCooldown)

        title.text = routeName
        seekKm.max = KM_MAX_PROGRESS
        seekKm.progress = 25
        seekPace.max = PACE_MAX_PROGRESS
        seekPace.progress = 20

        fun km(): Float = KM_MIN + seekKm.progress * KM_STEP
        fun pace(): Int = PACE_MIN + seekPace.progress * PACE_STEP

        fun refreshLabels() {
            kmLabel.text = String.format(Locale.US, "目标公里数：%.1f km", km())
            val p = pace()
            paceLabel.text = String.format(Locale.US, "目标配速：%d'%02d\" /km", p / 60, p % 60)
        }
        refreshLabels()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = refreshLabels()
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        seekKm.setOnSeekBarChangeListener(listener)
        seekPace.setOnSeekBarChangeListener(listener)

        view.findViewById<TextView>(R.id.btnStartRun).setOnClickListener {
            val host = activity as? RunHost
            if (host == null) {
                com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                    requireContext(),
                    "宿主页面不可用"
                )
                return@setOnClickListener
            }
            val targetKm = km()
            val targetPace = pace()
            val drift = switchDrift.isChecked
            val reverse = switchReverse.isChecked
            val cooldown = switchCooldown.isChecked
            viewLifecycleOwner.lifecycleScope.launch {
                val route = repository.getRoute(routeId)
                if (route == null) {
                    com.zcshou.gogogo.run.ui.glass.GlassFeedback.toast(
                        requireContext(),
                        "路线已删除"
                    )
                    return@launch
                }
                host.startRun(route, targetKm, targetPace, drift, cooldown, reverse)
                dismissAllowingStateLoss()
            }
        }
    }
}