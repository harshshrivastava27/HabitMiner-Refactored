package com.habitminer.collection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.habitminer.analytics.StepMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a listener on the hardware step counter for as long as the app process lives.
 *
 * The step counter only reports when a step happens, and many phones send nothing on
 * registration, so the old "listen for 2 seconds while the screen is on" approach almost
 * always timed out. The counter runs in low-power hardware and batches its reports
 * (here up to 1 minute), so keeping it registered costs very little battery.
 *
 * It also keeps the last few minutes of reports so a reading can tell whether the user was
 * walking just before it ([stepsInLast]), which is far more reliable than a few seconds of
 * accelerometer data taken while the phone is held steady in a hand.
 */
@Singleton
class StepCounterMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SensorEventListener2 {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val prefs = context.getSharedPreferences("step_prefs", Context.MODE_PRIVATE)

        private val _stepsToday = MutableStateFlow(restoreState()?.takeIf { it.day == LocalDate.now() }?.stepsToday ?: -1L)

        /** Steps counted today, or -1 when no reading has arrived yet. */
        val stepsToday: StateFlow<Long> = _stepsToday.asStateFlow()

        @Volatile private var registered = false

        @Volatile private var latestCounter: Long = prefs.getLong(KEY_LAST, -1L)

        /** Recent counter reports (device uptime in ms), for "steps in the last few minutes". */
        private var history: List<StepMath.StepPoint> = emptyList()

        @Volatile private var pendingFlush: CompletableDeferred<Unit>? = null

        /** Today's counter state, kept in memory between saves. */
        private var dayState: StepMath.DayState? = null
        private var savedDay: LocalDate? = null
        private var lastSavedAt = 0L

        fun hasSensor(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        fun hasPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** Starts listening if possible; safe to call repeatedly. Returns whether it is listening. */
        @Synchronized
        fun start(): Boolean {
            if (registered) return true
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
            if (!hasPermission()) return false
            registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_LATENCY_US)
            return registered
        }

        @Synchronized
        fun stop() {
            if (registered) sensorManager.unregisterListener(this)
            registered = false
            // Keep the latest state across the restart.
            dayState?.let { state ->
                prefs.edit()
                    .putString(KEY_DAY, state.day.toString())
                    .putLong(KEY_BASE, state.base)
                    .putLong(KEY_CARRIED, state.carried)
                    .putLong(KEY_LAST, state.last)
                    .apply()
                savedDay = state.day
            }
        }

        /**
         * Asks the sensor hub to deliver any batched reports now (they can otherwise be held
         * for up to a minute) and waits briefly for them, so a reading sees the latest steps.
         */
        suspend fun flush(timeoutMs: Long = 1_000L) {
            if (!registered) return
            val done = CompletableDeferred<Unit>()
            pendingFlush = done
            val accepted = runCatching { sensorManager.flush(this) }.getOrDefault(false)
            if (accepted) withTimeoutOrNull(timeoutMs) { done.await() }
            pendingFlush = null
        }

        override fun onFlushCompleted(sensor: Sensor?) {
            pendingFlush?.complete(Unit)
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
            record(event.values[0].toLong(), eventTimeMs(event.timestamp))
        }

        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int,
        ) = Unit

        /** Feeds a counter value (also used by the one-off read fallback). */
        @Synchronized
        fun record(
            counter: Long,
            atUptimeMs: Long = SystemClock.elapsedRealtime(),
        ) {
            latestCounter = counter
            history = StepMath.trim(history + StepMath.StepPoint(atUptimeMs, counter), SystemClock.elapsedRealtime(), HISTORY_MS)
            val state = StepMath.advance(dayState ?: restoreState(), LocalDate.now(), counter)
            dayState = state
            // Saved at most every few minutes (and on a new day): every batch while walking
            // used to write the preferences file.
            val nowMs = SystemClock.elapsedRealtime()
            if (state.day != savedDay || nowMs - lastSavedAt > SAVE_INTERVAL_MS) {
                prefs.edit()
                    .putString(KEY_DAY, state.day.toString())
                    .putLong(KEY_BASE, state.base)
                    .putLong(KEY_CARRIED, state.carried)
                    .putLong(KEY_LAST, state.last)
                    .apply()
                savedDay = state.day
                lastSavedAt = nowMs
            }
            _stepsToday.value = state.stepsToday
        }

        /** Steps taken in the last [windowMs], or null when the counter hasn't reported since the app started. */
        @Synchronized
        fun stepsInLast(windowMs: Long): Int? = StepMath.stepsInWindow(history, SystemClock.elapsedRealtime(), windowMs)

        /**
         * Steps since the previous context snapshot, or null if the counter hasn't reported
         * yet. Each call moves the snapshot marker forward.
         */
        @Synchronized
        fun takeDeltaSinceLastSnapshot(): Int? {
            val current = latestCounter.takeIf { it >= 0 } ?: return null
            val previous = prefs.getLong(KEY_SNAPSHOT, -1L).takeIf { it >= 0 }
            prefs.edit().putLong(KEY_SNAPSHOT, current).apply()
            return StepMath.delta(previous, current).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** Re-checks the date so "today" resets after midnight even without new steps. */
        fun refreshDay() {
            val state = dayState ?: restoreState() ?: return
            if (state.day != LocalDate.now()) _stepsToday.value = 0L
        }

        /**
         * When the steps happened. Batched reports carry the time of the step itself (device
         * uptime in ns); a value that can't be right falls back to the delivery time.
         */
        private fun eventTimeMs(timestampNs: Long): Long {
            val now = SystemClock.elapsedRealtime()
            val at = timestampNs / 1_000_000L
            return if (at in (now - 24 * 60 * 60 * 1000L)..(now + 5_000L)) at else now
        }

        private fun restoreState(): StepMath.DayState? {
            val day = prefs.getString(KEY_DAY, null) ?: return null
            return runCatching {
                StepMath.DayState(
                    day = LocalDate.parse(day),
                    base = prefs.getLong(KEY_BASE, 0L),
                    carried = prefs.getLong(KEY_CARRIED, 0L),
                    last = prefs.getLong(KEY_LAST, 0L),
                )
            }.getOrNull()
        }

        companion object {
            private const val MAX_LATENCY_US = 60_000_000 // batch reports for up to 1 minute
            private const val SAVE_INTERVAL_MS = 5 * 60 * 1000L
            private const val HISTORY_MS = 15 * 60 * 1000L
            private const val KEY_DAY = "day"
            private const val KEY_BASE = "base"
            private const val KEY_CARRIED = "carried"
            private const val KEY_LAST = "last"
            private const val KEY_SNAPSHOT = "snapshot_counter"
        }
    }
