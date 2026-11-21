package com.zcshou.gogogo.run.bridge

/**
 * 注入桥梁的提供者：由 ServiceGo.ServiceGoBinder 实现。
 * 调用方（RunSessionService / FreeRoamController）只依赖本接口，
 * 不再强转具体 Binder 类型，保持“run 新代码 ↔ ServiceGo”经 bridge 包单向对接。
 */
interface InjectorProvider {
    val injector: ILocationInjector
}
