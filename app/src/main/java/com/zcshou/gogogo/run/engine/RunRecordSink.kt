package com.zcshou.gogogo.run.engine

import com.zcshou.gogogo.run.data.db.RunRecordEntity
import com.zcshou.gogogo.run.data.prefs.RunSnapshot

/**
 * 引擎的持久化出口：把“结束记录落库 / 断点快照保存与清理”抽象成接口，
 * 便于 JVM 单测注入 fake，也避免引擎依赖具体 RunRepository/Room 实现。
 */
interface RunRecordSink {
    /** 写入一次结束记录，返回插入行 id */
    suspend fun addRecord(record: RunRecordEntity): Long
    suspend fun clearSnapshot()
    suspend fun saveSnapshot(snapshot: RunSnapshot)
}
