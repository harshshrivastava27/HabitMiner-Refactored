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
                    writer.append("wifiPlace,sensingMs,recentSteps\n")
                    contexts.forEach {
                        writer.append("${it.id},${it.timestamp},${it.accelMean},${it.accelVariance},${it.accelStd},${it.accelMin},")
                        writer.append("${it.accelMax},${it.accelEnergy},${it.gyroMean},${it.gyroVariance},${it.gyroStd},${it.gyroMin},")
                        writer.append("${it.gyroMax},${it.gyroEnergy},${it.lightLux},${it.proximityNear ?: ""},")
                        writer.append("${it.stepsSinceLastSnapshot},${it.batteryLevel},${it.isCharging},${it.isScreenOn},")
                        writer.append("${it.unlockCount},${it.notificationsLastHour},${it.wifiPlace ?: ""},${it.sensingMs},${it.recentSteps}\n")
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

                // 8. Unlocks and notifications: what this app recorded, plus the system's unlock
                //    history (Android keeps about a week), for pickup and sleep analysis.
                val eventsFile = File(exportDir, "device_events_$timestamp.csv")
                val events = contextRepository.getAllDeviceEvents()
                val systemUnlocks =
                    runCatching { usageDataCollector.getUnlockTimesSince(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) }
                        .getOrNull().orEmpty()
                FileWriter(eventsFile).use { writer ->
                    writer.append("eventType,packageName,timestamp,source\n")
                    events.forEach { writer.append("${escapeCsv(it.eventType)},${escapeCsv(it.packageName ?: "")},${it.timestamp},app\n") }
                    systemUnlocks.forEach { writer.append("UNLOCK,,$it,system\n") }
                }

                // 9. Zip everything
                val zipFile = File(exportDir, "habitminer_export_$timestamp.zip")
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                    listOf(usageFile, contextFile, habitsFile, baselinesFile, deviationsFile, labelsFile, placesFile, eventsFile).forEach { file ->
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

        private fun escapeCsv(value: String): String {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\""
            }
            return value
        }
    }
