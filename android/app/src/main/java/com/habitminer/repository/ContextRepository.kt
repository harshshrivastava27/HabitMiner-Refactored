package com.habitminer.repository

import com.habitminer.data.AppUsageDao
import com.habitminer.data.AppUsageEntity
import com.habitminer.data.ContextDao
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.data.DeviceEventDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextRepository
    @Inject
    constructor(
        private val appUsageDao: AppUsageDao,
        private val contextDao: ContextDao,
        private val deviceEventDao: DeviceEventDao,
        private val batchDao: com.habitminer.data.BatchDao,
    ) {
        val collectionMutex = Mutex()

        /**
         * True when a snapshot was taken less than [minGapMs] ago. The gap follows the current
         * sensing mode (5 min when active, up to 30 min when idle).
         */
        suspend fun shouldSkipContextCollection(minGapMs: Long = 10 * 60 * 1000L): Boolean {
            val latest = contextDao.getLatestSnapshotOnce()
            return latest != null && (System.currentTimeMillis() - latest.timestamp) < minGapMs
        }

        /** True when a snapshot with sensor readings was taken less than [maxAgeMs] ago. */
        suspend fun hasSensorReadingWithin(maxAgeMs: Long): Boolean {
            val latest = contextDao.getLatestSnapshotWithSensorsOnce()
            return latest != null && (System.currentTimeMillis() - latest.timestamp) < maxAgeMs
        }

        fun getLatestSnapshot(): Flow<ContextSnapshotEntity?> = contextDao.getLatestSnapshot()

        fun getLatestSnapshotWithSensors(): Flow<ContextSnapshotEntity?> = contextDao.getLatestSnapshotWithSensors()

        suspend fun getLatestSnapshotOnce(): ContextSnapshotEntity? = contextDao.getLatestSnapshotOnce()

        suspend fun getLatestSnapshotWithSensorsOnce(): ContextSnapshotEntity? = contextDao.getLatestSnapshotWithSensorsOnce()

        fun getSensingMsSince(sinceMs: Long): Flow<Long> = contextDao.getSensingMsSince(sinceMs)

        suspend fun getDeviceEventsSince(
            eventType: String,
            sinceMs: Long,
        ): List<com.habitminer.data.DeviceEventEntity> = deviceEventDao.getSince(eventType, sinceMs)

        fun getTodayUsage(startOfDayMs: Long): Flow<List<AppUsageEntity>> = appUsageDao.getTodayUsage(startOfDayMs)

        fun getTodaySnapshots(startOfDayMs: Long): Flow<List<ContextSnapshotEntity>> = contextDao.getTodaySnapshots(startOfDayMs)

        fun getAllUsage(): Flow<List<AppUsageEntity>> = appUsageDao.getAllUsage()

        /** Sessions that started since [sinceMs], oldest first. */
        suspend fun getUsageSince(sinceMs: Long): List<AppUsageEntity> = appUsageDao.getUsageSince(sinceMs)

        suspend fun getUsageForPackageSince(
            packageName: String,
            sinceMs: Long,
        ): List<AppUsageEntity> = appUsageDao.getUsageForPackageSince(packageName, sinceMs)

        /**
         * A string that changes whenever usage, readings or events since [sinceMs] change.
         * Analyses are cached against it, so the comparison has to be cheap: three indexed
         * aggregate queries.
         */
        suspend fun dataRevisionSince(sinceMs: Long): String =
            "${appUsageDao.getRevisionSince(sinceMs)}|${contextDao.getRevisionSince(sinceMs)}|${deviceEventDao.getRevisionSince(sinceMs)}"

        suspend fun countDaysWithUsageSince(sinceMs: Long): Int = appUsageDao.countDaysWithUsageSince(sinceMs)

        suspend fun getFirstUsageTime(): Long? = appUsageDao.getFirstStartTime()

        /**
         * Unlock times since [sinceMs], oldest first: the system's lock-screen log plus any
         * unlocks our receiver saw since it was last copied (see [com.habitminer.analytics.UnlockMerge]).
         */
        suspend fun unlockTimesSince(sinceMs: Long): List<Long> =
            com.habitminer.analytics.UnlockMerge.merge(
                deviceEventDao.getTimesSince(com.habitminer.collection.DeviceEvents.KEYGUARD_HIDDEN, sinceMs),
                deviceEventDao.getTimesSince(com.habitminer.collection.DeviceEvents.UNLOCK, sinceMs),
            )

        suspend fun countUnlocksSince(sinceMs: Long): Int = unlockTimesSince(sinceMs).size

        suspend fun notificationTimesForPackageSince(
            packageName: String,
            sinceMs: Long,
        ): List<Long> = deviceEventDao.getTimesForPackageSince(com.habitminer.collection.DeviceEvents.NOTIFICATION, packageName, sinceMs)

        suspend fun getDeviceEventsOfTypesSince(
            eventTypes: List<String>,
            sinceMs: Long,
        ): List<com.habitminer.data.DeviceEventEntity> = deviceEventDao.getTypesSince(eventTypes, sinceMs)

        /** Stores one read of the usage log in a single transaction. */
        suspend fun insertIngestBatch(
            sessions: List<AppUsageEntity>,
            events: List<com.habitminer.data.DeviceEventEntity>,
        ) {
            batchDao.insertBatch(sessions, events)
        }

        suspend fun getSnapshotsSince(sinceMs: Long): List<ContextSnapshotEntity> = contextDao.getSnapshotsSince(sinceMs)

        suspend fun getUsageForDateRange(startMs: Long, endMs: Long): List<AppUsageEntity> = appUsageDao.getUsageForDateRange(startMs, endMs)

        suspend fun getSnapshotsForDateRange(startMs: Long, endMs: Long): List<ContextSnapshotEntity> = contextDao.getSnapshotsForDateRange(startMs, endMs)

        fun getAllSnapshots(): Flow<List<ContextSnapshotEntity>> = contextDao.getAllSnapshots()

        suspend fun getUsageRevision(): String = appUsageDao.getUsageRevision()

        suspend fun getModelRevision(beforeMs: Long): String =
            "${appUsageDao.getModelRevision(beforeMs)}|${contextDao.getModelRevision(beforeMs)}"

        suspend fun getPackagesWithFallbackNames(): List<String> = appUsageDao.getPackagesWithPackageNameLabels()

        suspend fun updateFallbackAppName(
            packageName: String,
            appName: String,
        ) = appUsageDao.updateFallbackAppName(packageName, appName)

        suspend fun getUsageCount(): Int = appUsageDao.getUsageCount().first()

        fun getUsageCountFlow(): Flow<Int> = appUsageDao.getUsageCount()

        fun getHistoricalUsageCountFlow(): Flow<Int> = appUsageDao.getHistoricalUsageCount()

        fun getLiveUsageCountFlow(): Flow<Int> = appUsageDao.getLiveUsageCount()

        fun getSnapshotCountFlow(): Flow<Int> = contextDao.getSnapshotCount()

        suspend fun getLastInsertedUsageTimestamp(): Long? = appUsageDao.getLastInsertedTimestamp()

        suspend fun getLastUsedNonLauncherPackage(launcherPackages: List<String>): String? =
            if (launcherPackages.isEmpty()) {
                appUsageDao.getLastUsedPackage()
            } else {
                appUsageDao.getLastUsedNonLauncherPackage(launcherPackages)
            }

        suspend fun getSnapshotRevision(): String = contextDao.getSnapshotRevision()

        suspend fun insertSnapshot(snapshot: ContextSnapshotEntity) = contextDao.insert(snapshot)

        suspend fun insertAppUsage(usage: AppUsageEntity) = appUsageDao.insert(usage)

        suspend fun insertAllAppUsage(usages: List<AppUsageEntity>) = appUsageDao.insertAll(usages)

        suspend fun insertDeviceEvent(event: com.habitminer.data.DeviceEventEntity) = deviceEventDao.insert(event)

        suspend fun getAllDeviceEvents(): List<com.habitminer.data.DeviceEventEntity> = deviceEventDao.getAll()

        suspend fun countDeviceEventsSince(eventType: String, sinceMs: Long): Int = deviceEventDao.countSince(eventType, sinceMs)

        suspend fun clearOldData(retentionCutoffMs: Long) {
            appUsageDao.deleteOlderThan(retentionCutoffMs)
            contextDao.deleteOlderThan(retentionCutoffMs)
            deviceEventDao.deleteOlderThan(retentionCutoffMs)
        }

        suspend fun clearCollectedData() {
            appUsageDao.deleteAll()
            contextDao.deleteAll()
            deviceEventDao.deleteAll()
        }
    }
