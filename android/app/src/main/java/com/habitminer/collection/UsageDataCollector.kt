package com.habitminer.collection

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.habitminer.data.AppUsageEntity
import com.habitminer.domain.AppIdentityResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageDataCollector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appIdentityResolver: AppIdentityResolver,
    ) {
        private val appCategoryMap: Map<String, String> =
            mapOf(
                "com.instagram.android" to "SOCIAL",
                "com.facebook.katana" to "SOCIAL",
                "com.twitter.android" to "SOCIAL",
                "com.whatsapp" to "COMMUNICATION",
                "org.telegram.messenger" to "COMMUNICATION",
                "com.snapchat.android" to "SOCIAL",
                "com.google.android.youtube" to "ENTERTAINMENT",
                "com.netflix.mediaclient" to "ENTERTAINMENT",
                "com.spotify.music" to "ENTERTAINMENT",
                "com.google.android.apps.maps" to "NAVIGATION",
                "com.google.android.gm" to "PRODUCTIVITY",
                "com.microsoft.launcher" to "PRODUCTIVITY",
                "com.microsoft.teams" to "PRODUCTIVITY",
                "com.slack" to "PRODUCTIVITY",
                "com.notion.id" to "PRODUCTIVITY",
                "com.google.android.apps.docs" to "PRODUCTIVITY",
                "com.google.android.apps.sheets" to "PRODUCTIVITY",
                "com.google.android.calendar" to "PRODUCTIVITY",
                "com.amazon.mShop.android.shopping" to "SHOPPING",
                "com.flipkart.android" to "SHOPPING",
                "com.gamestar.perfectpiano" to "GAMING",
                "com.supercell.clashofclans" to "GAMING",
                "com.mojang.minecraftpe" to "GAMING",
                "com.pubg.imobile" to "GAMING",
                "com.reddit.frontpage" to "SOCIAL",
                "com.linkedin.android" to "PRODUCTIVITY",
                "com.google.android.keep" to "PRODUCTIVITY",
                "com.microsoft.office.word" to "PRODUCTIVITY",
                "com.microsoft.office.excel" to "PRODUCTIVITY",
                "jp.naver.line.android" to "COMMUNICATION",
                "com.viber.voip" to "COMMUNICATION",
                "com.skype.raider" to "COMMUNICATION",
                "com.zhiliaoapp.musically" to "ENTERTAINMENT",
                "com.google.android.apps.tachyon" to "COMMUNICATION",
                "com.duolingo" to "EDUCATION",
                "org.khanacademy.android" to "EDUCATION",
                "com.coursera.app" to "EDUCATION",
                "com.amazon.kindle" to "EDUCATION",
                "com.google.android.apps.podcasts" to "ENTERTAINMENT",
                "com.amazon.music" to "ENTERTAINMENT",
                "com.gaana" to "ENTERTAINMENT",
                "com.jio.media.jiocinema" to "ENTERTAINMENT",
                "com.hotstar" to "ENTERTAINMENT",
                "com.google.android.apps.fitness" to "HEALTH",
                "com.nike.plusgps" to "HEALTH",
                "com.strava" to "HEALTH",
                "com.ola.client" to "NAVIGATION",
                "com.ubercab" to "NAVIGATION",
                "com.google.android.dialer" to "COMMUNICATION",
                "com.android.contacts" to "COMMUNICATION",
            )

        fun getTimeSlot(hourOfDay: Int): String {
            return when (hourOfDay) {
                in 6..11 -> "MORNING"
                in 12..16 -> "AFTERNOON"
                in 17..21 -> "EVENING"
                else -> "NIGHT"
            }
        }

        /**
         * Counts unlocks from the system event log (KEYGUARD_HIDDEN, API 28+) by reading it
         * directly. Prefer [ContextRepository.countUnlocksSince], which reads the copy that
         * [UsageIngestor] keeps in the database; this direct read is for tests and one-offs.
         * Unlike our USER_PRESENT receiver this also sees unlocks that happened while
         * HabitMiner was not running. Returns null when the platform can't provide it.
         */
        fun countUnlocksSince(sinceMs: Long): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usageStatsManager.queryEvents(sinceMs, System.currentTimeMillis()) ?: return null
            val event = UsageEvents.Event()
            var count = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) count++
            }
            return count
        }

        /**
         * Timestamps of every unlock since [sinceMs] from the system event log (API 28+).
         * Used for pickup analysis and sleep estimation. Null when the platform can't provide it.
         */
        fun getUnlockTimesSince(sinceMs: Long): List<Long>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usageStatsManager.queryEvents(sinceMs, System.currentTimeMillis()) ?: return null
            val event = UsageEvents.Event()
            val times = mutableListOf<Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) times.add(event.timeStamp)
            }
            return times
        }

        fun getDayType(dayOfWeek: Int): String {
            return if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                "WEEKEND"
            } else {
                "WEEKDAY"
            }
        }

        /** PackageManager lookups are slow, and a package's category doesn't change. */
        private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun getCategoryForPackage(packageName: String): String = categoryCache.getOrPut(packageName) { lookUpCategory(packageName) }

        private fun lookUpCategory(packageName: String): String {
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                when (info.category) {
                    ApplicationInfo.CATEGORY_GAME -> "GAMING"
                    ApplicationInfo.CATEGORY_AUDIO,
                    ApplicationInfo.CATEGORY_VIDEO,
                    -> "ENTERTAINMENT"
                    ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                    ApplicationInfo.CATEGORY_UNDEFINED -> appCategoryMap[packageName] ?: "OTHER"
                    else -> appCategoryMap[packageName] ?: "OTHER"
                }
            } catch (e: PackageManager.NameNotFoundException) {
                appCategoryMap[packageName] ?: "OTHER"
            }
        }

        /** What one pass over the usage-event log produced. */
        data class UsageRead(
            /** Sessions that ended after the requested start (including ones still open). */
            val sessions: List<AppUsageEntity>,
            /** Lock-screen, screen and power events in the window, oldest first. */
            val events: List<com.habitminer.data.DeviceEventEntity>,
            /** Start of the earliest session still open when the read ended, or null if none. */
            val openSince: Long?,
            /** The app in front when the read ended (the most recently opened one still open). */
            val foreground: String? = null,
        )

        /**
         * Reads Android's usage-event log from [queryStart] to [endMs] once and turns it into
         * app sessions and device events. Sessions ending before [sinceMs] are dropped: they
         * were stored by an earlier read. [queryStart] can be earlier than [sinceMs] so a
         * session that was already open last time has its start event in the window.
         */
        fun read(
            queryStart: Long,
            endMs: Long,
            sinceMs: Long,
            prevStoredPackage: String? = null,
            isHistorical: Boolean = false,
        ): UsageRead {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usageStatsManager.queryEvents(queryStart.coerceAtLeast(0L), endMs)

            val result = mutableListOf<AppUsageEntity>()
            val deviceEvents = mutableListOf<com.habitminer.data.DeviceEventEntity>()
            val startTimes = mutableMapOf<String, Long>()
            val activeCounts = mutableMapOf<String, Int>()

            val foregroundEvent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND
                }
            val backgroundEvent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    UsageEvents.Event.ACTIVITY_PAUSED
                } else {
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND
                }
            val stoppedEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) UsageEvents.Event.ACTIVITY_STOPPED else -1

            fun close(
                pkg: String,
                start: Long,
                end: Long,
            ) {
                val duration = end - start
                if (duration > 2000) result.add(entity(pkg, start, end, duration, isHistorical))
            }

            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                val pkg = event.packageName

                DeviceEvents.nameFor(type)?.let { name ->
                    deviceEvents.add(com.habitminer.data.DeviceEventEntity(eventType = name, timestamp = event.timeStamp))
                }

                // Screen off, lock screen shown or shutdown: whatever was in front has ended.
                if (type == DeviceEvents.TYPE_SCREEN_NON_INTERACTIVE ||
                    type == DeviceEvents.TYPE_KEYGUARD_SHOWN ||
                    type == DeviceEvents.TYPE_DEVICE_SHUTDOWN
                ) {
                    for ((activePkg, start) in startTimes) close(activePkg, start, event.timeStamp)
                    startTimes.clear()
                    activeCounts.clear()
                    continue
                }

                // Home screen, this app and the system UI aren't counted as app use.
                if (pkg == null || appIdentityResolver.isLauncher(pkg) || pkg == context.packageName || pkg == "com.android.systemui") {
                    continue
                }

                if (type == foregroundEvent) {
                    val count = activeCounts.getOrDefault(pkg, 0)
                    if (count == 0) startTimes[pkg] = event.timeStamp
                    activeCounts[pkg] = count + 1
                } else if (type == backgroundEvent || type == stoppedEvent) {
                    val count = activeCounts.getOrDefault(pkg, 0)
                    // ACTIVITY_STOPPED acts as a force-close (process kill): drain the counter.
                    val newCount = if (type == stoppedEvent) 0 else (count - 1).coerceAtLeast(0)
                    if (newCount == 0) {
                        activeCounts.remove(pkg)
                        startTimes.remove(pkg)?.let { start -> close(pkg, start, event.timeStamp) }
                    } else {
                        activeCounts[pkg] = newCount
                    }
                    if (count == 0) startTimes.remove(pkg)
                }
            }

            // Sessions still in front when the read ended are stored up to now; the next read
            // starts from their start time and replaces the row with the longer session.
            var openSince: Long? = null
            val foreground = startTimes.maxByOrNull { it.value }?.key?.takeIf { endMs - (startTimes[it] ?: 0L) < MAX_OPEN_SESSION_MS }
            for ((pkg, start) in startTimes) {
                val end = minOf(endMs, start + MAX_OPEN_SESSION_MS)
                if (end - start > 2000L) result.add(entity(pkg, start, end, end - start, isHistorical))
                if (endMs - start < MAX_OPEN_SESSION_MS) openSince = minOf(openSince ?: start, start)
            }

            result.sortBy { it.startTime }
            val merged = mergeContiguousSessions(result)
            var tempPrev: String? = null
            for (i in merged.indices) {
                merged[i] = merged[i].copy(previousPackageName = tempPrev)
                tempPrev = merged[i].packageName
            }
            val newSessions = merged.filter { it.endTime > sinceMs }.toMutableList()
            if (newSessions.isNotEmpty() && newSessions.first().previousPackageName == null) {
                newSessions[0] = newSessions[0].copy(previousPackageName = prevStoredPackage)
            }
            return UsageRead(newSessions, deviceEvents, openSince, foreground)
        }

        private fun entity(
            pkg: String,
            start: Long,
            end: Long,
            duration: Long,
            isHistorical: Boolean,
        ): AppUsageEntity {
            val cal = Calendar.getInstance().apply { timeInMillis = start }
            return AppUsageEntity(
                id = stableSessionId(pkg, start),
                packageName = pkg,
                appName = appIdentityResolver.getAppName(pkg),
                appCategory = getCategoryForPackage(pkg),
                startTime = start,
                endTime = end,
                durationMs = duration,
                timeSlot = getTimeSlot(cal.get(Calendar.HOUR_OF_DAY)),
                dayType = getDayType(cal.get(Calendar.DAY_OF_WEEK)),
                isHistorical = isHistorical,
            )
        }

        /**
         * Sessions since [sinceMs] in one read with a fixed overlap. Kept for one-off reads;
         * the app itself goes through [UsageIngestor], which reads incrementally.
         */
        fun collectUsageSince(
            sinceMs: Long,
            prevStoredPackage: String? = null,
            isHistorical: Boolean = false,
            overlapMs: Long = 24 * 60 * 60 * 1000L,
        ): List<AppUsageEntity> =
            read(sinceMs - overlapMs, System.currentTimeMillis(), sinceMs, prevStoredPackage, isHistorical).sessions

        fun mergeContiguousSessions(sessions: List<AppUsageEntity>): MutableList<AppUsageEntity> {
            val mergedResult = mutableListOf<AppUsageEntity>()
            for (session in sessions) {
                if (mergedResult.isEmpty()) {
                    mergedResult.add(session)
                } else {
                    val prev = mergedResult.last()
                    // Same app again within 5 minutes counts as one session, but only the time
                    // it was actually in front is added: the gap (screen off, home screen) isn't
                    // use. Counting the whole span inflated screen time.
                    if (prev.packageName == session.packageName && (session.startTime - prev.endTime) <= 300000L) {
                        val newEnd = maxOf(prev.endTime, session.endTime)
                        val added = (session.endTime - maxOf(prev.endTime, session.startTime)).coerceAtLeast(0L)
                        mergedResult[mergedResult.lastIndex] =
                            prev.copy(
                                endTime = newEnd,
                                durationMs = prev.durationMs + added,
                            )
                    } else {
                        mergedResult.add(session)
                    }
                }
            }
            return mergedResult
        }

        companion object {
            /** A session open longer than this is assumed to have missed its end event. */
            const val MAX_OPEN_SESSION_MS = 4 * 60 * 60 * 1000L
        }

        private fun stableSessionId(
            packageName: String,
            startTime: Long,
        ): Long =
            "$packageName:$startTime".fold(0xcbf29ce484222325UL.toLong()) { hash, char ->
                (hash xor char.code.toLong()) * 0x100000001b3L
            }.let { if (it == 0L) 1L else it }

        fun collectHistoricalData(): List<AppUsageEntity> {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            return collectUsageSince(cal.timeInMillis, isHistorical = true)
        }
    }
