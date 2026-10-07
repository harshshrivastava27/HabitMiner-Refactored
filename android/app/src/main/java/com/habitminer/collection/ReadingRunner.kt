package com.habitminer.collection

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.habitminer.analytics.ContextLabels
import com.habitminer.analytics.Motion
import com.habitminer.analytics.SensingMode
import com.habitminer.analytics.SensingPolicy
import com.habitminer.analytics.TimeUtil
import com.habitminer.data.PrefsKeys
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.FeedbackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes one context reading: copies new usage events, samples the sensors if the screen is on,
 * and stores a snapshot. Shared by the alarm that drives scheduled readings, the monitoring
 * service (unlocks and app opens) and the WorkManager fallback, so they all follow one set of
 * rules about when a reading is worth taking.
 */
@Singleton
class ReadingRunner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val sensorCollector: SensorContextCollector,
        private val wifiPlaceProvider: WifiPlaceProvider,
        private val feedbackRepository: FeedbackRepository,
        private val usageIngestor: UsageIngestor,
    ) {
        /**
         * Why a reading is taken. Scheduled readings run every 5–30 minutes; unlocking the
         * phone and opening the app also take one (at most every 5 minutes) so the
         * surroundings shown are fresh while the phone is in use. Event readings skip the
         * gyroscope: the accelerometer and step counter already tell moving from still.
         */
        enum class Trigger(
            val minSensorGapMs: Long,
            val includeGyro: Boolean,
        ) {
            SCHEDULE(0L, true),
            UNLOCK(5 * 60_000L, false),
            APP_OPEN(5 * 60_000L, false),
        }

        @Volatile private var mode: SensingMode = readSavedMode()

        /** The current sensing mode; its interval sets when the next scheduled reading runs. */
        val sensingMode: SensingMode get() = mode

        private val prefs get() = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        fun isCollectionEnabled(): Boolean = prefs.getBoolean(PrefsKeys.COLLECTION_ENABLED, true)

        fun isPaused(): Boolean = prefs.getBoolean(PrefsKeys.MONITORING_PAUSED, false)

        /** Returns true when a snapshot was stored. */
        suspend fun read(trigger: Trigger): Boolean {
            if (!isCollectionEnabled() || isPaused()) return false

            // Cheap and always useful: keeps unlocks and sessions current for every screen.
            runCatching { usageIngestor.ingest(minIntervalMs = if (trigger == Trigger.SCHEDULE) 0L else 60_000L) }
                .onFailure { android.util.Log.w(TAG, "Usage ingest failed", it) }

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = pm.isInteractive
            val battery = batteryState()
            val shouldCollectSensors = isScreenOn && (battery.level >= 15 || battery.charging)

            if (trigger != Trigger.SCHEDULE &&
                (!shouldCollectSensors || contextRepository.hasSensorReadingWithin(trigger.minSensorGapMs))
            ) {
                return false
            }
            updateMode(isScreenOn, battery)

            val now = System.currentTimeMillis()
            val startOfDay = TimeUtil.startOfDay(TimeUtil.dateOf(now, java.time.ZoneId.systemDefault()), java.time.ZoneId.systemDefault())
            val unlockCount = contextRepository.countUnlocksSince(startOfDay)
            val notificationsLastHour =
                if (HabitNotificationListener.isEnabled(context)) {
                    contextRepository.countDeviceEventsSince(DeviceEvents.NOTIFICATION, now - TimeUnit.HOURS.toMillis(1))
                } else {
                    -1
                }

            return contextRepository.collectionMutex.withLock {
                // Skip if a snapshot was taken very recently (another trigger or the worker).
                val skip =
                    if (trigger == Trigger.SCHEDULE) {
                        contextRepository.shouldSkipContextCollection((mode.intervalMs - 60_000L).coerceAtLeast(4 * 60_000L))
                    } else {
                        contextRepository.hasSensorReadingWithin(trigger.minSensorGapMs)
                    }
                if (skip) return@withLock false
                val place = wifiPlaceProvider.currentPlaceHash()
                val snapshot =
                    sensorCollector.collectSnapshot(
                        unlockCount = unlockCount,
                        isScreenOn = isScreenOn,
                        notificationsLastHour = notificationsLastHour,
                        collectSensors = shouldCollectSensors,
                        batteryLevel = battery.level,
                        isCharging = battery.charging,
                        wifiPlace = place,
                        includeGyro = trigger.includeGyro,
                    )
                contextRepository.insertSnapshot(snapshot)
                if (place != null) feedbackRepository.recordPlaceSeen(place, snapshot.timestamp)
                true
            }
        }

        private suspend fun updateMode(
            isScreenOn: Boolean,
            battery: BatteryState,
        ) {
            val latest = contextRepository.getLatestSnapshotWithSensorsOnce()
            val recentlyMoving =
                latest?.takeIf { System.currentTimeMillis() - it.timestamp < 20 * 60 * 1000L }
                    ?.let { ContextLabels.motion(it.accelVariance.takeIf { v -> v >= 0f }, it.recentSteps.takeIf { s -> s >= 0 }) }
                    ?.let { it != Motion.STILL }
            val newMode = SensingPolicy.choose(isScreenOn, recentlyMoving, battery.charging, battery.level)
            if (newMode != mode) {
                mode = newMode
                prefs.edit().putString(PrefsKeys.SENSING_MODE, newMode.name).apply()
            }
        }

        private fun readSavedMode(): SensingMode =
            runCatching {
                SensingMode.valueOf(
                    context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(PrefsKeys.SENSING_MODE, null) ?: SensingMode.NORMAL.name,
                )
            }.getOrDefault(SensingMode.NORMAL)

        data class BatteryState(
            val level: Int,
            val charging: Boolean,
        )

        fun batteryState(): BatteryState {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            var level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            var charging = bm.isCharging
            if (level !in 0..100) {
                val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                if (intent != null) {
                    val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                } else {
                    level = -1
                }
            }
            return BatteryState(level, charging)
        }

        companion object {
            private const val TAG = "HabitMiner"
        }
    }
