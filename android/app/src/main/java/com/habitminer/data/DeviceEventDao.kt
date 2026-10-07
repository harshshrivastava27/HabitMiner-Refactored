package com.habitminer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DeviceEventDao {
    @Insert
    suspend fun insert(event: DeviceEventEntity)

    @Query("SELECT COUNT(*) FROM device_events WHERE eventType = :eventType AND timestamp >= :sinceMs")
    suspend fun countSince(
        eventType: String,
        sinceMs: Long,
    ): Int

    @Query("SELECT * FROM device_events WHERE eventType = :eventType AND timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getSince(
        eventType: String,
        sinceMs: Long,
    ): List<DeviceEventEntity>

    @Query("SELECT * FROM device_events ORDER BY timestamp ASC")
    suspend fun getAll(): List<DeviceEventEntity>

    /** Times of events of one type since [sinceMs], oldest first. */
    @Query("SELECT timestamp FROM device_events WHERE eventType = :eventType AND timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getTimesSince(
        eventType: String,
        sinceMs: Long,
    ): List<Long>

    /** Events of several types since [sinceMs], oldest first. */
    @Query("SELECT * FROM device_events WHERE eventType IN (:eventTypes) AND timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getTypesSince(
        eventTypes: List<String>,
        sinceMs: Long,
    ): List<DeviceEventEntity>

    @Query("SELECT timestamp FROM device_events WHERE eventType = :eventType AND packageName = :packageName AND timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getTimesForPackageSince(
        eventType: String,
        packageName: String,
        sinceMs: Long,
    ): List<Long>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(timestamp), 0) FROM device_events WHERE timestamp >= :sinceMs")
    suspend fun getRevisionSince(sinceMs: Long): String

    @Insert
    suspend fun insertAll(events: List<DeviceEventEntity>)

    /** "TYPE:timestamp" for every event, used to skip duplicates on import. */
    @Query("SELECT eventType || ':' || timestamp FROM device_events")
    suspend fun getKeys(): List<String>

    @Query("DELETE FROM device_events WHERE timestamp < :timestampMs")
    suspend fun deleteOlderThan(timestampMs: Long)

    @Query("DELETE FROM device_events")
    suspend fun deleteAll()
}
