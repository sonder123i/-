package com.zcshou.gogogo.mascot

import android.widget.ImageView

/** 静态素材播放器：切换透明 WebP，并把播放暂停/释放委托给资源加载器。 */
class StaticMascotPlayer(private val imageView: ImageView) : MascotPlayer {

    private var current: MascotState? = null

    override fun show(state: MascotState) {
        if (current == state) return
        current = state
        MascotAssetLoader.load(imageView, MascotAssets.drawableFor(state))
    }

    override fun stop() {
        MascotAssetLoader.stop(imageView)
        // 允许恢复时重新加载（未来动画 WebP 需要重新 start）。
        current = null
    }

    override fun release() {
        stop()
        current = null
    }
}