package com.zcshou.gogogo.run.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 断点续跑快照：引擎每 5 秒写入一次；服务被系统回收重启后，
 * 10 分钟内的未完成快照可自动加载续跑。
 */
data class RunSnapshot(
    val routeId: Long,
    val targetDistanceMeters: Double,
    val accumulatedMeters: Double,
    val currentLapIndex: Int,
    val lastUpdatedTimestamp: Long
)

private val Context.runSnapshotDataStore by preferencesDataStore(name = "run_snapshot")

class RunSnapshotStore(private val context: Context) {

    private object Keys {
        val ROUTE_ID = longPreferencesKey("route_id")
        val TARGET_METERS = doublePreferencesKey("target_meters")
        val ACCUMULATED_METERS = doublePreferencesKey("accumulated_meters")
        val LAP_INDEX = intPreferencesKey("lap_index")
        val UPDATED_AT = longPreferencesKey("updated_at")
    }

    val snapshotFlow: Flow<RunSnapshot?> = context.runSnapshotDataStore.data.map { prefs ->
        val updatedAt = prefs[Keys.UPDATED_AT] ?: return@map null
        RunSnapshot(
            routeId = prefs[Keys.ROUTE_ID] ?: return@map null,
            targetDistanceMeters = prefs[Keys.TARGET_METERS] ?: 0.0,
            accumulatedMeters = prefs[Keys.ACCUMULATED_METERS] ?: 0.0,
            currentLapIndex = prefs[Keys.LAP_INDEX] ?: 0,
            lastUpdatedTimestamp = updatedAt
        )
    }

    suspend fun save(snapshot: RunSnapshot) {
        context.runSnapshotDataStore.edit { prefs ->
            prefs[Keys.ROUTE_ID] = snapshot.routeId
            prefs[Keys.TARGET_METERS] = snapshot.targetDistanceMeters
            prefs[Keys.ACCUMULATED_METERS] = snapshot.accumulatedMeters
            prefs[Keys.LAP_INDEX] = snapshot.currentLapIndex
            prefs[Keys.UPDATED_AT] = snapshot.lastUpdatedTimestamp
        }
    }

    suspend fun clear() {
        context.runSnapshotDataStore.edit { it.clear() }
    }

    companion object {
        const val RESUME_WINDOW_MS = 10 * 60 * 1000L

        fun isResumable(snapshot: RunSnapshot?, nowMs: Long = System.currentTimeMillis()): Boolean =
            snapshot != null &&
                snapshot.accumulatedMeters < snapshot.targetDistanceMeters &&
                nowMs - snapshot.lastUpdatedTimestamp in 0..RESUME_WINDOW_MS
    }
}