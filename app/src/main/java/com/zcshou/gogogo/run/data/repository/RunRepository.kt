package com.zcshou.gogogo.run.data.repository

import android.content.Context
import com.zcshou.gogogo.run.data.db.GoRunDatabase
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import com.zcshou.gogogo.run.data.prefs.RunSnapshotStore
import kotlinx.coroutines.flow.Flow

/**
 * run 模块统一数据仓库：Room（路线/计划/历史）+ DataStore（断点快照）。
 */
class RunRepository(context: Context) {

    private val db = GoRunDatabase.get(context)
    private val snapshotStore = RunSnapshotStore(context.applicationContext)

    // --- 路线 ---
    fun observeRoutes(): Flow<List<RunRouteEntity>> = db.routeDao().observeRoutes()
    suspend fun getRoute(id: Long): RunRouteEntity? = db.routeDao().getById(id)
    suspend fun saveRoute(route: RunRouteEntity): Long = db.routeDao().insert(route)
    suspend fun updateRoute(route: RunRouteEntity) = db.routeDao().update(route)
    suspend fun deleteRoute(route: RunRouteEntity) = db.routeDao().delete(route)

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