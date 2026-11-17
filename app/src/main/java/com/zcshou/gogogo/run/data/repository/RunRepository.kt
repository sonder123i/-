package com.zcshou.gogogo.run.data.repository

import android.content.Context
import com.zcshou.gogogo.run.data.db.GoRunDatabase
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import com.zcshou.gogogo.run.data.prefs.RunSnapshotStore
import com.zcshou.gogogo.run.engine.TrackMathUtils
import com.zcshou.gogogo.run.engine.TrackTemplates
import com.zcshou.gogogo.run.engine.toWgsJson
import kotlinx.coroutines.flow.Flow

/**
 * run 模块统一数据仓库：Room（路线/计划/历史）+ DataStore（断点快照）。
 */
class RunRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = GoRunDatabase.get(context)
    private val snapshotStore = RunSnapshotStore(context.applicationContext)

    // --- 路线 ---
    fun observeRoutes(): Flow<List<RunRouteEntity>> = db.routeDao().observeRoutes()
    suspend fun getRoute(id: Long): RunRouteEntity? = db.routeDao().getById(id)
    suspend fun saveRoute(route: RunRouteEntity): Long = db.routeDao().insert(route)
    suspend fun updateRoute(route: RunRouteEntity) = db.routeDao().update(route)
    suspend fun deleteRoute(route: RunRouteEntity) = db.routeDao().delete(route)

    /** 首次安装预置一条 400m 示范跑道，装完即可一键开跑 */
    suspend fun seedDemoRouteIfNeeded() {
        val prefs = appContext.getSharedPreferences("pf_onboarding", Context.MODE_PRIVATE)
        if (prefs.getBoolean("demo_route_seeded", false)) return
        if (db.routeDao().count() > 0) {
            prefs.edit().putBoolean("demo_route_seeded", true).apply()
            return
        }
        val center = TrackMathUtils.LatLng(39.9042, 116.4074)
        val points = TrackTemplates.transform(
            TrackTemplates.template("400").localPoints,
            center.lat, center.lng, 1.0, 0.0
        )
        val lap = TrackMathUtils.cumulativeDistances(points).last()
        db.routeDao().insert(
            RunRouteEntity(
                name = "400m 标准田径场（示范）",
                buildMode = "DEMO",
                singleLapMeters = lap,
                isClockwise = true,
                denseWaypointsJson = points.toWgsJson(),
                anchorNodesJson = "[]",
                lastTargetDistanceKm = 2.0f,
                lastTargetPaceSecPerKm = 330,
                baseAltitudeMeters = 50.0
            )
        )
        prefs.edit().putBoolean("demo_route_seeded", true).apply()
    }

    // --- 定时计划 ---
    fun observeSchedules(): Flow<List<RunScheduleEntity>> = db.scheduleDao().observeSchedules()
    fun observeSchedulesByRoute(routeId: Long): Flow<List<RunScheduleEntity>> =
        db.scheduleDao().observeByRoute(routeId)
    suspend fun getEnabledSchedules(): List<RunScheduleEntity> = db.scheduleDao().getEnabled()
    suspend fun getSchedule(id: Long): RunScheduleEntity? = db.scheduleDao().getById(id)
    suspend fun saveSchedule(schedule: RunScheduleEntity): Long = db.scheduleDao().insert(schedule)
    suspend fun updateSchedule(schedule: RunScheduleEntity) = db.scheduleDao().update(schedule)
    suspend fun deleteSchedule(schedule: RunScheduleEntity) = db.scheduleDao().delete(schedule)

    // --- 历史记录 ---
    fun observeRecords(): Flow<List<RunRecordEntity>> = db.recordDao().observeRecords()
    fun observeCompletedMetersSince(sinceMs: Long): Flow<Double> =
        db.recordDao().observeCompletedMetersSince(sinceMs)
    fun observeCompletedCountSince(sinceMs: Long): Flow<Int> =
        db.recordDao().observeCompletedCountSince(sinceMs)
    suspend fun addRecord(record: RunRecordEntity): Long = db.recordDao().insert(record)

    // --- 断点快照 ---
    val snapshotFlow: Flow<RunSnapshot?> = snapshotStore.snapshotFlow
    suspend fun saveSnapshot(snapshot: RunSnapshot) = snapshotStore.save(snapshot)
    suspend fun clearSnapshot() = snapshotStore.clear()
}