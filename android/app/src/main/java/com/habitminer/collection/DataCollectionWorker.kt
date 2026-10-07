package com.habitminer.collection

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.habitminer.data.PrefsKeys
import com.habitminer.repository.ContextRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Safety net every 15 minutes (Android runs it in Doze maintenance windows). Copies new usage
 * events, takes a reading only if the alarm-driven readings have fallen behind, re-arms the
 * reading alarm if its chain broke, and prunes old data once a day.
 */
@HiltWorker
class DataCollectionWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val usageIngestor: UsageIngestor,
        private val readingRunner: ReadingRunner,
        private val contextRepository: ContextRepository,
        private val habitRepository: com.habitminer.repository.HabitRepository,
        private val feedbackRepository: com.habitminer.repository.FeedbackRepository,
        private val proactiveEngine: com.habitminer.proactive.ProactiveEngine,
        private val insightRepository: com.habitminer.repository.InsightRepository,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            workerMutex.withLock {
                try {
                    val preferences = appContext.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    if (!preferences.getBoolean(PrefsKeys.COLLECTION_ENABLED, true)) {
                        return@withLock Result.success()
                    }

                    pruneOncePerDay(preferences)
                    usageIngestor.ingest(minIntervalMs = 60_000L)

                    // The alarm chain normally takes the readings; only step in when it's behind.
                    val latest = contextRepository.getLatestSnapshotOnce()?.timestamp ?: 0L
                    val behindBy = System.currentTimeMillis() - latest
                    if (behindBy > readingRunner.sensingMode.intervalMs + 5 * 60_000L) {
                        readingRunner.read(ReadingRunner.Trigger.SCHEDULE)
                    }
                    if (!ReadingAlarm.isScheduled(appContext)) ReadingAlarm.schedule(appContext, 60_000L)

                    // Fallback for questions and summaries when the service isn't running.
                    if (!MonitoringService.isServiceRunning.value) proactiveEngine.tick()

                    Result.success()
                } catch (e: CancellationException) {
                    // Re-throw so WorkManager honours explicit cancellation (e.g. clearing data).
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "DataCollectionWorker failed", e)
                    Result.retry()
                }
            }

        /** Deleting old rows touches every table, so it runs once a day rather than every 15 minutes. */
        private suspend fun pruneOncePerDay(preferences: android.content.SharedPreferences) {
            val today = LocalDate.now().toString()
            if (preferences.getString(PrefsKeys.LAST_PRUNE_DAY, null) == today) return
            val retentionDays = preferences.getInt(PrefsKeys.RETENTION_DAYS, 90).coerceIn(30, 180)
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            contextRepository.clearOldData(cutoff)
            habitRepository.clearOldData(cutoff)
            feedbackRepository.clearOldData(cutoff)
            insightRepository.clearOldData(cutoff)
            preferences.edit().putString(PrefsKeys.LAST_PRUNE_DAY, today).apply()
        }

        companion object {
            private val workerMutex = Mutex()
            private const val TAG = "HabitMiner"
            private const val WORK_NAME = "DataCollectionWorker"

            fun schedulePeriodicWork(context: Context) {
                val constraints =
                    androidx.work.Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                val request =
                    PeriodicWorkRequestBuilder<DataCollectionWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }

            fun runOnce(context: Context) {
                val constraints =
                    androidx.work.Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                val request =
                    OneTimeWorkRequestBuilder<DataCollectionWorker>()
                        .setConstraints(constraints)
                        .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "DataCollectionWorker_Once",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
