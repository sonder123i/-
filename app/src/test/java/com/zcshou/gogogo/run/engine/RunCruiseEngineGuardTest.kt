package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.bridge.ILocationInjector
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 引擎健壮性回归：
 * 1) 释放路径的结束记录落库不受 scope.cancel 影响（服务被回收时记录不丢）；
 * 2) 退化轨迹（lapLength=0）上插点被拒绝，不产生 NaN 注入帧。
 */
class RunCruiseEngineGuardTest {

    private class FakeClock {
        var elapsedMs = 1_000L
        var wallMs = 1_700_000_000_000L
        var nanos = 1_000_000_000L
        fun advance(ms: Long) {
            elapsedMs += ms
            wallMs += ms
            nanos += ms * 1_000_000
        }
    }

    private class FakeEnv(private val clock: FakeClock) : EngineEnv {
        override fun isMockLocationAllowed(): Boolean = true
        override fun vibrate() {}
        override fun elapsedRealtimeMs(): Long = clock.elapsedMs
        override fun wallTimeMs(): Long = clock.wallMs
        override fun nanoTime(): Long = clock.nanos
    }

    private class RecordingSink : RunRecordSink {
        val records = java.util.concurrent.CopyOnWriteArrayList<RunRecordEntity>()
        override suspend fun addRecord(record: RunRecordEntity): Long {
            records += record
            return record.id
        }

        override suspend fun clearSnapshot() {}
        override suspend fun saveSnapshot(snapshot: RunSnapshot) {}
    }

    private class RecordingInjector : ILocationInjector {
        val frames = java.util.concurrent.CopyOnWriteArrayList<TrackMathUtils.LatLng>()
        override fun injectLocation(
            latitude: Double, longitude: Double, altitude: Double,
            speedMps: Float, bearingDegrees: Float, accuracyMeters: Float,
            verticalAccuracyMeters: Float, speedAccuracyMps: Float,
            bearingAccuracyDegrees: Float
        ) {
            frames += TrackMathUtils.LatLng(latitude, longitude)
        }

        override fun setEngineDriven(enabled: Boolean) {}
        override fun releaseInjection() {}
    }

    private val center = TrackMathUtils.LatLng(36.667662, 117.027707)

    private fun ovalRoute(id: Long = 7): RunRouteEntity = RunRouteEntity(
        id = id,
        name = "测试圈",
        buildMode = "TRACK_SPLINE",
        singleLapMeters = TrackMathUtils.cumulativeDistances(
            TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)
        ).last(),
        isClockwise = true,
        denseWaypointsJson = "",
        anchorNodesJson = "[]",
        baseAltitudeMeters = 55.0
    )

    private fun ovalPoints(): List<TrackMathUtils.LatLng> =
        TrackMathUtils.catmullRomClosed(TrackFactory.ovalAnchors(center), 2.0)

    /** 全部重复点：cumulative.last() == 0，构造 lapLength == 0 的退化轨迹 */
    private fun degenerateRoute(): RunRouteEntity = RunRouteEntity(
        id = 9,
        name = "退化圈",
        buildMode = "TRACK_SPLINE",
        singleLapMeters = 0.0,
        isClockwise = true,
        denseWaypointsJson = "",
        anchorNodesJson = "[]",
        baseAltitudeMeters = 55.0
    )

    private fun degeneratePoints(): List<TrackMathUtils.LatLng> =
        List(6) { TrackMathUtils.LatLng(36.667662, 117.027707) }

    private suspend fun awaitPhase(engine: RunCruiseEngine, phase: RunPhase) {
        withTimeout(10_000) {
            while (engine.state.value.phase != phase) delay(5)
        }
    }

    @Test
    fun insertDetour_onZeroLapLength_isRejectedWithoutNaN() = runBlocking {
        val injector = RecordingInjector()
        val engine = RunCruiseEngine(
            env = FakeEnv(FakeClock()),
            injector = injector,
            repository = null,
            tickMillis = 5L,
            random = Random(7),
            settleMillis = 20L,
            randomStartSlice = false
        )
        engine.startWithPoints(
            degenerateRoute(), degeneratePoints(), 0.05f, 340, 0,
            enableLaneDrift = false, enableCooldownWalk = true
        )
        awaitPhase(engine, RunPhase.RUNNING)
        engine.insertDetour(36.0, 117.0)
        delay(100)
        assertFalse("退化轨迹上插点应被拒绝", engine.state.value.phase == RunPhase.DETOUR_POINT)
        assertTrue(
            "不得注入 NaN 坐标",
            injector.frames.none { java.lang.Double.isNaN(it.lat) || java.lang.Double.isNaN(it.lng) }
        )
        engine.release()
    }

    @Test
    fun release_persistsAbortedRecordDespiteScopeCancel() = runBlocking {
        val sink = RecordingSink()
        val engine = RunCruiseEngine(
            env = FakeEnv(FakeClock()),
            injector = RecordingInjector(),
            repository = sink,
            tickMillis = 5L,
            random = Random(7),
            settleMillis = 20L,
            randomStartSlice = false
        )
        engine.startWithPoints(
            ovalRoute(), ovalPoints(), 0.5f, 340, 0,
            enableLaneDrift = false, enableCooldownWalk = true
        )
        awaitPhase(engine, RunPhase.RUNNING)
        // 模拟服务被系统回收：release() 会 cancel 主 scope，收尾记录仍必须落库
        engine.release()
        withTimeout(10_000) {
            while (sink.records.isEmpty()) delay(5)
        }
        assertEquals("释放路径应落一条 ABORTED 记录", 1, sink.records.size)
        assertEquals(7L, sink.records.first().routeId)
        assertEquals("ABORTED", sink.records.first().status)
    }
}
