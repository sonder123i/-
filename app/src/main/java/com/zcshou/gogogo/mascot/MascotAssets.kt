package com.zcshou.gogogo.mascot

import com.zcshou.gogogo.R

/** 状态到静态素材的映射；素材缺失时回退到探头。 */
internal object MascotAssets {

    fun drawableFor(state: MascotState): Int = when (state) {
        MascotState.PEEK_IDLE -> R.drawable.jing_peek_idle
        MascotState.RUN -> R.drawable.jing_run_frame1
        MascotState.CHEER -> R.drawable.jing_cheer
        MascotState.STARE -> R.drawable.jing_stare
        MascotState.SLEEP -> R.drawable.jing_sleep
        MascotState.AVATAR -> R.drawable.jing_avatar_badge
        MascotState.AVATAR_STARE -> R.drawable.jing_avatar_stare
    }
}