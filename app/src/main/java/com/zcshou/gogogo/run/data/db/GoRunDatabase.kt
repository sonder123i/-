package com.zcshou.gogogo.run.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RunRouteEntity::class, RunScheduleEntity::class, RunRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class GoRunDatabase : RoomDatabase() {
    abstract fun routeDao(): RunRouteDao
    abstract fun scheduleDao(): RunScheduleDao
    abstract fun recordDao(): RunRecordDao

    companion object {
        @Volatile
        private var instance: GoRunDatabase? = null

        /** v1 -> v2：步频流规格新增 方向/记忆参数/关联 App/记录 routeId 字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_routes ADD COLUMN isClockwise INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastTargetDistanceKm REAL NOT NULL DEFAULT 3.0")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastTargetPaceSecPerKm INTEGER NOT NULL DEFAULT 340")
                db.execSQL("ALTER TABLE run_routes ADD COLUMN lastSmartJitterEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE run_schedules ADD COLUMN linkedTargetAppPkg TEXT")
                db.execSQL("ALTER TABLE run_records ADD COLUMN routeId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE run_records SET routeId = 0")
            }
        }

        /** v2 -> v3：新增当地真实基准海拔 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE run_routes ADD COLUMN baseAltitudeMeters REAL NOT NULL DEFAULT 50.0")
            }
        }

        fun get(context: Context): GoRunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GoRunDatabase::class.java,
                    "go_run.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}