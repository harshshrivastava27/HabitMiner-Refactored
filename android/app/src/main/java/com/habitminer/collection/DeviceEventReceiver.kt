package com.habitminer.collection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.habitminer.data.DeviceEventDao
import com.habitminer.data.DeviceEventEntity
import com.habitminer.data.PrefsKeys
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DeviceEventReceiver : BroadcastReceiver() {
    @Inject
    lateinit var deviceEventDao: DeviceEventDao

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DataCollectionWorker.schedulePeriodicWork(context)

            val preferences = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences.getBoolean(PrefsKeys.COLLECTION_ENABLED, true)) {
                val serviceIntent =
                    Intent(context, MonitoringService::class.java).apply {
                        action = MonitoringService.ACTION_START
                    }
                try {
                    // BOOT_COMPLETED is an allowed exemption from background start restrictions on API 31+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException on API 31+ if somehow restricted
                    Log.w("HabitMiner", "Could not start MonitoringService on boot: ${e.message}")
                }
            }
            return
        }

        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deviceEventDao.insert(
                    DeviceEventEntity(eventType = EVENT_UNLOCK),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EVENT_UNLOCK = DeviceEvents.UNLOCK
        const val EVENT_NOTIFICATION = DeviceEvents.NOTIFICATION
    }
}
