package com.zcshou.gogogo.run.ui.theme

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.TextureMapView

/**
 * 百度地图双生光学底片：浅色「银白建筑制图」 / 深色「深渊岩灰海图」。
 *
 * 为什么不用 `setMapCustomStylePath`：百度只接受个性化地图平台导出的私有格式，
 * 手写 JSON 会被 SDK 判为 `customStyleFile format is incorrect` 并回退默认蓝底
 * （见 logcat 的 `TextureMapView: customStyleFile format is incorrect`）。
 *
 * 因此改用 API 31+ 的 GPU 颜色矩阵 RenderEffect（零逐帧采样、只作用于地图纹理）：
 * 由 [toneMatrix] 合成「保留色相 + 亮度映射」的矩阵，而不是简单灰度。
 *
 * 关键：**不要用三行系数相同的矩阵**（如 0.3 / 0.59 / 0.11）。那等价于亮度灰度转换，
 * 水域、绿地、道路会全部压成同一个灰值，地图直接变黑白。必须让每个通道保留
 * 自己的对角权重，色相才不会被抹掉。
 */
object MapStyleHelper {

    // ---------------------------------------------------------------- 按主题选择

    fun applyForTheme(mapView: MapView) {
        applyMapFilter(mapView, ThemeHelper.isDark(mapView.context))
    }

    fun applyForTheme(mapView: TextureMapView) {
        applyMapFilter(mapView, ThemeHelper.isDark(mapView.context))
    }

    /**
     * 给地图 View 叠加颜色矩阵（API 31+）。
     * 低版本直接回退默认底图，不引入任何降级噪点。
     */
    fun applyMapFilter(view: View, dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val matrix = if (dark) {
                // 深渊海图：**反相亮度、保留色相**。
                // out_c = chromaKeep * (in_c - L) + (1 - L)
                //   亮度 L → 1 - L：浅色陆地变暗、深色地名变亮，标注始终可读；
                //   色差 in_c - L 原样保留：水域仍是蓝、绿地仍是绿，不会翻成互补色。
                // 注意不能用 gain < 1 的纯缩放（例如 0.22×）：那是把整张图压暗，
                // 深色地名会跟着一起变黑，贴在暗底图上直接看不见。
                invertLumaMatrix(chromaKeep = 0.60f)
            } else {
                // 银白制图：保留大部分色相（水是浅蓝、绿地是薄荷绿），整体提亮到纸白，
                // 只做轻微冷调，不再整张去色。
                toneMatrix(
                    saturationKeep = 0.75f,
                    gain = 1f,
                    offsetR = 6f,
                    offsetG = 9f,
                    offsetB = 14f
                )
            }
            view.setRenderEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            )
        }
    }

    // ---------------------------------------------------------------- 矩阵合成

    /**
     * 合成 out = gain * (lum + keep * (in - lum)) + offset 的颜色矩阵。
     *
     * - `lum` 为亮度分量（0.213 / 0.715 / 0.072）；
     * - `saturationKeep = 0` 退化为纯灰度（这正是之前地图变黑白的原因）；
     *   `1` 表示完全不改色相，只做亮度映射；
     * - `gain` 为负时反相亮度，色相按 `keep` 比例保留。
     *   但反相场景请优先用 [invertLumaMatrix]：负增益缩放会让深色地名一起变暗。
     */
    private fun toneMatrix(
        saturationKeep: Float,
        gain: Float,
        offsetR: Float,
        offsetG: Float,
        offsetB: Float
    ): ColorMatrix {
        val k = saturationKeep.coerceIn(0f, 1f)
        val chroma = gain * k
        val luma = gain * (1f - k)
        return ColorMatrix(
            floatArrayOf(
                chroma + luma * LUM_R, luma * LUM_G, luma * LUM_B, 0f, offsetR,
                luma * LUM_R, chroma + luma * LUM_G, luma * LUM_B, 0f, offsetG,
                luma * LUM_R, luma * LUM_G, chroma + luma * LUM_B, 0f, offsetB,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private const val LUM_R = 0.213f
    private const val LUM_G = 0.715f
    private const val LUM_B = 0.072f

    /**
     * 反相亮度、保留色相：`out_c = keep * (in_c - L) + (1 - L)`，L 为亮度。
     *
     * 展开后每个通道的对角权重不同，因此色相不会被抹平（不是灰度矩阵），
     * 也不是简单的负增益反相（那会把色差一起取反，蓝水变褐）。
     *
     * `keep = 1` 完整保留色相；`0` 退化为纯灰度反相。
     */
    private fun invertLumaMatrix(chromaKeep: Float): ColorMatrix {
        val k = chromaKeep.coerceIn(0f, 1f)
        // k*(in_c - L) + 1 - L  =  k*in_c + 1 - (k+1)*L
        // 即：通道系数 = k*I - (k+1)*lumRow，偏移 = 255（矩阵偏移按 0..255 计）
        val w = (k + 1f)
        return ColorMatrix(
            floatArrayOf(
                k - w * LUM_R, -w * LUM_G, -w * LUM_B, 0f, 255f,
                -w * LUM_R, k - w * LUM_G, -w * LUM_B, 0f, 255f,
                -w * LUM_R, -w * LUM_G, k - w * LUM_B, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    // ---------------------------------------------------------------- 显式皮肤

    fun applyDark(mapView: MapView) = applyMapFilter(mapView, true)

    fun applyDark(mapView: TextureMapView) = applyMapFilter(mapView, true)

    fun applyLight(mapView: MapView) = applyMapFilter(mapView, false)

    fun applyLight(mapView: TextureMapView) = applyMapFilter(mapView, false)

    fun disable(mapView: MapView) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mapView.setRenderEffect(null)
    }

    fun disable(mapView: TextureMapView) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mapView.setRenderEffect(null)
    }
}
