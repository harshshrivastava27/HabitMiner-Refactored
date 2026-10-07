package com.habitminer.collection

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.habitminer.data.DeviceEventDao
import com.habitminer.data.DeviceEventEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HabitNotificationListener : NotificationListenerService() {
    @Inject
    lateinit var deviceEventDao: DeviceEventDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPosted = HashMap<String, Long>()

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { notification ->
            if (notification.isOngoing) return
            // A group summary arrives with its children; counting both doubles the count.
            if (notification.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
            if (notification.packageName == packageName) return
            // Silent re-posts of the same notification (a timestamp tick, a reaction) aren't new.
            val now = System.currentTimeMillis()
            val last = lastPosted[notification.key]
            lastPosted[notification.key] = now
            if (last != null && now - last < REPOST_WINDOW_MS) return
            if (lastPosted.size > 500) lastPosted.entries.removeIf { now - it.value > REPOST_WINDOW_MS }
            serviceScope.launch {
                deviceEventDao.insert(
                    DeviceEventEntity(
                        eventType = DeviceEventReceiver.EVENT_NOTIFICATION,
                        packageName = notification.packageName,
                    ),
                )
            }
        }
    }

    companion object {
        private const val REPOST_WINDOW_MS = 3_000L

        fun isEnabled(context: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }
    }
}
