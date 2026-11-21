package com.zcshou.gogogo.mascot

/**
 * 伴侣播放层。
 *
 * 现在由 [StaticMascotPlayer] 用静态透明 WebP 实现；以后拿到 Lottie/Rive
 * 源文件时，只需提供新的实现并在 [MascotPeekView] 里替换默认 player，
 * 页面、设置和开关逻辑都不需要动。
 */
interface MascotPlayer {
    fun show(state: MascotState)
    fun stop()
    fun release()
}