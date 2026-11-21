package com.zcshou.gogogo.run.service

/**
 * 玻璃渲染闸门：巡航 / 锁屏时冻结装饰性玻璃卡片渲染（注销传感器高光、停止连续重绘）。
 *
 * 服务层只依赖本接口；UI 层的 DeepRunGlassManager 在初始化时通过 [register] 注册实现，
 * 以此消除 run/service → run/ui 的反向依赖（依赖方向：ui 实现服务定义的接缝，与
 * bridge/ILocationInjector 同一套思路）。
 */
interface RenderGate {
    fun setRenderSuppressed(suppressed: Boolean, reason: String)

    companion object {
        @Volatile
        private var impl: RenderGate? = null

        /** 由 UI 层实现方在初始化时调用；object 实现与应用同生命周期，无需注销 */
        fun register(gate: RenderGate) {
            impl = gate
        }

        fun setRenderSuppressed(suppressed: Boolean, reason: String) {
            impl?.setRenderSuppressed(suppressed, reason)
        }
    }
}
