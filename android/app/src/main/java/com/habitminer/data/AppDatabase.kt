package com.habitminer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppUsageEntity::class,
        ContextSnapshotEntity::class,
        DiscoveredHabitEntity::class,
        BaselineEntity::class,
        DeviationEntity::class,
        DeviceEventEntity::class,
        UserLabelEntity::class,
        PlaceEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appUsageDao(): AppUsageDao

    abstract fun contextDao(): ContextDao

    abstract fun habitDao(): HabitDao

    abstract fun baselineDao(): BaselineDao

    abstract fun deviationDao(): DeviationDao

    abstract fun deviceEventDao(): DeviceEventDao

    abstract fun labelDao(): LabelDao

    abstract fun placeDao(): PlaceDao

    abstract fun batchDao(): BatchDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                val newInstance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "habitminer_database",
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                        .build()
                instance = newInstance
                newInstance
            }
        }

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS device_events (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "eventType TEXT NOT NULL, " +
                            "packageName TEXT, " +
                            "timestamp INTEGER NOT NULL)",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_device_events_timestamp ON device_events(timestamp)",
                    )
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Drop the old single-column index on device_events and add the composite one
                    database.execSQL("DROP INDEX IF EXISTS index_device_events_timestamp")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_device_events_eventType_timestamp ON device_events(eventType, timestamp)",
                    )

                    // Add indices for app_usage
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_startTime_endTime ON app_usage(startTime, endTime)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_dayType_timeSlot ON app_usage(dayType, timeSlot)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_packageName ON app_usage(packageName)")

                    // Add index for context_snapshots
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_context_snapshots_timestamp ON context_snapshots(timestamp)")

                    // Add index for deviations
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_deviations_timestamp ON deviations(timestamp)")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE app_usage ADD COLUMN previousPackageName TEXT DEFAULT NULL")

                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS context_snapshots_new (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "timestamp INTEGER NOT NULL, " +
                            "accelMean REAL NOT NULL, " +
                            "accelVariance REAL NOT NULL, " +
                            "accelStd REAL NOT NULL, " +
                            "accelMin REAL NOT NULL, " +
                            "accelMax REAL NOT NULL, " +
                            "accelEnergy REAL NOT NULL, " +
                            "lightLux REAL NOT NULL, " +
                            "batteryLevel INTEGER NOT NULL, " +
                            "isCharging INTEGER NOT NULL, " +
                            "isScreenOn INTEGER NOT NULL, " +
                            "unlockCount INTEGER NOT NULL, " +
                            "notificationsLastHour INTEGER NOT NULL)",
                    )
                    database.execSQL(
                        "INSERT INTO context_snapshots_new " +
                            "(id, timestamp, accelMean, accelVariance, accelStd, accelMin, accelMax, " +
                            "accelEnergy, lightLux, batteryLevel, isCharging, isScreenOn, unlockCount, notificationsLastHour) " +
                            "SELECT id, timestamp, -1, -1, -1, -1, -1, -1, lightLevel, " +
                            "batteryLevel, isCharging, isScreenOn, unlockCount, notificationCount " +
                            "FROM context_snapshots",
                    )
                    database.execSQL("DROP TABLE context_snapshots")
                    database.execSQL("ALTER TABLE context_snapshots_new RENAME TO context_snapshots")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_context_snapshots_timestamp ON context_snapshots(timestamp)")

                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS baseline_new (" +
                            "timeBin TEXT PRIMARY KEY NOT NULL, " +
                            "avgScreenTimeMs INTEGER NOT NULL, " +
                            "stdScreenTimeMs INTEGER NOT NULL, " +
                            "avgSessionCount REAL NOT NULL, " +
                            "stdSessionCount REAL NOT NULL, " +
                            "avgUnlockCount REAL NOT NULL, " +
                            "typicalCategoriesJson TEXT NOT NULL, " +
                            "avgAccelEnergy REAL NOT NULL, " +
                            "avgLightLux REAL NOT NULL, " +
                            "updatedAt INTEGER NOT NULL, " +
                            "dataPointCount INTEGER NOT NULL)",
                    )
                    database.execSQL(
                        "INSERT INTO baseline_new (timeBin, avgScreenTimeMs, stdScreenTimeMs, " +
                            "avgSessionCount, stdSessionCount, avgUnlockCount, typicalCategoriesJson, " +
                            "avgAccelEnergy, avgLightLux, updatedAt, dataPointCount) " +
                            "SELECT timeBin, avgScreenTimeMs, stdScreenTimeMs, avgSessionCount, " +
                            "stdSessionCount, avgUnlockCount, typicalCategoriesJson, -1, avgLightLevel, " +
                            "updatedAt, dataPointCount FROM baseline",
                    )
                    database.execSQL("DROP TABLE baseline")
                    database.execSQL("ALTER TABLE baseline_new RENAME TO baseline")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroMean REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroVariance REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroStd REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroMin REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroMax REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN gyroEnergy REAL NOT NULL DEFAULT -1.0")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN proximityNear INTEGER DEFAULT NULL")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN stepsSinceLastSnapshot INTEGER NOT NULL DEFAULT -1")
                }
            }
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE app_usage ADD COLUMN isHistorical INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** v1.2: check-in/feedback labels, Wi-Fi places, and sensing cost per snapshot. */
        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN wifiPlace TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN sensingMs INTEGER NOT NULL DEFAULT 0")
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS user_labels (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "timestamp INTEGER NOT NULL, " +
                            "kind TEXT NOT NULL, " +
                            "value TEXT NOT NULL, " +
                            "refKey TEXT, " +
                            "promptedAt INTEGER, " +
                            "contextJson TEXT)",
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_labels_kind_timestamp ON user_labels(kind, timestamp)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_labels_refKey ON user_labels(refKey)")
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS places (" +
                            "placeHash TEXT PRIMARY KEY NOT NULL, " +
                            "label TEXT, " +
                            "firstSeen INTEGER NOT NULL, " +
                            "lastSeen INTEGER NOT NULL)",
                    )
                }
            }

        /** v9 (Extended): index for MAX(endTime), which runs on every collection. */
        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_endTime ON app_usage(endTime)")
                }
            }

        /** v8: steps in the two minutes before each reading, used to tell walking from still. */
        internal val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE context_snapshots ADD COLUMN recentSteps INTEGER NOT NULL DEFAULT -1")
                }
            }
    }
}
