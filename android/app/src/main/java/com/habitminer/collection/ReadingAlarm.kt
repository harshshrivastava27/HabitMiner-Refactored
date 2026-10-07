package com.habitminer.collection

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.habitminer.proactive.ProactiveEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Wakes the phone briefly for each scheduled reading.
 *
 * HabitMiner used to hold a wake lock for four hours so a timer loop could keep running in
 * Doze; Google flags anything over two hours a day as excessive. Now the reading is driven by
 * an inexact alarm that is allowed in Doze (Android limits these to about one per 9 minutes
 * while idle, which matches the 15–30 minute idle interval). The phone is awake only while
 * the reading runs, a few seconds.
 */
object ReadingAlarm {
    private const val REQUEST_CODE = 4101

    fun schedule(
        context: Context,
        delayMs: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(60_000L)
        runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context, create = true)!!) }
            .onFailure { android.util.Log.w("HabitMiner", "Could not schedule reading alarm", it) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, create = false)?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    /** True when an alarm is waiting, so the worker can re-arm a broken chain. */
    fun isScheduled(context: Context): Boolean = pendingIntent(context, create = false) != null

    private fun pendingIntent(
        context: Context,
        create: Boolean,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReadingAlarmReceiver::class.java).setAction(ReadingAlarmReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE),
        )
}

@AndroidEntryPoint
class ReadingAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var readingRunner: ReadingRunner

    @Inject lateinit var proactiveEngine: ProactiveEngine

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        // The alarm keeps the CPU awake while the broadcast is handled; this short timed lock
        // only covers the hand-off to the background thread.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HabitMiner::Reading").apply { acquire(WAKE_LOCK_MS) }
        scope.launch {
            try {
                // Runs even if the system stopped the service: the reading needs no service, and
                // keeping the chain going means no gap until the service comes back.
                if (readingRunner.isCollectionEnabled()) {
                    withTimeoutOrNull(WORK_TIMEOUT_MS) {
                        runCatching { readingRunner.read(ReadingRunner.Trigger.SCHEDULE) }
                            .onFailure { android.util.Log.w("HabitMiner", "Scheduled reading failed", it) }
                        runCatching { proactiveEngine.tick() }
                    }
                    ReadingAlarm.schedule(context, readingRunner.sensingMode.intervalMs)
                }
            } finally {
                if (lock.isHeld) lock.release()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.habitminer.action.SCHEDULED_READING"
        private const val WAKE_LOCK_MS = 30_000L
        private const val WORK_TIMEOUT_MS = 25_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
