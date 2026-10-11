package com.habitminer.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.habitminer.proactive.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Once a day, on any network, asks whether a new version is out and tells you once if so. */
@HiltWorker
class UpdateWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val checker: UpdateChecker,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val update = runCatching { checker.check() }.getOrNull()
            if (update != null && Notifier.canPost(appContext) && checker.shouldNotify(update.tag)) {
                Notifier.postUpdate(appContext, update.versionName)
            }
            return Result.success()
        }

        companion object {
            private const val NAME = "UpdateCheck"

            fun schedule(
                context: Context,
                enabled: Boolean,
            ) {
                val wm = WorkManager.getInstance(context)
                if (!enabled) {
                    wm.cancelUniqueWork(NAME)
                    return
                }
                val request =
                    PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                        .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
