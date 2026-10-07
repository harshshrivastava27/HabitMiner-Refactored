package com.habitminer.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a database exactly as v1.2.0 (schema 7) left it, then opens it with the current
 * app. Room checks every table against the entities after migrating, so a wrong column type,
 * nullability or name in MIGRATION_7_8 fails here instead of crashing on people's phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    // Copied from Room's exported schema for version 7 (printed by CI).
    private val v7 =
        listOf(
            "CREATE TABLE IF NOT EXISTS `app_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`appName` TEXT NOT NULL, `appCategory` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `timeSlot` TEXT NOT NULL, `dayType` TEXT NOT NULL, `previousPackageName` TEXT, " +
                "`isHistorical` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_app_usage_startTime_endTime` ON `app_usage` (`startTime`, `endTime`)",
            "CREATE INDEX IF NOT EXISTS `index_app_usage_dayType_timeSlot` ON `app_usage` (`dayType`, `timeSlot`)",
            "CREATE INDEX IF NOT EXISTS `index_app_usage_packageName` ON `app_usage` (`packageName`)",
            "CREATE TABLE IF NOT EXISTS `context_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`accelMean` REAL NOT NULL, `accelVariance` REAL NOT NULL, `accelStd` REAL NOT NULL, `accelMin` REAL NOT NULL, " +
                "`accelMax` REAL NOT NULL, `accelEnergy` REAL NOT NULL, `gyroMean` REAL NOT NULL, `gyroVariance` REAL NOT NULL, " +
                "`gyroStd` REAL NOT NULL, `gyroMin` REAL NOT NULL, `gyroMax` REAL NOT NULL, `gyroEnergy` REAL NOT NULL, " +
                "`lightLux` REAL NOT NULL, `proximityNear` INTEGER, `stepsSinceLastSnapshot` INTEGER NOT NULL, " +
                "`batteryLevel` INTEGER NOT NULL, `isCharging` INTEGER NOT NULL, `isScreenOn` INTEGER NOT NULL, " +
                "`unlockCount` INTEGER NOT NULL, `notificationsLastHour` INTEGER NOT NULL, `wifiPlace` TEXT, `sensingMs` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_context_snapshots_timestamp` ON `context_snapshots` (`timestamp`)",
            "CREATE TABLE IF NOT EXISTS `discovered_habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitName` TEXT NOT NULL, " +
                "`patternDescription` TEXT NOT NULL, `appSequence` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                "`occurrenceCount` INTEGER NOT NULL, `timeSlot` TEXT NOT NULL, `dayType` TEXT NOT NULL, " +
                "`discoveredAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `baseline` (`timeBin` TEXT NOT NULL, `avgScreenTimeMs` INTEGER NOT NULL, " +
                "`stdScreenTimeMs` INTEGER NOT NULL, `avgSessionCount` REAL NOT NULL, `stdSessionCount` REAL NOT NULL, " +
                "`avgUnlockCount` REAL NOT NULL, `typicalCategoriesJson` TEXT NOT NULL, `avgAccelEnergy` REAL NOT NULL, " +
                "`avgLightLux` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `dataPointCount` INTEGER NOT NULL, PRIMARY KEY(`timeBin`))",
            "CREATE TABLE IF NOT EXISTS `deviations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`timeBin` TEXT NOT NULL, `deviationType` TEXT NOT NULL, `description` TEXT NOT NULL, `zScore` REAL NOT NULL, " +
                "`normalizedScore` REAL NOT NULL, `affectedCategory` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_deviations_timestamp` ON `deviations` (`timestamp`)",
            "CREATE TABLE IF NOT EXISTS `device_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventType` TEXT NOT NULL, " +
                "`packageName` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_device_events_eventType_timestamp` ON `device_events` (`eventType`, `timestamp`)",
            "CREATE TABLE IF NOT EXISTS `user_labels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `refKey` TEXT, `promptedAt` INTEGER, `contextJson` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_user_labels_kind_timestamp` ON `user_labels` (`kind`, `timestamp`)",
            "CREATE INDEX IF NOT EXISTS `index_user_labels_refKey` ON `user_labels` (`refKey`)",
            "CREATE TABLE IF NOT EXISTS `places` (`placeHash` TEXT NOT NULL, `label` TEXT, `firstSeen` INTEGER NOT NULL, " +
                "`lastSeen` INTEGER NOT NULL, PRIMARY KEY(`placeHash`))",
        )

    private fun createVersion7() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            v7.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.execSQL(
            "INSERT INTO context_snapshots (timestamp, accelMean, accelVariance, accelStd, accelMin, accelMax, accelEnergy, " +
                "gyroMean, gyroVariance, gyroStd, gyroMin, gyroMax, gyroEnergy, lightLux, proximityNear, stepsSinceLastSnapshot, " +
                "batteryLevel, isCharging, isScreenOn, unlockCount, notificationsLastHour, wifiPlace, sensingMs) " +
                "VALUES (1000, 9.8, 0.1, 0.3, 9.5, 10.1, 96.0, 0.1, 0.01, 0.1, 0, 0.3, 0.02, 120, 0, 42, 80, 0, 1, 5, 3, NULL, 900)",
        )
        helper.close()
    }

    @Test
    fun `version 7 database migrates to 9 and keeps its readings`() =
        runBlocking {
            createVersion7()
            val db =
                Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
                    .allowMainThreadQueries()
                    .build()
            try {
                val rows = db.contextDao().getAllSnapshots().first()
                assertEquals(1, rows.size)
                assertEquals(42, rows[0].stepsSinceLastSnapshot)
                assertEquals(900L, rows[0].sensingMs)
                assertEquals(-1, rows[0].recentSteps)
            } finally {
                db.close()
            }
        }
}
