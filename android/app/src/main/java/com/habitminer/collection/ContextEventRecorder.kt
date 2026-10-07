package com.habitminer.collection

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.habitminer.data.DeviceEventEntity
import com.habitminer.data.PrefsKeys
import com.habitminer.repository.ContextRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the phone-state changes that sharpen sleep and routine estimates: Do Not Disturb,
 * the next alarm, the charger, headphones, time zone, apps being installed or removed, and
 * (through [ActivityTransitions]) still / walking / in a vehicle.
 *
 * Android only delivers most of these to receivers registered at runtime, so
 * [MonitoringService] starts and stops this. Each one is a broadcast that arrives when the
 * state changes, so it costs nothing in between. Values are only stored when they differ from
 * the last stored one, so a service restart doesn't log duplicates.
 */
@Singleton
class ContextEventRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val activityTransitions: ActivityTransitions,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        private var registered = false
        private var audioCallback: AudioDeviceCallback? = null

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> recordDnd()
                        AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> recordNextAlarm()
                        Intent.ACTION_POWER_CONNECTED -> recordPower(true)
                        Intent.ACTION_POWER_DISCONNECTED -> recordPower(false)
                        Intent.ACTION_TIMEZONE_CHANGED -> recordTimeZone()
                    }
                }
            }

        private val packageReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED ->
                            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) insert(DeviceEvents.APP_INSTALLED, pkg, null)
                        Intent.ACTION_PACKAGE_FULLY_REMOVED -> insert(DeviceEvents.APP_REMOVED, pkg, null)
                    }
                }
            }

        fun start() {
            if (registered) return
            registered = true
            runCatching {
                val filter =
                    IntentFilter().apply {
                        addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                        addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                        addAction(Intent.ACTION_POWER_CONNECTED)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    }
                // System broadcasts: exported so the system can deliver them.
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                val packages =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                        addDataScheme("package")
                    }
                ContextCompat.registerReceiver(context, packageReceiver, packages, ContextCompat.RECEIVER_EXPORTED)
            }.onFailure { android.util.Log.w("HabitMiner", "Could not register context receivers", it) }
            startAudio()
            activityTransitions.start()
            // The state right now, in case it changed while HabitMiner wasn't running.
            recordDnd()
            recordNextAlarm()
            recordTimeZone()
        }

        fun stop() {
            if (!registered) return
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { context.unregisterReceiver(packageReceiver) }
            audioCallback?.let { cb -> runCatching { audioManager().unregisterAudioDeviceCallback(cb) } }
            audioCallback = null
            activityTransitions.stop()
        }

        // ---- Do Not Disturb ----

        private fun recordDnd() {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val mode =
                when (nm.currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "silent"
                    else -> return
                }
            insertIfChanged(DeviceEvents.DND, "mode=$mode")
        }

        // ---- Alarm ----

        private fun recordNextAlarm() {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = runCatching { am.nextAlarmClock?.triggerTime }.getOrNull()
            insertIfChanged(DeviceEvents.NEXT_ALARM, "at=${at ?: ""}")
        }

        // ---- Charger ----

        private fun recordPower(connected: Boolean) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { b ->
                val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) l * 100 / scale else null
            }
            val plug =
                when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> ""
                }
            insert(if (connected) DeviceEvents.POWER_CONNECTED else DeviceEvents.POWER_DISCONNECTED, null, "level=${level ?: ""};plug=$plug")
        }

        // ---- Time zone ----

        private fun recordTimeZone() = insertIfChanged(DeviceEvents.TIMEZONE, "zone=${TimeZone.getDefault().id}")

        // ---- Headphones and speakers ----

        private fun audioManager() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private fun startAudio() {
            val cb =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = syncAudio()

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = syncAudio()
                }
            audioCallback = cb
            // Called once right away with the devices already connected.
            runCatching { audioManager().registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
        }

        /** Compares the connected headphones and speakers with the last stored set. */
        private fun syncAudio() {
            val now =
                runCatching { audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
                    .mapNotNull { d -> kindOf(d.type)?.let { kind -> "kind=$kind;id=${hash(kind + "|" + d.productName)}" } }
                    .toSet()
            val before = prefs.getStringSet(PrefsKeys.AUDIO_DEVICES, emptySet()).orEmpty()
            if (now == before) return
            prefs.edit().putStringSet(PrefsKeys.AUDIO_DEVICES, now).apply()
            (now - before).forEach { insert(DeviceEvents.AUDIO_CONNECTED, null, it) }
            (before - now).forEach { insert(DeviceEvents.AUDIO_DISCONNECTED, null, it) }
        }

        private fun kindOf(type: Int): String? =
            when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
                else ->
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> "bluetooth"
                        else -> null
                    }
            }

        /** A short hash with a per-install salt: tells devices apart, can't be reversed to a name. */
        private fun hash(value: String): String {
            val salt =
                prefs.getString(PrefsKeys.ID_SALT, null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(PrefsKeys.ID_SALT, it).apply()
                }
            val digest = MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray())
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        // ---- Storage ----

        private fun insertIfChanged(
            type: String,
            detail: String,
        ) {
            val key = PrefsKeys.LAST_EVENT_PREFIX + type
            if (prefs.getString(key, null) == detail) return
            prefs.edit().putString(key, detail).apply()
            insert(type, null, detail)
        }

        private fun insert(
            type: String,
            pkg: String?,
            detail: String?,
        ) {
            scope.launch {
                runCatching { contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = type, packageName = pkg, detail = detail)) }
            }
        }
    }
