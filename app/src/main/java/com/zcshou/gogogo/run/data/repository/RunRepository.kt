package com.zcshou.gogogo.run.data.repository

import android.content.Context
import com.zcshou.gogogo.run.data.db.GoRunDatabase
import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.data.db.RunScheduleEntity
import com.zcshou.gogogo.run.data.db.RunUsageStats
import com.zcshou.gogogo.run.data.prefs.RunSnapshot
import com.zcshou.gogogo.run.data.prefs.RunSnapshotStore
import com.zcshou.gogogo.run.engine.RunRecordSink
import kotlinx.coroutines.flow.Flow

/**
 * run 模块统一数据仓库：Room（路线/计划/历史）+ DataStore（断点快照）。
 */
class RunRepository(context: Context) : RunRecordSink {

    private val db = GoRunDatabase.get(context)
    private val snapshotStore = RunSnapshotStore(context.applicationContext)

    // --- 路线 ---
    fun observeRoutes(): Flow<List<RunRouteEntity>> = db.routeDao().observeRoutes()
    suspend fun getRoute(id: Long): RunRouteEntity? = db.routeDao().getById(id)
    suspend fun saveRoute(route: RunRouteEntity): Long = db.routeDao().insert(route)
    suspend fun updateRoute(route: RunRouteEntity) = db.routeDao().update(route)
    suspend fun deleteRoute(route: RunRouteEntity) = db.routeDao().delete(route)
    suspend fun routeCount(): Int = db.routeDao().count()

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
    override suspend fun addRecord(record: RunRecordEntity): Long = db.recordDao().insert(record)

    /** 支持云迹邀请的触发依据：已完成跑次、累计里程与首末完成时间。 */
    suspend fun usageStats(): RunUsageStats = db.recordDao().completedStats()

    // --- 断点快照 ---
    val snapshotFlow: Flow<RunSnapshot?> = snapshotStore.snapshotFlow
    override suspend fun saveSnapshot(snapshot: RunSnapshot) = snapshotStore.save(snapshot)
    override suspend fun clearSnapshot() = snapshotStore.clear()
}