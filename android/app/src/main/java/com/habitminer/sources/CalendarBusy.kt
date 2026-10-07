package com.habitminer.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.habitminer.data.PrefsKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When your calendar says you're busy, when you turn it on. Only the times are read, never
 * titles, places or people, and nothing is stored: it's read when needed. Used so a still phone
 * in a meeting or class isn't taken for a nap, and so questions and nudges wait until after.
 */
@Singleton
class CalendarBusy
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs get() = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        var enabled: Boolean
            get() = prefs.getBoolean(PrefsKeys.CALENDAR_ENABLED, false)
            set(value) = prefs.edit().putBoolean(PrefsKeys.CALENDAR_ENABLED, value).apply()

        fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        /** Busy stretches overlapping [from]..[to], as (start, end), merged. Empty when off. */
        fun busy(
            from: Long,
            to: Long,
        ): List<Pair<Long, Long>> {
            if (!enabled || !hasPermission()) return emptyList()
            val uri =
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(from.toString())
                    .appendPath(to.toString())
                    .build()
            val projection =
                arrayOf(
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.AVAILABILITY,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                )
            val out = mutableListOf<Pair<Long, Long>>()
            runCatching {
                context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val begin = c.getLong(0)
                        val end = c.getLong(1)
                        val allDay = c.getInt(2) == 1
                        val free = c.getInt(3) == CalendarContract.Events.AVAILABILITY_FREE
                        val declined = c.getInt(4) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        // All-day items (birthdays, holidays) and long blocks don't say you were busy right then.
                        if (allDay || free || declined || end <= begin || end - begin > 12 * 60 * 60 * 1000L) continue
                        out += begin to end
                    }
                }
            }
            val merged = mutableListOf<Pair<Long, Long>>()
            for (iv in out.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && iv.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, iv.second) else merged += iv
            }
            return merged
        }

        /** True when the calendar says you're busy right now. */
        fun isBusyNow(now: Long = System.currentTimeMillis()): Boolean = busy(now - 1, now + 1).any { now in it.first until it.second }
    }
