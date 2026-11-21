package com.zcshou.gogogo.run.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移回归：用入库的 schema JSON 逐版本建库、灌数、跑迁移，由 Room 校验表结构。
 * 迁移写错（列类型/约束不符）会让升级用户数据库直接打不开、路线数据不可恢复，
 * 因此必须用 MigrationTestHelper 对照导出的 schema 验证，而不是只确认 ALTER 能执行。
 */
@RunWith(AndroidJUnit4::class)
class GoRunDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GoRunDatabase::class.java
    )

    @Test
    fun migrate1To2_keepsRowsAndFillsDefaults() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO run_routes (name, buildMode, singleLapMeters, isClosedLoop, denseWaypointsJson, anchorNodesJson, createdAt) " +
                    "VALUES ('操场圈', 'TRACK_SPLINE', 400.0, 1, '[]', '[]', 0)"
            )
            db.execSQL(
                "INSERT INTO run_records (routeName, actualDistanceMeters, durationSeconds, averagePaceSec, startedAt, status) " +
                    "VALUES ('操场圈', 1000.0, 300, 300, 0, 'COMPLETED')"
            )
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true, GoRunDatabase.MIGRATION_1_2)
        db.query(
            "SELECT isClockwise, lastTargetDistanceKm, lastTargetPaceSecPerKm, lastSmartJitterEnabled FROM run_routes"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(3.0f, c.getFloat(1), 1e-6f)
            assertEquals(340, c.getInt(2))
            assertEquals(1, c.getInt(3))
        }
        db.query("SELECT routeId FROM run_records").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_addsBaseAltitudeDefault() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO run_routes (name, buildMode, singleLapMeters, isClosedLoop, isClockwise, denseWaypointsJson, anchorNodesJson, lastTargetDistanceKm, lastTargetPaceSecPerKm, lastSmartJitterEnabled, createdAt) " +
                    "VALUES ('操场圈', 'TRACK_SPLINE', 400.0, 1, 1, '[]', '[]', 3.0, 340, 1, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, GoRunDatabase.MIGRATION_2_3)
        db.query("SELECT baseAltitudeMeters FROM run_routes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(50.0, c.getDouble(0), 1e-9)
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "go-run-migration-test.db"
    }
}
