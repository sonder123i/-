package com.zcshou.gogogo.run.ui.home

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.engine.TrackJsonCodec
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.ui.glass.OverlayGlassStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 本地历史战报：列表 → 复盘弹窗（多圈变道轨迹 + 分段配速估算）。
 * 复用现有 RunRecordEntity，不新增数据库字段。
 */
object HistoryReportDialog {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(context: Context, repository: RunRepository) {
        scope.launch {
            if (!isContextAlive(context)) return@launch
            val records = repository.observeRecords().first().take(30)
            if (records.isEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("本地历史战报")
                    .setMessage("还没有完成记录，跑完第一圈后这里会自动生成战报。")
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            val labels = records.map { formatItem(it) }.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("本地历史战报")
                .setItems(labels) { _, which ->
                    scope.launch {
                        val record = records[which]
                        val route = repository.getRoute(record.routeId)
                        showDetail(context, record, route)
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun showDetail(context: Context, record: RunRecordEntity, route: RunRouteEntity?) {
        if (!isContextAlive(context)) return
        val points = route?.let { TrackJsonCodec.decode(it.denseWaypointsJson) } ?: emptyList()
        val lapLength = route?.singleLapMeters?.takeIf { it > 1.0 }
            ?: points.let { TrackMathUtils.cumulativeDistances(it).lastOrNull() }
        val laps = if (lapLength != null && lapLength > 1.0) {
            ceil(record.actualDistanceMeters / lapLength).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_history_replay, null)
        view.findViewById<TextView>(R.id.historyReplayTitle).text =
            if (route != null) route.name else "路线已删除 · ${record.routeName}"
        view.findViewById<TextView>(R.id.historyReplaySummary).text = String.format(
            Locale.US,
            "%s · %.2f km · 用时 %s · 平均配速 %s · %s · %d 圈分层",
            formatDate(record.startedAt),
            record.actualDistanceMeters / 1000.0,
            formatDuration(record.durationSeconds),
            if (record.averagePaceSec > 0) formatPace(record.averagePaceSec) else "--",
            if (record.status == "COMPLETED") "已完成" else "已中止",
            laps
        )
        view.findViewById<RunReplayView>(R.id.historyReplayView).bind(
            points = points,
            laps = laps,
            averagePaceSec = record.averagePaceSec,
            seed = record.id.toLong()
        )
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()
        // V4.0 玻璃战报：跨窗模糊折射底下页面 + 圆角液态玻璃窗口底；
        // ROM 关闭跨窗模糊时回退实体底板，严禁出现"无模糊且全透明导致文字重叠"
        dialog.window?.let { window ->
            val density = context.resources.displayMetrics.density
            val lp = window.attributes
            OverlayGlassStyle.enableBlurBehind(lp, 36f * density)
            window.attributes = lp
            window.setBackgroundDrawableResource(R.drawable.bg_glass_dialog)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val token = wm?.let {
                OverlayGlassStyle.watchCrossWindowBlur(it) { enabled ->
                    window.setBackgroundDrawableResource(
                        if (enabled) R.drawable.bg_glass_dialog else R.drawable.bg_hub_panel_solid
                    )
                }
            }
            dialog.setOnDismissListener {
                OverlayGlassStyle.unwatchCrossWindowBlur(wm, token)
            }
        }
        dialog.show()
    }

    /** 弹窗宿主可能在挂起查询期间销毁，恢复执行时必须先校验窗口 token。 */
    private fun isContextAlive(context: Context): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun formatItem(record: RunRecordEntity): String = String.format(
        Locale.US,
        "%s · %.2fkm · %s · %s",
        formatDate(record.startedAt),
        record.actualDistanceMeters / 1000.0,
        if (record.averagePaceSec > 0) formatPace(record.averagePaceSec) else "--",
        if (record.status == "COMPLETED") "完成" else "中止"
    )

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatPace(secPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)
}
