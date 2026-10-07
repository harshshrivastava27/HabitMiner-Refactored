package com.habitminer.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: com.habitminer.repository.ContextRepository,
        private val habitRepository: com.habitminer.repository.HabitRepository,
        private val feedbackRepository: com.habitminer.repository.FeedbackRepository,
        private val usageDataCollector: com.habitminer.collection.UsageDataCollector,
        private val appIdentityResolver: com.habitminer.domain.AppIdentityResolver,
    ) {
        suspend fun exportDataToCsv(): String? {
            try {
                val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
                val exportDir = File(externalDir, "export")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                // Delete any stale ZIPs from previous exports
                exportDir.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

                // 1. Export App Usage
                val usageFile = File(exportDir, "app_usage_$timestamp.csv")
                val usages = contextRepository.getAllUsage().firstOrNull() ?: emptyList()
                FileWriter(usageFile).use { writer ->
                    writer.append("id,packageName,appName,appCategory,startTime,endTime,")
                    writer.append("durationMs,timeSlot,dayType,previousPackageName,isHistorical\n")
                    usages.forEach {
                        writer.append(
                            "${it.id},${escapeCsv(it.packageName)},${escapeCsv(it.appName)},${escapeCsv(it.appCategory)},${it.startTime},",
                        )
                        writer.append("${it.endTime},${it.durationMs},${it.timeSlot},${it.dayType},")
                        writer.append("${escapeCsv(it.previousPackageName ?: "")},${it.isHistorical}\n")
                    }
                }

                // 2. Export Context Snapshots
                val contextFile = File(exportDir, "context_snapshots_$timestamp.csv")
                val contexts = contextRepository.getAllSnapshots().firstOrNull() ?: emptyList()
                FileWriter(contextFile).use { writer ->
                    writer.append("id,timestamp,accelMean,accelVariance,accelStd,accelMin,accelMax,accelEnergy,")
                    writer.append("gyroMean,gyroVariance,gyroStd,gyroMin,gyroMax,gyroEnergy,lightLux,proximityNear,")
                    writer.append("stepsSinceLastSnapshot,batteryLevel,isCharging,isScreenOn,unlockCount,notificationsLastHour,")
                    writer.append("wifiPlace,sensingMs,recentSteps,batteryTempC,powerSave,thermalStatus\n")
                    contexts.forEach {
                        writer.append("${it.id},${it.timestamp},${it.accelMean},${it.accelVariance},${it.accelStd},${it.accelMin},")
                        writer.append("${it.accelMax},${it.accelEnergy},${it.gyroMean},${it.gyroVariance},${it.gyroStd},${it.gyroMin},")
                        writer.append("${it.gyroMax},${it.gyroEnergy},${it.lightLux},${it.proximityNear ?: ""},")
                        writer.append("${it.stepsSinceLastSnapshot},${it.batteryLevel},${it.isCharging},${it.isScreenOn},")
                        writer.append("${it.unlockCount},${it.notificationsLastHour},${it.wifiPlace ?: ""},${it.sensingMs},${it.recentSteps},${it.batteryTempC ?: ""},${it.powerSave ?: ""},${it.thermalStatus ?: ""}\n")
                    }
                }

                // 3. Export Habits
                val habitsFile = File(exportDir, "habits_$timestamp.csv")
                val habits = habitRepository.getAllHabits().firstOrNull() ?: emptyList()
                FileWriter(habitsFile).use { writer ->
                    writer.append("id,habitName,patternDescription,appSequence,confidence,")
                    writer.append("occurrenceCount,timeSlot,dayType,discoveredAt,lastSeenAt\n")
                    habits.forEach {
                        writer.append("${it.id},${escapeCsv(it.habitName)},${escapeCsv(it.patternDescription)},")
                        writer.append("${escapeCsv(it.appSequence)},${it.confidence},")
                        writer.append("${it.occurrenceCount},${it.timeSlot},${it.dayType},${it.discoveredAt},${it.lastSeenAt}\n")
                    }
                }

                // 4. Export Baselines
                val baselinesFile = File(exportDir, "baselines_$timestamp.csv")
                val baselines = habitRepository.getAllBaselines().firstOrNull() ?: emptyList()
                FileWriter(baselinesFile).use { writer ->
                    writer.append("timeBin,avgScreenTimeMs,stdScreenTimeMs,avgSessionCount,stdSessionCount,")
                    writer.append("avgUnlockCount,typicalCategoriesJson,avgAccelEnergy,avgLightLux,updatedAt,dataPointCount\n")
                    baselines.forEach {
                        writer.append(
                            "${escapeCsv(
                                it.timeBin,
                            )},${it.avgScreenTimeMs},${it.stdScreenTimeMs},${it.avgSessionCount},${it.stdSessionCount},",
                        )
                        writer.append(
                            "${it.avgUnlockCount},${escapeCsv(
                                it.typicalCategoriesJson,
                            )},${it.avgAccelEnergy},${it.avgLightLux},${it.updatedAt},${it.dataPointCount}\n",
                        )
                    }
                }

                // 5. Export Deviations
                val deviationsFile = File(exportDir, "deviations_$timestamp.csv")
                val deviations = habitRepository.getAllDeviations().firstOrNull() ?: emptyList()
                FileWriter(deviationsFile).use { writer ->
                    writer.append("id,timestamp,timeBin,deviationType,description,zScore,normalizedScore,affectedCategory\n")
                    deviations.forEach {
                        writer.append("${it.id},${it.timestamp},${escapeCsv(it.timeBin)},${escapeCsv(it.deviationType)},")
                        writer.append("${escapeCsv(it.description)},${it.zScore},${it.normalizedScore},${escapeCsv(it.affectedCategory)}\n")
                    }
                }

                // 6. Labels: check-in answers and deviation feedback (ground truth for evaluation)
                val labelsFile = File(exportDir, "labels_$timestamp.csv")
                val labels = feedbackRepository.getAllLabels().firstOrNull() ?: emptyList()
                FileWriter(labelsFile).use { writer ->
                    writer.append("id,timestamp,kind,value,refKey,promptedAt,contextJson\n")
                    labels.forEach {
                        writer.append("${it.id},${it.timestamp},${escapeCsv(it.kind)},${escapeCsv(it.value)},")
                        writer.append("${escapeCsv(it.refKey ?: "")},${it.promptedAt ?: ""},${escapeCsv(it.contextJson ?: "")}\n")
                    }
                }

                // 7. Wi-Fi places (hashed IDs and the names given to them)
                val placesFile = File(exportDir, "places_$timestamp.csv")
                val places = feedbackRepository.getPlaces().firstOrNull() ?: emptyList()
                FileWriter(placesFile).use { writer ->
                    writer.append("placeHash,label,firstSeen,lastSeen\n")
                    places.forEach {
                        writer.append("${it.placeHash},${escapeCsv(it.label ?: "")},${it.firstSeen},${it.lastSeen}\n")
                    }
                }

                // 8. Device events: unlocks and notifications this app saw ("app"), and the
                //    lock-screen, screen and power events copied from Android's log ("system").
                val eventsFile = File(exportDir, "device_events_$timestamp.csv")
                val events = contextRepository.getAllDeviceEvents()
                FileWriter(eventsFile).use { writer ->
                    writer.append("eventType,packageName,timestamp,source,detail\n")
                    events.forEach {
                        val source = if (it.eventType in com.habitminer.collection.DeviceEvents.RECORDED_BY_APP) "app" else "system"
                        writer.append("${escapeCsv(it.eventType)},${escapeCsv(it.packageName ?: "")},${it.timestamp},$source,${escapeCsv(it.detail ?: "")}\n")
                    }
                }

                // 9. Sleep per day: the night that ended that morning + confirmed naps.
                val sleepFile = File(exportDir, "sleep_days_$timestamp.csv")
                FileWriter(sleepFile).use { writer ->
                    writer.append("date,sleepStart,wakeTime,bedtime,wakeUp,nightAsleepMin,inBedMin,briefWakes,briefWakeMin,")
                    writer.append("naps,napMin,totalMin,confidence\n")
                    sleepDays(usages).forEach { d ->
                        val n = d.night
                        val zone = java.time.ZoneId.systemDefault()
                        writer.append("${d.date},${n?.sleepStart ?: ""},${n?.wakeTime ?: ""},")
                        writer.append("${n?.let { com.habitminer.analytics.Format.clock(it.sleepStart, zone) } ?: ""},")
                        writer.append("${n?.let { com.habitminer.analytics.Format.clock(it.wakeTime, zone) } ?: ""},")
                        writer.append("${d.nightMs / 60_000},${d.inBedMs / 60_000},${n?.briefWakes?.size ?: 0},${d.wakeUpMs / 60_000},")
                        writer.append("${d.naps.size},${d.napMs / 60_000},${d.totalMs / 60_000},${n?.confidence?.name ?: ""}\n")
                    }
                }

                // 10. Zip everything
                val zipFile = File(exportDir, "habitminer_export_$timestamp.zip")
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                    listOf(usageFile, contextFile, habitsFile, baselinesFile, deviationsFile, labelsFile, placesFile, eventsFile, sleepFile).forEach { file ->
                        if (file.exists()) {
                            zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            file.delete() // clean up raw CSVs
                        }
                    }
                }

                return zipFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "exportDataToCsv failed", e)
                return null
            }
        }

        companion object {
            private const val TAG = "ExportManager"
        }

        /** Up to 90 days of sleep per day, computed the same way as in the app. */
        private suspend fun sleepDays(usages: List<AppUsageEntity>): List<com.habitminer.analytics.DailySleep> =
            runCatching {
                val now = System.currentTimeMillis()
                val zone = java.time.ZoneId.systemDefault()
                val since = now - 90L * 24 * 60 * 60 * 1000
                val sessions =
                    usages.filter { it.startTime >= since }
                        .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                        .map(com.habitminer.engine.AnalyticsMappers::session)
                val samples = contextRepository.getSnapshotsSince(since).map(com.habitminer.engine.AnalyticsMappers::sample)
                val unlocks = contextRepository.unlockTimesSince(since)
                val labels = feedbackRepository.getAllLabels().firstOrNull().orEmpty()
                val today = java.time.LocalDate.now(zone)
                val nights = com.habitminer.analytics.SleepDetector.detectRange(sessions, unlocks, samples, today, 90, now, zone)
                com.habitminer.analytics.SleepDays.build(nights, com.habitminer.engine.LabelMappers.confirmedNaps(labels), today, 90, zone)
            }.getOrDefault(emptyList())

        private fun escapeCsv(value: String): String {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\""
            }
            return value
        }
    }
