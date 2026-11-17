package com.zcshou.gogogo.run.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RunRouteDao {
    @Query("SELECT * FROM run_routes ORDER BY createdAt DESC")
    fun observeRoutes(): Flow<List<RunRouteEntity>>

    @Query("SELECT * FROM run_routes WHERE id = :id")
    suspend fun getById(id: Long): RunRouteEntity?

    @Query("SELECT COUNT(*) FROM run_routes")
    suspend fun count(): Int

    @Insert
    suspend fun insert(route: RunRouteEntity): Long

    @Update
    suspend fun update(route: RunRouteEntity)

    @Delete
    suspend fun delete(route: RunRouteEntity)
}

@Dao
interface RunScheduleDao {
    @Query("SELECT * FROM run_schedules ORDER BY id DESC")
    fun observeSchedules(): Flow<List<RunScheduleEntity>>

    @Query("SELECT * FROM run_schedules WHERE routeId = :routeId ORDER BY id DESC")
    fun observeByRoute(routeId: Long): Flow<List<RunScheduleEntity>>

    @Query("SELECT * FROM run_schedules WHERE isScheduleEnabled = 1")
    suspend fun getEnabled(): List<RunScheduleEntity>

    @Query("SELECT * FROM run_schedules WHERE id = :id")
    suspend fun getById(id: Long): RunScheduleEntity?

    @Insert
    suspend fun insert(schedule: RunScheduleEntity): Long

    @Update
    suspend fun update(schedule: RunScheduleEntity)

    @Delete
    suspend fun delete(schedule: RunScheduleEntity)
}

@Dao
interface RunRecordDao {
    @Query("SELECT * FROM run_records ORDER BY startedAt DESC")
    fun observeRecords(): Flow<List<RunRecordEntity>>

    /** 指定时间戳之后的累计里程（本周概览用） */
    @Query("SELECT COALESCE(SUM(actualDistanceMeters), 0) FROM run_records WHERE startedAt >= :sinceMs AND status = 'COMPLETED'")
    fun observeCompletedMetersSince(sinceMs: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM run_records WHERE startedAt >= :sinceMs AND status = 'COMPLETED'")
    fun observeCompletedCountSince(sinceMs: Long): Flow<Int>

    @Insert
    suspend fun insert(record: RunRecordEntity): Long
}