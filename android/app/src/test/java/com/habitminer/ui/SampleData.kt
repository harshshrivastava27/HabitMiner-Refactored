package com.habitminer.ui

import com.habitminer.analytics.AppChange
import com.habitminer.analytics.AppGuess
import com.habitminer.analytics.Confidence
import com.habitminer.analytics.ContextInsights
import com.habitminer.analytics.DayDeviation
import com.habitminer.analytics.DeviationKind
import com.habitminer.analytics.DeviationReport
import com.habitminer.analytics.GuessRecord
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.RoutineShift
import com.habitminer.analytics.DayTypeClusterer
import com.habitminer.analytics.Heatmap
import com.habitminer.analytics.PatternGrouper
import com.habitminer.analytics.PatternRow
import com.habitminer.analytics.PickupAnalyzer
import com.habitminer.analytics.PredictabilityEvaluator
import com.habitminer.analytics.SessionGrouper
import com.habitminer.analytics.SleepDetector
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.TimedEvent
import com.habitminer.analytics.TypicalDay
import com.habitminer.analytics.WeekComparer
import com.habitminer.data.AppUsageEntity
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.data.DiscoveredHabitEntity
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.engine.FeatureSettings
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.InsightsBundle
import com.habitminer.engine.TypicalUsageCalculator
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Two weeks of realistic synthetic data for screenshot tests: late-night gaming,
 * Snapchat → Telegram in the evenings, weekday classes, sleep around 02:30–09:00.
 */
object SampleData {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private data class App(val pkg: String, val name: String, val stored: String)

    private val evony = App("com.topgames.evony", "Evony", "GAMING")
    private val snapchat = App("com.snapchat.android", "Snapchat", "SOCIAL")
    private val telegram = App("org.telegram.messenger", "Telegram", "COMMUNICATION")
    private val whatsapp = App("com.whatsapp", "WhatsApp", "COMMUNICATION")
    private val brave = App("com.brave.browser", "Brave", "OTHER")
    private val yt = App("com.drifty.yt", "YT (drifty)", "OTHER")
    private val gmail = App("com.google.android.gm", "Gmail", "PRODUCTIVITY")
    private val linkedin = App("com.linkedin.android", "LinkedIn", "PRODUCTIVITY")
    private val meet = App("com.google.android.apps.meetings", "Meet", "PRODUCTIVITY")

    private var nextId = 1L

    private fun usage(
        app: App,
        start: Long,
        minutes: Int,
    ): AppUsageEntity {
        val z = TimeUtil.zoned(start, zone)
        val slot =
            when (z.hour) {
                in 6..11 -> "MORNING"
                in 12..16 -> "AFTERNOON"
                in 17..21 -> "EVENING"
                else -> "NIGHT"
            }
        return AppUsageEntity(
            id = nextId++,
            packageName = app.pkg,
            appName = app.name,
            appCategory = app.stored,
            startTime = start,
            endTime = start + minutes * 60_000L,
            durationMs = minutes * 60_000L,
            timeSlot = slot,
            dayType = if (TimeUtil.isWeekend(z.toLocalDate())) "WEEKEND" else "WEEKDAY",
        )
    }

    private fun snapshot(
        time: Long,
        lux: Float,
        variance: Float,
        charging: Boolean,
        screenOn: Boolean,
        battery: Int,
    ) = ContextSnapshotEntity(
        id = nextId++,
        timestamp = time,
        accelMean = if (screenOn) 9.8f else -1f,
        accelVariance = if (screenOn) variance else -1f,
        accelStd = -1f,
        accelMin = -1f,
        accelMax = -1f,
        accelEnergy = if (screenOn) 96f + variance * 10 else -1f,
        gyroMean = -1f,
        gyroVariance = -1f,
        gyroStd = -1f,
        gyroMin = -1f,
        gyroMax = -1f,
        gyroEnergy = if (screenOn) 0.2f else -1f,
        lightLux = if (screenOn) lux else -1f,
        proximityNear = if (screenOn) false else null,
        stepsSinceLastSnapshot = if (screenOn) 12 else -1,
        batteryLevel = battery,
        isCharging = charging,
        isScreenOn = screenOn,
        unlockCount = 0,
        notificationsLastHour = 3,
    )

    class Data(
        val state: HabitUiState,
        val now: Long,
    )

