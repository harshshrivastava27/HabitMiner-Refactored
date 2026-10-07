package com.habitminer.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.habitminer.analytics.TimeUtil
import com.habitminer.data.DeviceEventEntity
import com.habitminer.data.PrefsKeys
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.proactive.ProactiveEngine
import com.habitminer.repository.ContextRepository
import com.habitminer.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Keeps HabitMiner running in the background: shows the ongoing notification, listens for
 * unlocks (Android only delivers those to receivers registered at runtime) and keeps the
 * reading alarm going. The readings themselves happen in [ReadingRunner]; the service no
 * longer holds a wake lock or runs its own timer loop.
 */
@AndroidEntryPoint
class MonitoringService : Service() {
    @Inject lateinit var contextRepository: ContextRepository

    @Inject lateinit var appIdentityResolver: AppIdentityResolver

    @Inject lateinit var readingRunner: ReadingRunner

    @Inject lateinit var proactiveEngine: ProactiveEngine

    @Inject lateinit var wifiPlaceProvider: WifiPlaceProvider

    @Inject lateinit var stepCounterMonitor: StepCounterMonitor

    @Inject lateinit var contextEventRecorder: ContextEventRecorder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var eventReadingJob: Job? = null

    /** What the notification currently says, so it's only re-posted when that changes. */
    @Volatile private var shownContent: String? = null

    @Volatile private var todayScreenTimeMs: Long = 0L

    @Volatile private var lastUsedApp: String = ""

    private val unlockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                serviceScope.launch {
                    contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = DeviceEvents.UNLOCK))
                }
                requestEventReading(ReadingRunner.Trigger.UNLOCK)
            }
        }
    private var unlockReceiverRegistered = false

    companion object {
        const val ACTION_START = "com.habitminer.action.START"
        const val ACTION_PAUSE = "com.habitminer.action.PAUSE"
        const val ACTION_RESUME = "com.habitminer.action.RESUME"
        const val ACTION_STOP = "com.habitminer.action.STOP"
        const val ACTION_COLLECT_NOW = "com.habitminer.action.COLLECT_NOW"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "monitoring_channel"

        val isServiceRunning = MutableStateFlow(false)

        /**
         * Asks the running service for a fresh reading (when the app is opened, so the
         * surroundings shown are current). Readings are rate-limited to one every 5 minutes.
         */
        fun requestFreshReading(context: Context) {
            if (!isServiceRunning.value) return
            runCatching {
                context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_COLLECT_NOW))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isServiceRunning.value = true
        stepCounterMonitor.start()
        contextEventRecorder.start()
        try {
            ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            unlockReceiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("HabitMiner", "Could not register unlock receiver", e)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                setPaused(true)
                refreshNotification(force = true)
            }
            ACTION_RESUME -> {
                setPaused(false)
                refreshNotification(force = true)
                requestEventReading(ReadingRunner.Trigger.SCHEDULE)
            }
            ACTION_STOP -> {
                ReadingAlarm.cancel(this)
                stopSelf()
            }
            ACTION_COLLECT_NOW -> {
                promoteToForeground()
                ensureAlarm()
                requestEventReading(ReadingRunner.Trigger.APP_OPEN)
            }
            else -> {
                // ACTION_START, a restart after the system stopped us, or boot.
                promoteToForeground()
                ensureAlarm()
                refreshNotification(force = true)
            }
        }
        return START_STICKY
    }

    private fun setPaused(paused: Boolean) {
        if (isPaused() == paused) return
        getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PrefsKeys.MONITORING_PAUSED, paused).apply()
        serviceScope.launch {
            contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = if (paused) DeviceEvents.PAUSED else DeviceEvents.RESUMED))
        }
    }

    private fun isPaused(): Boolean = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PrefsKeys.MONITORING_PAUSED, false)

    /** Starts the reading alarm chain if it isn't already waiting. */
    private fun ensureAlarm() {
        if (!ReadingAlarm.isScheduled(this)) ReadingAlarm.schedule(this, 60_000L)
    }

    private fun requestEventReading(trigger: ReadingRunner.Trigger) {
        if (isPaused() || eventReadingJob?.isActive == true) return
        eventReadingJob =
            serviceScope.launch {
                // After an unlock, wait until the phone is actually in use before sampling.
                if (trigger == ReadingRunner.Trigger.UNLOCK) delay(3_000L)
                runCatching { readingRunner.read(trigger) }
                    .onFailure { android.util.Log.w("HabitMiner", "Event reading failed", it) }
                // Right after an unlock is a good moment for a question or a nudge.
                if (trigger == ReadingRunner.Trigger.UNLOCK) runCatching { proactiveEngine.tick(afterUnlock = true) }
                refreshNotification()
            }
    }

    private fun promoteToForeground() {
        val notification = buildNotification(notificationContent())
        // Wi-Fi places need the location type so the network's BSSID isn't redacted while the
        // app is in the background. Fall back to the plain type if Android refuses it.
        val wantsLocation = wifiPlaceProvider.isEnabled() && wifiPlaceProvider.hasPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            try {
                startForeground(NOTIFICATION_ID, notification, if (wantsLocation) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base)
            } catch (e: Exception) {
                android.util.Log.w("HabitMiner", "Location service type refused, continuing without places", e)
                startForeground(NOTIFICATION_ID, notification, base)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (wantsLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                )
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Re-posts the notification only when its text changed (each post wakes the system UI). */
    private fun refreshNotification(force: Boolean = false) {
        serviceScope.launch {
            runCatching {
                val zone = ZoneId.systemDefault()
                val startOfDay = TimeUtil.startOfDay(TimeUtil.dateOf(System.currentTimeMillis(), zone), zone)
                val today = contextRepository.getUsageSince(startOfDay).filterNot { appIdentityResolver.isLauncher(it.packageName) }
                todayScreenTimeMs = today.sumOf { it.durationMs }
                lastUsedApp = today.maxByOrNull { it.startTime }?.let { appIdentityResolver.getAppName(it.packageName) } ?: ""
            }
            val content = notificationContent()
            if (!force && content == shownContent) return@launch
            shownContent = content
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(content))
        }
    }

    private fun notificationContent(): String {
        if (isPaused()) return "Paused · tap Resume to continue"
        val h = TimeUnit.MILLISECONDS.toHours(todayScreenTimeMs)
        val m = TimeUnit.MILLISECONDS.toMinutes(todayScreenTimeMs) % 60
        val time = if (h > 0) "${h}h ${m}m" else "${m}m"
        return "$time on your phone today" + if (lastUsedApp.isNotEmpty()) " · last: $lastUsedApp" else ""
    }

    private fun buildNotification(content: String): Notification {
        val paused = isPaused()
        val mainIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val mainPendingIntent = PendingIntent.getActivity(this, 3, mainIntent, PendingIntent.FLAG_IMMUTABLE)
        val action =
            if (paused) {
                NotificationCompat.Action(
                    0,
                    "Resume",
                    PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                NotificationCompat.Action(
                    0,
                    "Pause",
                    PendingIntent.getService(this, 2, Intent(this, MonitoringService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE),
                )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "Monitoring paused" else "Monitoring")
            .setContentText(content)
            .setSmallIcon(com.habitminer.R.drawable.ic_stat_habitminer)
            .setContentIntent(mainPendingIntent)
            .addAction(action)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Background monitoring", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while HabitMiner records your usage. You can hide it in system settings."
                setShowBadge(false)
            }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        stepCounterMonitor.stop()
        contextEventRecorder.stop()
        serviceScope.cancel()
    }
}
