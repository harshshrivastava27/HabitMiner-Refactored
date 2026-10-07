package com.habitminer.engine

import androidx.compose.runtime.Immutable
import com.habitminer.analytics.AppCategory
import com.habitminer.analytics.ContextInsight
import com.habitminer.analytics.ContextInsights
import com.habitminer.analytics.ContextSample
import com.habitminer.analytics.DayTypeClusterer
import com.habitminer.analytics.DailySleep
import com.habitminer.analytics.DayTypes
import com.habitminer.analytics.DeviationReport
import com.habitminer.analytics.GuessRecord
import com.habitminer.analytics.LabelledPeriod
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.NextAppModel
import com.habitminer.analytics.Heatmap
import com.habitminer.analytics.HeatmapData
import com.habitminer.analytics.PatternGroup
import com.habitminer.analytics.PatternGrouper
import com.habitminer.analytics.PatternRow
import com.habitminer.analytics.PickupAnalyzer
import com.habitminer.analytics.PickupStats
import com.habitminer.analytics.PlaceInference
import com.habitminer.analytics.PlaceUsage
import com.habitminer.analytics.PredictabilityResult
import com.habitminer.analytics.SessionGrouper
import com.habitminer.analytics.SleepDays
import com.habitminer.analytics.SleepDetector
import com.habitminer.analytics.SleepWeek
import com.habitminer.analytics.SleepEstimate
import com.habitminer.analytics.SleepSummary
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.TimedEvent
import com.habitminer.analytics.TypicalDay
import com.habitminer.analytics.TypicalDayCurve
import com.habitminer.analytics.UsageSession
import com.habitminer.analytics.WeekComparer
import com.habitminer.analytics.WeekComparison
import com.habitminer.collection.DeviceEventReceiver
import com.habitminer.data.AppUsageEntity
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.data.DiscoveredHabitEntity
import com.habitminer.data.PlaceEntity
import com.habitminer.data.UserLabelEntity
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.repository.ContextRepository
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Today, History and Insights screens show beyond raw rows. */
@Immutable
data class InsightsBundle(
    val computedAt: Long,
    val todayByCategory: List<Pair<AppCategory, Long>>,
    val sleepNights: List<SleepEstimate>,
    val sleepSummary: SleepSummary?,
    val pickupsToday: PickupStats?,
    val contextInsights: List<ContextInsight>,
    val heatmap: HeatmapData?,
    val typicalDay: TypicalDayCurve?,
    val dayTypes: DayTypes?,
    val week: WeekComparison?,
    val predictability: PredictabilityResult?,
    val patternGroups: List<PatternGroup>,
    val placeUsage: List<PlaceUsage>,
    /** Display name for each place hash: the user's label or a suggestion. */
    val placeNames: Map<String, String>,
    // ---- v1.3 ----
    /** Differences from your usual days (last week + today so far) and any multi-day change. */
    val deviations: DeviationReport = DeviationReport(emptyList(), null),
    /** Likely naps yesterday and today, oldest first. */
    val naps: List<NapCandidate> = emptyList(),
    /** Naps you confirmed, (start, end). */
    val confirmedNaps: List<Pair<Long, Long>> = emptyList(),
    /** Nap and routine-change answers by key. */
    val answers: Map<String, String> = emptyMap(),
    /** Periods you labelled (exams…), left out of "usual". */
    val periods: List<LabelledPeriod> = emptyList(),
    /** What the next-app model guessed before your latest app switches, newest first. */
    val recentGuesses: List<GuessRecord> = emptyList(),
    /** Night + confirmed naps for each of the last 7 days (by the day you woke up), oldest first. */
    val sleepDays: List<DailySleep> = emptyList(),
    val sleepWeek: SleepWeek? = null,
) {
    /** Today's sleep: the night that ended this morning plus today's confirmed naps. */
    val sleepToday: DailySleep? get() = sleepDays.lastOrNull()?.takeIf { it.date == java.time.Instant.ofEpochMilli(computedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    /** The night that ended this morning, if detected. */
    val lastNight: SleepEstimate? get() = sleepNights.lastOrNull()
}

@Singleton
class InsightsComputer
    @Inject
    constructor(
        private val appIdentityResolver: AppIdentityResolver,
        private val contextRepository: ContextRepository,
        private val routineAnalysis: RoutineAnalysis,
    ) {
        suspend fun compute(
            allUsage: List<AppUsageEntity>,
            snapshots: List<ContextSnapshotEntity>,
            habits: List<DiscoveredHabitEntity>,
            places: List<PlaceEntity>,
            labels: List<UserLabelEntity> = emptyList(),
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): InsightsBundle {
            val today = TimeUtil.dateOf(now, zone)
            val todayStart = TimeUtil.startOfDay(today, zone)
            val horizon = now - LOOKBACK_DAYS * TimeUtil.DAY

            val sessions: List<UsageSession> =
                allUsage.asSequence()
                    .filter { it.startTime >= horizon && it.startTime <= now }
                    .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                    .map(AnalyticsMappers::session)
                    .toList()
            val samples: List<ContextSample> =
                snapshots.asSequence().filter { it.timestamp >= horizon }.map(AnalyticsMappers::sample).toList()

            val eventHorizon = now - 8 * TimeUtil.DAY
            val unlocks = contextRepository.unlockTimesSince(eventHorizon)
            val notifications =
                contextRepository.getDeviceEventsSince(DeviceEventReceiver.EVENT_NOTIFICATION, todayStart)
                    .map { TimedEvent(it.timestamp, it.packageName) }

            // Same rule as the headline screen-time number: sessions that started today.
            val todaySessions = sessions.filter { it.start >= todayStart }
            val weekStart = TimeUtil.startOfDay(today.minusDays(6), zone)
            val weekSessions = sessions.filter { it.start >= weekStart }
            val weekSamples = samples.filter { it.timestamp >= weekStart }

            val routine = routineAnalysis.run(sessions, samples, unlocks, labels, places, now, zone)
            val nights = routine.nights.takeLast(7)
            val excluded =
                routine.periods.flatMap { p -> generateSequence(p.from) { it.plusDays(1) }.takeWhile { !it.isAfter(p.to) }.toList() }.toSet()
            val guesses = NextAppModel.evaluate(sessions, now, zone)
            val sleepDays = SleepDays.build(routine.nights, routine.confirmedNaps, today, 7, zone)

            val pickups =
                if (unlocks.isEmpty()) {
                    null
                } else {
                    PickupAnalyzer.analyze(unlocks, notifications, todaySessions, todayStart, now) { appIdentityResolver.getAppName(it) }
                }

            val suggested = PlaceInference.suggestNames(samples, zone)
            val placeNames = suggested.toMutableMap()
            places.forEach { p -> p.label?.let { placeNames[p.placeHash] = it } }

            val patternRows =
                habits.map {
                    PatternRow(it.patternDescription, it.timeSlot, it.dayType, it.occurrenceCount, it.confidence, it.lastSeenAt)
                }

            return InsightsBundle(
                computedAt = now,
                todayByCategory = SessionGrouper.byCategory(todaySessions),
                sleepNights = nights,
                sleepSummary = SleepDetector.summarize(nights, zone),
                pickupsToday = pickups,
                contextInsights = ContextInsights.compute(weekSessions, weekSamples, zone),
                heatmap = Heatmap.build(sessions, today, zone),
                typicalDay = TypicalDay.build(sessions, today, now, zone, excluded),
                dayTypes = DayTypeClusterer.cluster(sessions, today, zone),
                week = WeekComparer.compare(sessions, today, zone),
                predictability = guesses.result,
                patternGroups = PatternGrouper.group(patternRows),
                placeUsage = ContextInsights.byPlace(weekSessions, weekSamples, zone) { placeNames[it] ?: "Unnamed place" },
                placeNames = placeNames,
                deviations = routine.deviations,
                naps = routine.naps,
                confirmedNaps = routine.confirmedNaps,
                answers = routine.answers,
                periods = routine.periods,
                recentGuesses = guesses.recent,
                sleepDays = sleepDays,
                sleepWeek = SleepDays.summarize(sleepDays, zone),
            )
        }

        companion object {
            const val LOOKBACK_DAYS = 35L
        }
    }
