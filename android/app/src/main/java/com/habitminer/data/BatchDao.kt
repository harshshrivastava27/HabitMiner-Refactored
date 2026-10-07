package com.habitminer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Multi-row writes done in one transaction: one disk sync and one table-change signal instead
 * of one per row, so screens observing these tables refresh once.
 */
@Dao
abstract class BatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSessions(sessions: List<AppUsageEntity>)

    @Insert
    protected abstract suspend fun insertEvents(events: List<DeviceEventEntity>)

    /** One read of the usage-event log. */
    @Transaction
    open suspend fun insertBatch(
        sessions: List<AppUsageEntity>,
        events: List<DeviceEventEntity>,
    ) {
        if (sessions.isNotEmpty()) insertSessions(sessions)
        if (events.isNotEmpty()) insertEvents(events)
    }

    @Query("DELETE FROM deviations WHERE timestamp >= :sinceMs")
    protected abstract suspend fun deleteDeviationsSince(sinceMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDeviations(deviations: List<DeviationEntity>)

    @Transaction
    open suspend fun replaceDeviationsSince(
        sinceMs: Long,
        deviations: List<DeviationEntity>,
    ) {
        deleteDeviationsSince(sinceMs)
        if (deviations.isNotEmpty()) insertDeviations(deviations)
    }

    @Query("DELETE FROM discovered_habits")
    protected abstract suspend fun deleteHabits()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertHabits(habits: List<DiscoveredHabitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertBaselines(baselines: List<BaselineEntity>)

    /** A model rebuild: new baselines and the full set of discovered routines. */
    @Transaction
    open suspend fun replaceModel(
        baselines: List<BaselineEntity>,
        habits: List<DiscoveredHabitEntity>,
    ) {
        if (baselines.isNotEmpty()) insertBaselines(baselines)
        deleteHabits()
        if (habits.isNotEmpty()) insertHabits(habits)
    }
}