    fun build(now: Long): Data {
        nextId = 1L
        val today = TimeUtil.dateOf(now, zone)
        val rnd = Random(42)
        val usage = mutableListOf<AppUsageEntity>()
        val unlocks = mutableListOf<Long>()
        val notifications = mutableListOf<TimedEvent>()

        fun chain(
            date: LocalDate,
            hour: Int,
            minute: Int,
            vararg steps: Pair<App, Int>,
        ) {
            var t = TimeUtil.at(date, hour, minute, zone)
            if (t > now) return
            unlocks.add(t)
            if (rnd.nextFloat() < 0.45f || hour == 9 || hour == 19) notifications.add(TimedEvent(t - 40_000L, if (rnd.nextBoolean()) whatsapp.pkg else telegram.pkg))
            for ((app, mins) in steps) {
                if (t > now) return
                val m = minOf(mins, ((now - t) / 60_000L).toInt().coerceAtLeast(1))
                usage.add(usage(app, t, m))
                t += (m + 1) * 60_000L
            }
        }

        for (back in 13 downTo 0) {
            val d = today.minusDays(back.toLong())
            val weekend = TimeUtil.isWeekend(d)
            val jitter = rnd.nextInt(0, 25)
            chain(d, 0, 5 + jitter % 10, evony to (if (weekend) 170 else 120) + rnd.nextInt(-20, 30), snapchat to 2)
            chain(d, 9, 5 + jitter, snapchat to 3, telegram to 5, whatsapp to 6)
            chain(d, 11, 0, gmail to 4, linkedin to 6, whatsapp to 3)
            chain(d, 13, 30, brave to 14, yt to 15)
            if (!weekend) chain(d, 16, 0, meet to 45)
            chain(d, 19, jitter, snapchat to 4, telegram to 10)
            chain(d, 21, 30, yt to 25 + rnd.nextInt(0, 20), whatsapp to 8)
            chain(d, 23, 0, snapchat to 3, telegram to 6)
            for (q in 0 until 20) unlocks.add(TimeUtil.at(d, 10 + q % 12, (q * 7) % 60, zone)) // quick checks
        }
        val todayUnlocks = unlocks.filter { it in TimeUtil.startOfDay(today, zone)..now }

        // Snapshots: every 15 minutes; sensors only while a session is running.
        val snapshots = mutableListOf<ContextSnapshotEntity>()
        var t = TimeUtil.startOfDay(today.minusDays(13), zone)
        while (t <= now) {
            val hour = TimeUtil.hourOf(t, zone)
            val screenOn = usage.any { t in it.startTime..it.endTime }
            val lux =
                when (hour) {
                    in 0..5 -> 2f
                    in 6..8 -> 60f
                    in 9..17 -> 320f
                    in 18..21 -> 45f
                    else -> 8f
                }
            val variance = if (hour == 18) 1.2f else 0.08f
            val charging = hour in 2..8
            snapshots.add(snapshot(t, lux, variance, charging, screenOn, battery = (100 - (hour * 3)).coerceIn(20, 100)))
            t += 15 * 60_000L
        }

        val sessions = usage.map(AnalyticsMappers::session)
        val samples = snapshots.map(AnalyticsMappers::sample)
        val todayStart = TimeUtil.startOfDay(today, zone)
        val todayUsage = usage.filter { it.startTime >= todayStart }
        val todaySessions = sessions.filter { it.start >= todayStart }
        val weekStart = TimeUtil.startOfDay(today.minusDays(6), zone)
        val nights = SleepDetector.detectRange(sessions, unlocks, samples, today, 7, now, zone)

        val habits =
            listOf(
                DiscoveredHabitEntity(1, "🌙 Night Snapchat Routine", "Snapchat → Telegram", "[]", 1f, 10, "NIGHT", "WEEKDAY", now, now - 3 * 3_600_000L),
                DiscoveredHabitEntity(2, "🌆 Evening Snapchat Routine", "Snapchat → Telegram", "[]", 0.9f, 9, "EVENING", "WEEKDAY", now, now - 26 * 3_600_000L),
                DiscoveredHabitEntity(3, "🌆 Evening WhatsApp Routine", "YT (drifty) → WhatsApp", "[]", 0.9f, 9, "EVENING", "WEEKDAY", now, now - 22 * 3_600_000L),
                DiscoveredHabitEntity(4, "🌅 Morning Snapchat Routine", "Snapchat → Telegram → WhatsApp", "[]", 0.8f, 8, "MORNING", "WEEKDAY", now, now - 9 * 3_600_000L),
                DiscoveredHabitEntity(5, "☀️ Afternoon Brave Routine", "Brave → YT (drifty)", "[]", 0.7f, 7, "AFTERNOON", "WEEKDAY", now, now - 30 * 3_600_000L),
            )

        val min = 60_000L
        val deviations =
            listOf(
                DayDeviation(
                    today, DeviationKind.LESS_USE, "Quieter than usual so far",
                    "1h 35m on your phone by 18:45, usually about 3h 33m by now. Mostly in the afternoon (−1h 10m).",
                    "ALL", 3.1f, now, true,
                ),
                DayDeviation(
                    today, DeviationKind.APP_DROP, "Much less Evony",
                    "2m by 18:45, usually about 48m by now.",
                    "Evony", 2f, now, true,
                ),
                DayDeviation(
                    today.minusDays(1), DeviationKind.APP_SPIKE, "More WhatsApp than usual",
                    "1h 20m, usually about 22m.",
                    "WhatsApp", 4.5f, now - 20 * 60 * min, false,
                ),
                DayDeviation(
                    today.minusDays(2), DeviationKind.LATE_NIGHT, "More late-night phone use",
                    "3h 25m between midnight and 6:00, usually about 1h 3m.",
                    "LATE_NIGHT", 5.1f, now - 40 * 60 * min, false,
                ),
            )
        val shift =
            RoutineShift(
                since = today.minusDays(2),
                days = 3,
                change = -0.33f,
                avgPerDayMs = 214 * min,
                usualPerDayMs = 317 * min,
                appChanges =
                    listOf(
                        AppChange("Evony", 88 * min, 59 * min),
                        AppChange("YT (drifty)", 47 * min, 19 * min),
                        AppChange("Instagram", 3 * min, 20 * min),
                    ),
            )
        val nap =
            NapCandidate(
                TimeUtil.at(today, 14, 8, zone), TimeUtil.at(today, 17, 1, zone), Confidence.HIGH,
                listOf("no steps", "dark room when you picked the phone up"),
            )
        val guesses =
            listOf(
                GuessRecord(now - 12 * min, "Snapchat", listOf("WhatsApp", "Telegram", "Superset"), "Telegram"),
                GuessRecord(now - 25 * min, "Superset", listOf("Snapchat", "WhatsApp", "Google"), "Snapchat"),
                GuessRecord(now - 25 * min - 20_000, "WhatsApp", listOf("Snapchat", "Telegram", "Google"), "Superset"),
                GuessRecord(now - 55 * min, "Telegram", listOf("Snapchat", "WhatsApp", "Gmail"), "Snapchat"),
                GuessRecord(now - 58 * min, "Snapchat", listOf("Telegram", "WhatsApp", "Instagram"), "Telegram"),
            )

        val bundle =
            InsightsBundle(
                computedAt = now,
                todayByCategory = SessionGrouper.byCategory(todaySessions),
                sleepNights = nights,
                sleepSummary = SleepDetector.summarize(nights, zone),
                pickupsToday = PickupAnalyzer.analyze(unlocks, notifications, todaySessions, todayStart, now) { pkg -> usage.first { it.packageName == pkg }.appName },
                contextInsights = ContextInsights.compute(sessions.filter { it.start >= weekStart }, samples.filter { it.timestamp >= weekStart }, zone),
                heatmap = Heatmap.build(sessions, today, zone),
                typicalDay = TypicalDay.build(sessions, today, now, zone),
                dayTypes = DayTypeClusterer.cluster(sessions, today, zone),
                week = WeekComparer.compare(sessions, today, zone),
                predictability = PredictabilityEvaluator.evaluate(sessions, now, zone),
                patternGroups = PatternGrouper.group(habits.map { PatternRow(it.patternDescription, it.timeSlot, it.dayType, it.occurrenceCount, it.confidence, it.lastSeenAt) }),
                placeUsage = emptyList(),
                placeNames = emptyMap(),
                deviations = DeviationReport(deviations, shift),
                naps = listOf(nap),
                recentGuesses = guesses,
            )

        val typical =
            TypicalUsageCalculator.compute(
                usage.map { TypicalUsageCalculator.Interval(it.startTime, it.endTime, it.durationMs) },
                now,
            )

        val byApp = todayUsage.groupBy { it.appName }.mapValues { e -> e.value.sumOf { it.durationMs } }
        val todaySnapshots = snapshots.filter { it.timestamp >= todayStart }
        val state =
            HabitUiState(
                isLoading = false,
                daysOfData = 14,
                todayScreenTimeMs = todayUsage.sumOf { it.durationMs },
                todayUnlocks = todayUnlocks.size,
                todayUsageByApp = byApp.toImmutableMap(),
                todayAppUsage = todayUsage.sortedByDescending { it.startTime }.toImmutableList(),
                todaySnapshots = todaySnapshots.toImmutableList(),
                discoveredHabits = habits.toImmutableList(),
                predictions =
                    listOf(
                        AppGuess("Snapchat", 0.25f),
                        AppGuess("WhatsApp", 0.21f),
                        AppGuess("Google", 0.10f),
                    ).toImmutableList(),
                predictionsAfter = "Telegram",
                hasEnoughData = true,
                baselineStatus = "Model up to date · 14 days",
                latestContext = todaySnapshots.lastOrNull(),
                latestSensorContext = todaySnapshots.lastOrNull { it.lightLux >= 0f },
                hasUsagePermission = true,
                hasNotificationPermission = true,
                hasRuntimePermissions = true,
                usageRecordCount = usage.size,
                liveUsageRecordCount = usage.size / 2,
                historicalUsageRecordCount = usage.size / 2,
                contextRecordCount = snapshots.size,
                lastUsageUpdate = now - 4 * 60_000L,
                isMonitoringServiceActive = true,
                selectedHistoryDate = todayStart,
                historicalAppUsage = todayUsage.sortedBy { it.startTime }.toImmutableList(),
                historicalSnapshots = todaySnapshots.toImmutableList(),
                typicalUsage = typical,
                insights = bundle,
                checkInCount = 6,
                labelCount = 9,
                features = FeatureSettings(),
                sensingModeName = "NORMAL",
                sensingMsToday = 93_000L,
                stepsToday = 4_321L,
            )
        return Data(state, now)
    }
}
