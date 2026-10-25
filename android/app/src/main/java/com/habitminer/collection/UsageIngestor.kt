package com.habitminer.collection

import android.content.Context
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.repository.ContextRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that reads Android's usage-event log.
 *
 * It remembers how far it got (a cursor) and reads only what's new, plus enough before that
 * to finish sessions that were still open last time. Sessions and device events (unlocks,
 * screen on/off, shutdowns) from the same pass are stored together, so screens, background
 * prompts and the sleep detector read them from the database instead of each re-reading days
 * of the system log. Before Extended, every reading re-read 24 hours and every analysis
 * re-read 8 days.
 */
@Singleton
class UsageIngestor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val collector: UsageDataCollector,
        private val contextRepository: ContextRepository,
        private val appIdentityResolver: AppIdentityResolver,
    ) {
        private val mutex = Mutex()
        private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

        private val _lastIngestAt = MutableStateFlow(0L)

        /** When the log was last read (0 before the first read in this process). */
        val lastIngestAt: StateFlow<Long> = _lastIngestAt.asStateFlow()

        @Volatile private var _foreground: String? = null

        /** The app in front at the last read (null on the home screen or with the screen off). */
        val foreground: String? get() = _foreground

        /**
         * Reads new usage events and stores them. Skips the read if the last one was less than
         * [minIntervalMs] ago, so callers can ask freely. Returns the number of sessions stored.
         */
        suspend fun ingest(minIntervalMs: Long = 0L): Int =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    val lastRun = prefs.getLong(KEY_LAST_RUN, 0L)
                    if (minIntervalMs > 0 && now - lastRun in 0 until minIntervalMs) return@withLock 0

                    val cursor = prefs.getLong(KEY_CURSOR, -1L).takeIf { it > 0 }
                    val lastStored = if (cursor == null) contextRepository.getLastInsertedUsageTimestamp() else null
                    // First read ever: take what Android still has (about a week).
                    val firstRead = cursor == null && lastStored == null
                    val since = cursor ?: lastStored ?: (now - BOOTSTRAP_MS)
                    val openSince = prefs.getLong(KEY_OPEN_SINCE, -1L).takeIf { it > 0 }
                    val queryStart =
                        minOf(since - OVERLAP_MS, openSince ?: Long.MAX_VALUE)
                            .coerceAtLeast(now - MAX_LOOKBACK_MS)

                    if (prefs.getInt(KEY_REPAIRED, 0) < REPAIR_VERSION) {
                        runCatching { repairStoredLocked() }.onFailure { android.util.Log.w("HabitMiner", "Session repair failed", it) }
                        prefs.edit().putInt(KEY_REPAIRED, REPAIR_VERSION).apply()
                    }

                    val previous = contextRepository.getLastUsedNonLauncherPackage(appIdentityResolver.getLauncherPackages())
                    val read =
                        collector.read(
                            queryStart,
                            now,
                            since,
                            previous,
                            isHistorical = firstRead,
                            lockedAtStart = contextRepository.lockedBefore(queryStart) ?: false,
                            alarmPackage = alarmPackage(),
                        )

                    // Events between queryStart and the last cursor were stored by the previous read.
                    val eventCursor = prefs.getLong(KEY_EVENT_CURSOR, if (cursor == null) 0L else since)
                    val newEvents = read.events.filter { it.timestamp > eventCursor }
                    contextRepository.insertIngestBatch(read.sessions, newEvents)

                    prefs.edit()
                        .putLong(KEY_CURSOR, now)
                        .putLong(KEY_LAST_RUN, now)
                        .putLong(KEY_EVENT_CURSOR, maxOf(eventCursor, newEvents.maxOfOrNull { it.timestamp } ?: eventCursor))
                        .putLong(KEY_OPEN_SINCE, read.openSince ?: -1L)
                        .apply()
                    _lastIngestAt.value = now
                    _foreground = read.foreground?.takeUnless { appIdentityResolver.isLauncher(it) }
                    read.sessions.size
                }
            }

        /** The app that set the next alarm, as last recorded. */
        private fun alarmPackage(): String? =
            context.getSharedPreferences(com.habitminer.data.PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(com.habitminer.data.PrefsKeys.ALARM_PACKAGE, null)

        /**
         * Brings stored sessions in line with how they're read now (also after an import):
         * sessions running past midnight are split so each day has its own time, and alarm
         * or timer screens shown over the lock screen (an alarm ringing, not phone use)
         * become ALARM_RING events.
         */
        suspend fun repairStored() =
            withContext(Dispatchers.IO) {
                mutex.withLock { repairStoredLocked() }
            }

        private suspend fun repairStoredLocked() {
            val zone = java.time.ZoneId.systemDefault()
            val all = contextRepository.getUsageSince(0L)
            if (all.isEmpty()) return
            val keyguard =
                contextRepository.getDeviceEventsOfTypesSince(listOf(DeviceEvents.KEYGUARD_SHOWN, DeviceEvents.KEYGUARD_HIDDEN), 0L)
                    .sortedBy { it.timestamp }
            val times = keyguard.map { it.timestamp }
            val alarmPkg = alarmPackage()

            fun lockedThroughout(
                start: Long,
                end: Long,
            ): Boolean {
                if (keyguard.isEmpty()) return false
                var i = times.binarySearch(start).let { if (it >= 0) it else -it - 2 }
                if (i < 0 || keyguard[i].eventType != DeviceEvents.KEYGUARD_SHOWN) return false
                // No unlock while it was in front.
                i++
                while (i < keyguard.size && keyguard[i].timestamp < end) {
                    if (keyguard[i].eventType == DeviceEvents.KEYGUARD_HIDDEN) return false
                    i++
                }
                return true
            }

            val remove = mutableListOf<Long>()
            val add = mutableListOf<com.habitminer.data.AppUsageEntity>()
            val rings = mutableListOf<com.habitminer.data.DeviceEventEntity>()
            for (s in all) {
                if (com.habitminer.analytics.AlarmApps.isAlarmApp(s.packageName, s.appName, alarmPkg) && lockedThroughout(s.startTime, s.endTime)) {
                    remove += s.id
                    rings += com.habitminer.data.DeviceEventEntity(eventType = DeviceEvents.ALARM_RING, packageName = s.packageName, timestamp = s.endTime, detail = "start=${s.startTime}")
                    continue
                }
                if (com.habitminer.analytics.DaySplit.crossesMidnight(s.startTime, s.endTime, zone)) {
                    remove += s.id
                    for (p in com.habitminer.analytics.DaySplit.split(s.startTime, s.endTime, s.durationMs, zone)) {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.start }
                        add +=
                            s.copy(
                                id = UsageDataCollector.stableSessionId(s.packageName, p.start),
                                startTime = p.start,
                                endTime = p.end,
                                durationMs = p.durationMs,
                                timeSlot = collector.getTimeSlot(cal.get(java.util.Calendar.HOUR_OF_DAY)),
                                dayType = collector.getDayType(cal.get(java.util.Calendar.DAY_OF_WEEK)),
                            )
                    }
                }
            }
            if (remove.isNotEmpty() || add.isNotEmpty()) contextRepository.replaceSessions(remove, add, rings)
        }

        /** Moves the cursor to [time] without reading, so the log before it (a pause) is skipped. */
        suspend fun skipTo(time: Long) =
            mutex.withLock {
                prefs.edit()
                    .putLong(KEY_CURSOR, time)
                    .putLong(KEY_EVENT_CURSOR, time)
                    .putLong(KEY_OPEN_SINCE, -1L)
                    .apply()
            }

        /** Forget the cursor (after clearing data), so the next read starts over. */
        fun reset() {
            prefs.edit().clear().apply()
            _lastIngestAt.value = 0L
        }

        companion object {
            private const val PREFS = "usage_ingest"
            private const val KEY_CURSOR = "cursor"
            private const val KEY_EVENT_CURSOR = "event_cursor"
            private const val KEY_OPEN_SINCE = "open_since"
            private const val KEY_LAST_RUN = "last_run"
            private const val KEY_REPAIRED = "repaired"

            /** Bump when [repairStored] learns something new, so stored sessions are fixed once more. */
            private const val REPAIR_VERSION = 1

            /** Re-read a little before the cursor in case a session closed right at it. */
            private const val OVERLAP_MS = 2 * 60 * 1000L

            /** Never read more than this far back in one go (beyond the first read). */
            private const val MAX_LOOKBACK_MS = 10L * 24 * 60 * 60 * 1000

            private const val BOOTSTRAP_MS = 8L * 24 * 60 * 60 * 1000
        }
    }
