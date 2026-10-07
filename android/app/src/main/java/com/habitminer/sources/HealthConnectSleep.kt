package com.habitminer.sources

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.habitminer.analytics.ExternalSleep
import com.habitminer.data.PrefsKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads sleep sessions from Health Connect, when you turn it on: a watch or sleep app knows
 * when you actually slept, which beats guessing from the phone. Read-only; HabitMiner never
 * writes to Health Connect.
 */
@Singleton
class HealthConnectSleep
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        enum class Status { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

        val permission: String = HealthPermission.getReadPermission(SleepSessionRecord::class)

        private val prefs get() = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        var enabled: Boolean
            get() = prefs.getBoolean(PrefsKeys.HEALTH_CONNECT_ENABLED, false)
            set(value) = prefs.edit().putBoolean(PrefsKeys.HEALTH_CONNECT_ENABLED, value).apply()

        fun status(): Status =
            when (runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
                HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_UPDATE
                else -> Status.UNAVAILABLE
            }

        suspend fun hasPermission(): Boolean =
            status() == Status.AVAILABLE &&
                runCatching { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().contains(permission) }.getOrDefault(false)

        /** Sleep sessions overlapping [from]..[to]. Health Connect only allows this while the app is open. */
        suspend fun read(
            from: Long,
            to: Long,
        ): List<ExternalSleep> {
            val client = HealthConnectClient.getOrCreate(context)
            val out = mutableListOf<ExternalSleep>()
            var page: String? = null
            do {
                val response =
                    client.readRecords(
                        ReadRecordsRequest(
                            SleepSessionRecord::class,
                            TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                            pageToken = page,
                        ),
                    )
                response.records.forEach { r ->
                    val awake =
                        r.stages.filter { it.stage in AWAKE_STAGES }.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }
                    out += ExternalSleep(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), awake)
                }
                page = response.pageToken
            } while (page != null)
            return out
        }

        companion object {
            private val AWAKE_STAGES =
                setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        }
    }
