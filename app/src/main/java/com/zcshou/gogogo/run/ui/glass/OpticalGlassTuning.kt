package com.zcshou.gogogo.run.ui.glass

import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView

/**
 * V6.3 液态玻璃唯一调参入口。
 *
 * 目标不是“白卡片”，而是 Apple iOS 26 的材质行为：
 * - 自适应染色：亮背景压一点烟灰，暗背景提一点乳白；
 * - 更低遮蔽 + 更强模糊/饱和，让背景真正参与材质；
 * - 更厚的边缘透镜（bevel / refraction）+ 极细色散；
 * - 投影交给 View outline elevation，避免库内置 0.75α 硬阴影。
 *
 * 需要整体调风格时只改这里。
 */
object OpticalGlassTuning {

    /** 边缘色散：0.08~0.12 为克制档，0.10 是当前基准。 */
    const val DISPERSION = 0.10f

    const val BEVEL_DP = 16f
    const val REFRACTION_DP = 10f
    const val NAV_BEVEL_DP = 12f
    const val NAV_REFRACTION_DP = 8f

    /** 0.24 比库默认 0.0625 更接近 iOS 的乳白磨砂。 */
    const val BLUR_AMOUNT = 0.24f
    /** 1.72 倍饱和，接近网页玻璃的 saturate(1.8)。 */
    const val SATURATION = 172f

    fun apply(
        view: LiquidGlassView,
        bevelDp: Float = BEVEL_DP,
        refractionDp: Float = REFRACTION_DP
    ) {
        val density = view.resources.displayMetrics.density
        view.material = GlassMaterial.REGULAR
        view.bevelWidth = bevelDp * density
        view.refractionHeight = refractionDp * density
        view.refractionFalloff = 2f
        view.dispersionStrength = DISPERSION
        view.enableSensorHighlight = true
        view.enableAdaptiveTint = isNight(view)
        view.blurAmount = BLUR_AMOUNT
        view.saturation = SATURATION
        view.enableShadow = false
        view.enablePressEffect = true
    }

    /**
     * 地图页面板专用：地图本身有大量道路/水系颜色，饱和度不能拉满，
     * 否则一层薄玻璃会把地图的蓝绿放大成“彩色玻璃”。这里降饱和、加重遮蔽。
     */
    fun applyMapPanel(view: LiquidGlassView) {
        apply(view, bevelDp = 14f, refractionDp = 8f)
        view.blurAmount = 0.20f
        view.saturation = 118f
        view.enableAdaptiveTint = false
        // TextureMapView 的捕获带蓝色偏移，单靠 absorption tint 无法中和；
        // 在内容层加 90% 中性底衬，保留边缘折射/高光，同时止住“蓝玻璃”。
        val child = (view as? android.view.ViewGroup)?.getChildAt(0)
        if (child != null) {
            val density = view.resources.displayMetrics.density
            child.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(
                    if (isNight(view)) 0xE61C1C1E.toInt() else 0xE6F5F7FA.toInt()
                )
            }
        }
    }

    private fun isNight(view: LiquidGlassView): Boolean {
        val mode = view.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
