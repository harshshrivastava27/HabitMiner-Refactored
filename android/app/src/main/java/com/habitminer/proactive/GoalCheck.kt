package com.habitminer.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A one-off check for app-time reminders: when you're in an app you want to use less, it fires
 * about when the next reminder would be due. Inexact (no exact-alarm permission needed); a new
 * schedule replaces the old one.
 */
object GoalCheck {
    private const val REQUEST_CODE = 78

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, GoalCheckReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(
        context: Context,
        at: Long,
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context)) }
    }
}

@AndroidEntryPoint
class GoalCheckReceiver : BroadcastReceiver() {
    @Inject lateinit var proactiveEngine: ProactiveEngine

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                proactiveEngine.tick()
            } finally {
                pending.finish()
            }
        }
    }
}
