package com.zcshou.gogogo.run.ui.theme

import android.content.Context
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.TextureMapView
import java.io.File

/**
 * 百度地图双生皮肤：暗色「深海海图」与浅色「银灰建筑制图」。
 *
 * TextureMapView（V4.0 建圈器/漫游页）与 MapView 是两套独立类型，
 * 但样式方法同名，这里用同一套落盘逻辑分别适配。
 * 样式文件失败（无 AK / JSON 解析异常）时静默回退默认底图。
 */
object MapStyleHelper {

    private const val ASSET_DARK = "map_style_dark.json"
    private const val ASSET_LIGHT = "map_style_light.json"

    // ---------------------------------------------------------------- 按主题选择

    fun applyForTheme(mapView: MapView) {
        if (ThemeHelper.isDark(mapView.context)) applyDark(mapView) else applyLight(mapView)
    }

    fun applyForTheme(mapView: TextureMapView) {
        if (ThemeHelper.isDark(mapView.context)) applyDark(mapView) else applyLight(mapView)
    }

    // ---------------------------------------------------------------- 显式皮肤

    fun applyDark(mapView: MapView) = applyStyle(
        mapView.context, ASSET_DARK,
        mapView::setMapCustomStylePath, mapView::setMapCustomStyleEnable
    )

    fun applyDark(mapView: TextureMapView) = applyStyle(
        mapView.context, ASSET_DARK,
        mapView::setMapCustomStylePath, mapView::setMapCustomStyleEnable
    )

    fun applyLight(mapView: MapView) = applyStyle(
        mapView.context, ASSET_LIGHT,
        mapView::setMapCustomStylePath, mapView::setMapCustomStyleEnable
    )

    fun applyLight(mapView: TextureMapView) = applyStyle(
        mapView.context, ASSET_LIGHT,
        mapView::setMapCustomStylePath, mapView::setMapCustomStyleEnable
    )

    fun disable(mapView: MapView) = runCatching { mapView.setMapCustomStyleEnable(false) }

    fun disable(mapView: TextureMapView) =
        runCatching { mapView.setMapCustomStyleEnable(false) }

    // ---------------------------------------------------------------- 实现

    private fun applyStyle(
        context: Context,
        asset: String,
        setPath: (String) -> Unit,
        setEnabled: (Boolean) -> Unit
    ) {
        runCatching {
            // 每次都覆盖落盘：App 升级更新样式 JSON 后，旧拷贝不会永久生效
            context.assets.open(asset).use { input ->
                File(context.filesDir, asset).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            setPath(File(context.filesDir, asset).absolutePath)
            setEnabled(true)
        }
    }
}
