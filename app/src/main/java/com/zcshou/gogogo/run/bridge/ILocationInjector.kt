package com.zcshou.gogogo.run.bridge

/**
 * 新旧代码唯一桥梁：原版 ServiceGo 实现本接口，run 引擎只依赖此接口注入坐标。
 * 所有经纬度一律为 WGS-84。
 */
interface ILocationInjector {

    /**
     * 立即向 GPS_PROVIDER 与 NETWORK_PROVIDER（若设备支持）双通道注入一帧坐标，
     * 完整填充 elapsedRealtimeNanos / verticalAccuracy / speedAccuracy / bearingAccuracy。
     */
    fun injectLocation(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speedMps: Float,
        bearingDegrees: Float,
        accuracyMeters: Float,
        verticalAccuracyMeters: Float,
        speedAccuracyMps: Float,
        bearingAccuracyDegrees: Float
    )

    /**
     * 引擎接管开关：true 时暂停原版摇杆注入循环并隐藏摇杆，false 时恢复原版手动模式。
     */
    fun setEngineDriven(enabled: Boolean)

    /**
     * 停止注入并移除测试 Provider，恢复手机真实定位。
     */
    fun releaseInjection()
}