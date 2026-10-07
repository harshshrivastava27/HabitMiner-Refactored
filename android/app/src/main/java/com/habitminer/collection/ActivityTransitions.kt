package com.habitminer.collection

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.habitminer.data.DeviceEventDao
import com.habitminer.data.DeviceEventEntity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Still, walking, running, cycling or in a vehicle, from Google Play services' activity
 * recognition. Android delivers a broadcast only when the activity changes, using the phone's
 * low-power motion hardware, so it costs next to nothing. Phones without Play services simply
 * don't record these.
 */
@Singleton
class ActivityTransitions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private fun hasPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        private fun pendingIntent(): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, ActivityTransitionReceiver::class.java), flags)
        }

        @Suppress("MissingPermission")
        fun start() {
            if (!hasPermission()) return
            val transitions =
                TYPES.keys.map { type ->
                    ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
                }
            runCatching {
                ActivityRecognition.getClient(context)
                    .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                    .addOnFailureListener { android.util.Log.i("HabitMiner", "Activity transitions unavailable: ${it.message}") }
            }
        }

        @Suppress("MissingPermission")
        fun stop() {
            if (!hasPermission()) return
            runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent()) }
        }

        companion object {
            private const val REQUEST_CODE = 77

            val TYPES =
                mapOf(
                    DetectedActivity.STILL to "still",
                    DetectedActivity.WALKING to "walking",
                    DetectedActivity.RUNNING to "running",
                    DetectedActivity.ON_BICYCLE to "cycling",
                    DetectedActivity.IN_VEHICLE to "vehicle",
                )
        }
    }

/** Stores each activity change as an ACTIVITY device event. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {
    @Inject lateinit var deviceEventDao: DeviceEventDao

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val events =
            result.transitionEvents.mapNotNull { e ->
                val name = ActivityTransitions.TYPES[e.activityType] ?: return@mapNotNull null
                val time = nowWall - (nowElapsed - e.elapsedRealTimeNanos) / 1_000_000L
                DeviceEventEntity(eventType = DeviceEvents.ACTIVITY, timestamp = time, detail = "type=$name")
            }
        if (events.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deviceEventDao.insertAll(events)
            } finally {
                pending.finish()
            }
        }
    }
}
