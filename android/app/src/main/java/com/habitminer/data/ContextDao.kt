package com.habitminer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: ContextSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<ContextSnapshotEntity>)

    @Query("SELECT timestamp FROM context_snapshots")
    suspend fun getAllTimestamps(): List<Long>

    @Query("SELECT * FROM context_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshot(): Flow<ContextSnapshotEntity?>

    /** Latest snapshot that actually has sensor readings (sensors are skipped while the screen is off). */
    @Query("SELECT * FROM context_snapshots WHERE lightLux >= 0 OR accelVariance >= 0 ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshotWithSensors(): Flow<ContextSnapshotEntity?>

    @Query("SELECT COALESCE(SUM(sensingMs), 0) FROM context_snapshots WHERE timestamp >= :sinceMs")
    fun getSensingMsSince(sinceMs: Long): Flow<Long>

    @Query("SELECT * FROM context_snapshots WHERE timestamp >= :startOfDayMs ORDER BY timestamp ASC")
    fun getTodaySnapshots(startOfDayMs: Long): Flow<List<ContextSnapshotEntity>>

    @Query("SELECT * FROM context_snapshots WHERE timestamp >= :startMs AND timestamp <= :endMs ORDER BY timestamp ASC")
    suspend fun getSnapshotsForDateRange(
        startMs: Long,
        endMs: Long,
    ): List<ContextSnapshotEntity>

    @Query("SELECT * FROM context_snapshots WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getSnapshotsSince(sinceMs: Long): List<ContextSnapshotEntity>

    @Query("SELECT * FROM context_snapshots ORDER BY timestamp DESC")
    fun getAllSnapshots(): Flow<List<ContextSnapshotEntity>>

    @Query("SELECT COUNT(*) FROM context_snapshots")
    fun getSnapshotCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(" +
            "date(MAX(timestamp) / 1000, 'unixepoch', 'localtime') || ':' || " +
            "CAST(CAST(strftime('%H', MAX(timestamp) / 1000, 'unixepoch', 'localtime') AS INTEGER) / 6 AS TEXT), " +
            "''" +
            ") FROM context_snapshots",
    )
    suspend fun getSnapshotRevision(): String

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(timestamp), 0) FROM context_snapshots WHERE timestamp < :beforeMs")
    suspend fun getModelRevision(beforeMs: Long): String

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(timestamp), 0) FROM context_snapshots WHERE timestamp >= :sinceMs")
    suspend fun getRevisionSince(sinceMs: Long): String

    @Query("SELECT * FROM context_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshotOnce(): ContextSnapshotEntity?

    @Query("SELECT * FROM context_snapshots WHERE lightLux >= 0 OR accelVariance >= 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshotWithSensorsOnce(): ContextSnapshotEntity?

    @Query("DELETE FROM context_snapshots WHERE timestamp < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long)

    @Query("DELETE FROM context_snapshots")
    suspend fun deleteAll()
}
