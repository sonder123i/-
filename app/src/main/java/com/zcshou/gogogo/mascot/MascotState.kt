package com.zcshou.gogogo.mascot

/** 静态素材覆盖的六种状态；未来换成 Lottie/Rive 时沿用同一套状态语义。 */
enum class MascotState {
    PEEK_IDLE,
    RUN,
    CHEER,
    STARE,
    SLEEP,
    AVATAR,
    AVATAR_STARE
}