package com.habitminer.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restores a ZIP made by [ExportManager] (v1.1 or later), e.g. after reinstalling the app
 * or moving to another phone. App usage, surroundings readings, labels and places are
 * merged into the current database without duplicates; routines, baselines and
 * deviations are recomputed from the restored data rather than imported.
 */
@Singleton
class ImportManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appUsageDao: AppUsageDao,
        private val contextDao: ContextDao,
        private val labelDao: LabelDao,
        private val placeDao: PlaceDao,
        private val deviceEventDao: DeviceEventDao,
    ) {
        data class Result(
            val usage: Int,
            val snapshots: Int,
            val labels: Int,
            val places: Int,
            val filesRead: Int,
        ) {
            val isEmpty: Boolean get() = filesRead == 0
        }

        suspend fun importZip(uri: Uri): Result =
            withContext(Dispatchers.IO) {
                var usage = 0
                var snapshots = 0
                var labels = 0
                var places = 0
                var files = 0
                val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the file")
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory || !name.endsWith(".csv")) continue
                        val rows = CsvParser.parseWithHeader(zip.readBytes().toString(Charsets.UTF_8))
                        when {
                            name.startsWith("app_usage") -> usage += importUsage(rows).also { files++ }
                            name.startsWith("context_snapshots") -> snapshots += importSnapshots(rows).also { files++ }
                            name.startsWith("labels") -> labels += importLabels(rows).also { files++ }
                            name.startsWith("places") -> places += importPlaces(rows).also { files++ }
                            name.startsWith("device_events") -> importDeviceEvents(rows).also { files++ }
                            else -> Unit // habits, baselines, deviations are recomputed
                        }
                    }
                }
                Result(usage, snapshots, labels, places, files)
            }

        private suspend fun importUsage(rows: List<Map<String, String>>): Int {
            val parsed =
                rows.mapNotNull { r ->
                    runCatching {
                        AppUsageEntity(
                            // Session IDs are stable hashes of package + start time, so rows that
                            // the new install already re-read from Android's history are replaced,
                            // not duplicated.
                            id = r["id"]?.toLongOrNull() ?: 0L,
                            packageName = r.getValue("packageName"),
                            appName = r.getValue("appName"),
                            appCategory = r["appCategory"].orEmpty().ifEmpty { "OTHER" },
                            startTime = r.getValue("startTime").toLong(),
                            endTime = r.getValue("endTime").toLong(),
                            durationMs = r.getValue("durationMs").toLong(),
                            timeSlot = r.getValue("timeSlot"),
                            dayType = r.getValue("dayType"),
                            previousPackageName = r["previousPackageName"]?.ifEmpty { null },
                            isHistorical = r["isHistorical"]?.toBooleanStrictOrNull() ?: true,
                        )
                    }.getOrNull()
                }
            parsed.chunked(500).forEach { appUsageDao.insertAll(it) }
            return parsed.size
        }

        private suspend fun importSnapshots(rows: List<Map<String, String>>): Int {
            val existing = contextDao.getAllTimestamps().toHashSet()
            fun f(
                r: Map<String, String>,
                key: String,
            ) = r[key]?.toFloatOrNull() ?: -1f
            val parsed =
                rows.mapNotNull { r ->
                    runCatching {
                        val ts = r.getValue("timestamp").toLong()
                        if (ts in existing) return@runCatching null
                        existing.add(ts)
                        ContextSnapshotEntity(
                            id = 0L,
                            timestamp = ts,
                            accelMean = f(r, "accelMean"),
                            accelVariance = f(r, "accelVariance"),
                            accelStd = f(r, "accelStd"),
                            accelMin = f(r, "accelMin"),
                            accelMax = f(r, "accelMax"),
                            accelEnergy = f(r, "accelEnergy"),
                            gyroMean = f(r, "gyroMean"),
                            gyroVariance = f(r, "gyroVariance"),
                            gyroStd = f(r, "gyroStd"),
                            gyroMin = f(r, "gyroMin"),
                            gyroMax = f(r, "gyroMax"),
                            gyroEnergy = f(r, "gyroEnergy"),
                            lightLux = f(r, "lightLux"),
                            proximityNear = r["proximityNear"]?.toBooleanStrictOrNull(),
                            stepsSinceLastSnapshot = r["stepsSinceLastSnapshot"]?.toIntOrNull() ?: -1,
                            batteryLevel = r["batteryLevel"]?.toIntOrNull() ?: -1,
                            isCharging = r["isCharging"]?.toBooleanStrictOrNull() ?: false,
                            isScreenOn = r["isScreenOn"]?.toBooleanStrictOrNull() ?: false,
                            unlockCount = r["unlockCount"]?.toIntOrNull() ?: 0,
                            notificationsLastHour = r["notificationsLastHour"]?.toIntOrNull() ?: -1,
                            wifiPlace = r["wifiPlace"]?.ifEmpty { null },
                            sensingMs = r["sensingMs"]?.toLongOrNull() ?: 0L,
                            recentSteps = r["recentSteps"]?.toIntOrNull() ?: -1,
                        )
                    }.getOrNull()
                }
            parsed.chunked(500).forEach { contextDao.insertAll(it) }
            return parsed.size
        }

        private suspend fun importLabels(rows: List<Map<String, String>>): Int {
            val existing = labelDao.getKeys().toHashSet()
            val parsed =
                rows.mapNotNull { r ->
                    runCatching {
                        val kind = r.getValue("kind")
                        val ts = r.getValue("timestamp").toLong()
                        if (!existing.add("$kind:$ts")) return@runCatching null
                        UserLabelEntity(
                            timestamp = ts,
                            kind = kind,
                            value = r.getValue("value"),
                            refKey = r["refKey"]?.ifEmpty { null },
                            promptedAt = r["promptedAt"]?.toLongOrNull(),
                            contextJson = r["contextJson"]?.ifEmpty { null },
                        )
                    }.getOrNull()
                }
            if (parsed.isNotEmpty()) labelDao.insertAll(parsed)
            return parsed.size
        }

        /**
         * Unlocks, notifications and screen events. The original HabitMiner exported the
         * system's unlock history as "UNLOCK" rows with source "system"; those become
         * KEYGUARD_HIDDEN events, the type Extended keeps them as, so imported history feeds
         * sleep and pickup analysis like unlocks recorded here.
         */
        private suspend fun importDeviceEvents(rows: List<Map<String, String>>): Int {
            val existing = deviceEventDao.getKeys().toHashSet()
            val parsed =
                rows.mapNotNull { r ->
                    val raw = r["eventType"]?.ifEmpty { null } ?: return@mapNotNull null
                    val type =
                        if (r["source"] == "system" && raw == com.habitminer.collection.DeviceEvents.UNLOCK) {
                            com.habitminer.collection.DeviceEvents.KEYGUARD_HIDDEN
                        } else {
                            raw
                        }
                    val ts = r["timestamp"]?.toLongOrNull() ?: return@mapNotNull null
                    if (!existing.add("$type:$ts")) return@mapNotNull null
                    DeviceEventEntity(eventType = type, packageName = r["packageName"]?.ifEmpty { null }, timestamp = ts)
                }
            parsed.chunked(500).forEach { deviceEventDao.insertAll(it) }
            return parsed.size
        }

        private suspend fun importPlaces(rows: List<Map<String, String>>): Int {
            var count = 0
            for (r in rows) {
                val hash = r["placeHash"]?.ifEmpty { null } ?: continue
                val first = r["firstSeen"]?.toLongOrNull() ?: continue
                val last = r["lastSeen"]?.toLongOrNull() ?: first
                placeDao.insertIgnore(PlaceEntity(placeHash = hash, firstSeen = first, lastSeen = last))
                r["label"]?.ifEmpty { null }?.let { placeDao.rename(hash, it) }
                count++
            }
            return count
        }
    }
