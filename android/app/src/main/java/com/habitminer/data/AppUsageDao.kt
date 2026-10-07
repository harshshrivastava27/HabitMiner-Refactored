package com.habitminer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class CategoryDuration(
    val appCategory: String,
    val totalDuration: Long,
)

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: AppUsageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(usages: List<AppUsageEntity>)

    @Query("SELECT * FROM app_usage WHERE dayType = :dayType AND timeSlot = :timeSlot ORDER BY startTime DESC")
    fun getUsageForTimeBin(
        dayType: String,
        timeSlot: String,
    ): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage WHERE startTime >= :startMs AND endTime <= :endMs ORDER BY startTime ASC")
    suspend fun getUsageForDateRange(
        startMs: Long,
        endMs: Long,
    ): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage ORDER BY startTime DESC")
    fun getAllUsage(): Flow<List<AppUsageEntity>>

    /** Sessions that started at or after [sinceMs], oldest first. Analyses only need a few weeks. */
    @Query("SELECT * FROM app_usage WHERE startTime >= :sinceMs ORDER BY startTime ASC")
    suspend fun getUsageSince(sinceMs: Long): List<AppUsageEntity>

    /** Sessions of one app since [sinceMs], oldest first (app detail page). */
    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND startTime >= :sinceMs ORDER BY startTime ASC")
    suspend fun getUsageForPackageSince(
        packageName: String,
        sinceMs: Long,
    ): List<AppUsageEntity>

    /** Changes whenever a session since [sinceMs] is added or grows; cheap with the startTime index. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(endTime), 0) || ':' || COALESCE(SUM(durationMs), 0) FROM app_usage WHERE startTime >= :sinceMs")
    suspend fun getRevisionSince(sinceMs: Long): String

    /** First day with data, for "days of data". */
    @Query("SELECT MIN(startTime) FROM app_usage")
    suspend fun getFirstStartTime(): Long?

    /** Days with any app use since [sinceMs] (local dates). */
    @Query("SELECT COUNT(DISTINCT date(startTime / 1000, 'unixepoch', 'localtime')) FROM app_usage WHERE startTime >= :sinceMs")
    suspend fun countDaysWithUsageSince(sinceMs: Long): Int

    @Query("SELECT * FROM app_usage WHERE startTime >= :startOfDayMs ORDER BY startTime DESC")
    fun getTodayUsage(startOfDayMs: Long): Flow<List<AppUsageEntity>>

    @Query(
        "SELECT appCategory, SUM(durationMs) as totalDuration FROM app_usage " +
            "WHERE startTime >= :startMs AND endTime <= :endMs GROUP BY appCategory ORDER BY totalDuration DESC",
    )
    suspend fun getTotalDurationByCategory(
        startMs: Long,
        endMs: Long,
    ): List<CategoryDuration>

    @Query("SELECT COUNT(*) FROM app_usage")
    fun getUsageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM app_usage WHERE isHistorical = 1")
    fun getHistoricalUsageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM app_usage WHERE isHistorical = 0")
    fun getLiveUsageCount(): Flow<Int>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(endTime), 0) || ':' || COALESCE(SUM(durationMs), 0) FROM app_usage")
    suspend fun getUsageRevision(): String

    @Query(
        "SELECT COUNT(*) || ':' || COALESCE(MAX(endTime), 0) || ':' || " +
            "COALESCE(SUM(durationMs), 0) FROM app_usage WHERE startTime < :beforeMs",
    )
    suspend fun getModelRevision(beforeMs: Long): String

    @Query("SELECT DISTINCT packageName FROM app_usage WHERE appName = packageName")
    suspend fun getPackagesWithPackageNameLabels(): List<String>

    @Query("UPDATE app_usage SET appName = :appName WHERE packageName = :packageName AND appName = packageName")
    suspend fun updateFallbackAppName(
        packageName: String,
        appName: String,
    )

    @Query("DELETE FROM app_usage WHERE startTime < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long)

    @Query("DELETE FROM app_usage")
    suspend fun deleteAll()

    @Query("SELECT MAX(endTime) FROM app_usage")
    suspend fun getLastInsertedTimestamp(): Long?

    @Query("SELECT packageName FROM app_usage ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastUsedPackage(): String?

    @Query("SELECT packageName FROM app_usage WHERE packageName NOT IN (:launcherPackages) ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastUsedNonLauncherPackage(launcherPackages: List<String>): String?
}
