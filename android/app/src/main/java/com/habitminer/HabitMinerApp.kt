package com.habitminer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HabitMinerApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        com.habitminer.proactive.Notifier.createChannels(this)
        com.habitminer.update.UpdateWorker.schedule(
            this,
            getSharedPreferences(com.habitminer.data.PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(com.habitminer.data.PrefsKeys.UPDATE_CHECK_ENABLED, true),
        )
    }

    private fun createNotificationChannel() {
        val name = "Habit Deviation Alerts"
        val descriptionText = "Notifications for habit deviations"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel =
            NotificationChannel("habit_deviations", name, importance).apply {
                description = descriptionText
            }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
