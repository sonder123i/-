package com.zcshou.gogogo.mascot

/** 伴侣显示模式。 */
enum class MascotMode {
    /** 完全关闭：所有页面保持原样，零占位、零动画。 */
    OFF,

    /** 轻度陪伴：首页空状态、体检、设置、赞赏页探头。 */
    LIGHT,

    /** 深度伴侣：在 LIGHT 基础上再加地图小人。 */
    FULL;

    companion object {
        fun fromName(raw: String?): MascotMode =
            values().firstOrNull { it.name == raw } ?: OFF
    }
}