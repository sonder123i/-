package com.zcshou.gogogo.run.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_quick_start, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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

        view.findViewById<Button>(R.id.btnStartRun).setOnClickListener {
            val host = activity as? RunHost
            if (host == null) {
                Toast.makeText(requireContext(), "宿主页面不可用", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), "路线已删除", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                host.startRun(route, targetKm, targetPace, drift, cooldown, reverse)
                dismissAllowingStateLoss()
            }
        }
    }
}