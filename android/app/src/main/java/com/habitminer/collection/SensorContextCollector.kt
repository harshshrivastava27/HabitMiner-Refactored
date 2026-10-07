package com.habitminer.collection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.habitminer.analytics.ContextLabels
import com.habitminer.analytics.MotionMath
import com.habitminer.analytics.SignalStats
import com.habitminer.data.ContextSnapshotEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.sqrt

@Singleton
class SensorContextCollector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val stepCounterMonitor: StepCounterMonitor,
    ) {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensorThread = android.os.HandlerThread("SensorThread").apply { start() }
        private val sensorHandler = Handler(sensorThread.looper)

        /** Called by MonitoringService.onDestroy() to stop the background thread cleanly. */
        fun shutdown() {
            sensorThread.quitSafely()
        }

        suspend fun collectSnapshot(
            unlockCount: Int,
            isScreenOn: Boolean,
            notificationsLastHour: Int,
            collectSensors: Boolean,
            batteryLevel: Int,
            isCharging: Boolean,
            wifiPlace: String? = null,
            includeGyro: Boolean = true,
            /** Light and proximity (Settings > What's recorded). */
            light: Boolean = true,
            /** Accelerometer and gyroscope. */
            motion: Boolean = true,
        ): ContextSnapshotEntity {
            val timestamp = System.currentTimeMillis()
            var sensingMs = 0L

            var lightLux = -1f
            var accelStats: SignalStats? = null
            var gyroStats: SignalStats? = null
            var proximityNear: Boolean? = null

            if (collectSensors) {
                val sensingStart = SystemClock.elapsedRealtime()
                kotlinx.coroutines.coroutineScope {
                    val lightDeferred = async { if (light) collectLightLevel() else null }
                    val accelDeferred = async { if (motion) collectMotionState(Sensor.TYPE_ACCELEROMETER) else null }
                    val gyroDeferred = async { if (includeGyro && motion) collectMotionState(Sensor.TYPE_GYROSCOPE) else null }
                    val proxDeferred = async { if (light) collectProximityState() else null }

                    lightLux = lightDeferred.await() ?: -1f
                    accelStats = accelDeferred.await()
                    gyroStats = gyroDeferred.await()
                    proximityNear = proxDeferred.await()
                }
                sensingMs = SystemClock.elapsedRealtime() - sensingStart
            }

            // Steps come from the always-on step counter, so they are recorded whether or not
            // the screen is on (the other sensors are only sampled with the screen on).
            // Flushing first delivers steps the sensor hub is still holding in its batch.
            var recentSteps = -1
            if (stepCounterMonitor.hasPermission() && stepCounterMonitor.hasSensor() && stepCounterMonitor.start()) {
                stepCounterMonitor.flush()
                recentSteps = stepCounterMonitor.stepsInLast(ContextLabels.RECENT_STEPS_WINDOW_MS) ?: -1
            }
            val stepsDelta = collectStepDelta()

            return ContextSnapshotEntity(
                timestamp = timestamp,
                accelMean = accelStats?.mean ?: -1f,
                accelVariance = accelStats?.variance ?: -1f,
                accelStd = accelStats?.std ?: -1f,
                accelMin = accelStats?.min ?: -1f,
                accelMax = accelStats?.max ?: -1f,
                accelEnergy = accelStats?.energy ?: -1f,
                gyroMean = gyroStats?.mean ?: -1f,
                gyroVariance = gyroStats?.variance ?: -1f,
                gyroStd = gyroStats?.std ?: -1f,
                gyroMin = gyroStats?.min ?: -1f,
                gyroMax = gyroStats?.max ?: -1f,
                gyroEnergy = gyroStats?.energy ?: -1f,
                lightLux = lightLux,
                proximityNear = proximityNear,
                stepsSinceLastSnapshot = stepsDelta,
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                isScreenOn = isScreenOn,
                unlockCount = unlockCount,
                notificationsLastHour = notificationsLastHour,
                wifiPlace = wifiPlace,
                sensingMs = sensingMs,
                recentSteps = recentSteps,
            )
        }

        private suspend fun collectLightLevel(): Float? =
            withTimeoutOrNull(2000L) {
                suspendCancellableCoroutine { continuation ->
                    val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
                    if (lightSensor == null) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent?) {
                                if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                                    sensorManager.unregisterListener(this)
                                    if (continuation.isActive) {
                                        continuation.resume(event.values[0])
                                    }
                                }
                            }

                            override fun onAccuracyChanged(
                                sensor: Sensor?,
                                accuracy: Int,
                            ) {}
                        }

                    val registered = sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                    if (!registered) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation {
                        sensorManager.unregisterListener(listener)
                    }
                }
            }

        /**
         * Listens for [MOTION_WARM_UP_MS] + [MOTION_WINDOW_MS] at ~25 Hz and summarises the
         * magnitude. The first few hundred ms are dropped because some drivers replay a cached
         * value on registration, and a 2.5 s window spans several steps, so walking with the
         * phone held steady still shows up (the old 12-sample burst lasted under a second).
         */
        private suspend fun collectMotionState(sensorType: Int): SignalStats? {
            val sensor = sensorManager.getDefaultSensor(sensorType) ?: return null
            val readings = ConcurrentLinkedQueue<Pair<Long, Float>>()
            val start = SystemClock.elapsedRealtime()
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type != sensorType || readings.size >= MAX_MOTION_READINGS) return
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        readings.add((SystemClock.elapsedRealtime() - start) to sqrt(x * x + y * y + z * z))
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {}
                }
            // 25 Hz is plenty for walking (about 2 steps a second) at half the cost of 50 Hz.
            if (!sensorManager.registerListener(listener, sensor, MOTION_SAMPLING_US, sensorHandler)) return null
            try {
                delay(MOTION_WARM_UP_MS + MOTION_WINDOW_MS)
            } finally {
                sensorManager.unregisterListener(listener)
            }
            return MotionMath.stats(readings.toList(), MOTION_WARM_UP_MS, rejectConstant = sensorType == Sensor.TYPE_ACCELEROMETER)
        }

        private suspend fun collectProximityState(): Boolean? =
            withTimeoutOrNull(2000L) {
                suspendCancellableCoroutine { continuation ->
                    val proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                    if (proxSensor == null) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val listener =
                        object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent?) {
                                if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
                                    sensorManager.unregisterListener(this)
                                    if (continuation.isActive) {
                                        val distance = event.values[0]
                                        val isNear = distance < proxSensor.maximumRange
                                        continuation.resume(isNear)
                                    }
                                }
                            }

                            override fun onAccuracyChanged(
                                sensor: Sensor?,
                                accuracy: Int,
                            ) {}
                        }

                    val registered = sensorManager.registerListener(listener, proxSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                    if (!registered) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation {
                        sensorManager.unregisterListener(listener)
                    }
                }
            }

        private suspend fun collectStepDelta(): Int {
            if (!stepCounterMonitor.hasPermission() || !stepCounterMonitor.hasSensor()) return -1
            stepCounterMonitor.start()
            stepCounterMonitor.takeDeltaSinceLastSnapshot()?.let { return it }

            // The monitor hasn't heard from the counter yet (no steps since the app started).
            // Some phones report the current total on registration, so try a short read.
            val counter =
                withTimeoutOrNull(2000L) {
                    suspendCancellableCoroutine<Long?> { continuation ->
                        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                        if (stepSensor == null) {
                            continuation.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        val listener =
                            object : SensorEventListener {
                                override fun onSensorChanged(event: SensorEvent?) {
                                    if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
                                        sensorManager.unregisterListener(this)
                                        if (continuation.isActive) continuation.resume(event.values[0].toLong())
                                    }
                                }

                                override fun onAccuracyChanged(
                                    sensor: Sensor?,
                                    accuracy: Int,
                                ) {}
                            }
                        if (!sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)) {
                            continuation.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
                    }
                }
            if (counter == null) return -1
            stepCounterMonitor.record(counter)
            return stepCounterMonitor.takeDeltaSinceLastSnapshot() ?: -1
        }

        companion object {
            private const val MOTION_WARM_UP_MS = 300L
            private const val MOTION_WINDOW_MS = 2_500L
            private const val MAX_MOTION_READINGS = 400
            private const val MOTION_SAMPLING_US = 40_000
        }
    }
