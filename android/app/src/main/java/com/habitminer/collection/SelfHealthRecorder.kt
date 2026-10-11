package com.habitminer.collection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import com.habitminer.analytics.PowerModel
import com.habitminer.data.SelfHealthDao
import com.habitminer.data.SelfHealthEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records HabitMiner's own CPU, wake-lock, sensor and radio use with each reading, from
 * Android's per-app health stats (the same counters the system's battery screen uses).
 */
@Singleton
class SelfHealthRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: SelfHealthDao,
    ) {
        @Volatile private var lastAt = 0L

        suspend fun snapshot(minIntervalMs: Long = 10 * 60_000L) {
            val now = System.currentTimeMillis()
            if (now - lastAt < minIntervalMs) return
            lastAt = now
            val stats =
                runCatching { (context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as SystemHealthManager).takeMyUidSnapshot() }.getOrNull() ?: return
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }
            dao.insert(
                SelfHealthEntity(
                    timestamp = now,
                    cpuUserMs = stats.measurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS),
                    cpuSystemMs = stats.measurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS),
                    wakelockMs = stats.timerTotal(UidHealthStats.TIMERS_WAKELOCKS_PARTIAL),
                    sensorMs = stats.timerTotal(UidHealthStats.TIMERS_SENSORS),
                    mobileMams = stats.measurement(UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS),
                    wifiMams = stats.measurement(UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS),
                    bluetoothMams = stats.measurement(UidHealthStats.MEASUREMENT_BLUETOOTH_POWER_MAMS),
                    batteryLevel = level,
                    chargeCounterUah = counter,
                ),
            )
        }

        suspend fun samplesSince(since: Long) = dao.since(since)

        suspend fun prune(cutoff: Long) = dao.deleteOlderThan(cutoff)

        suspend fun clearAll() = dao.deleteAll()

        /** The power (mA) of the sensors HabitMiner uses, as the phone reports it. */
        fun sensorPower(): PowerModel.SensorPower {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            fun p(type: Int) = sm.getDefaultSensor(type)?.power?.toDouble() ?: 0.0
            return PowerModel.SensorPower(
                accelerometer = p(Sensor.TYPE_ACCELEROMETER),
                gyroscope = p(Sensor.TYPE_GYROSCOPE),
                light = p(Sensor.TYPE_LIGHT),
                proximity = p(Sensor.TYPE_PROXIMITY),
                stepCounter = p(Sensor.TYPE_STEP_COUNTER),
            )
        }

        private fun HealthStats.measurement(key: Int): Long = runCatching { if (hasMeasurement(key)) getMeasurement(key) else 0L }.getOrDefault(0L)

        private fun HealthStats.timerTotal(key: Int): Long =
            runCatching { if (hasTimers(key)) getTimers(key).values.sumOf { it.time } else 0L }.getOrDefault(0L)
    }
