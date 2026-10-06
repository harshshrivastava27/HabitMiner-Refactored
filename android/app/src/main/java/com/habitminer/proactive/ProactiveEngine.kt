package com.habitminer.proactive

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.habitminer.analytics.DeviationReport
import com.habitminer.analytics.DeviationSummary
import com.habitminer.analytics.DigestBuilder
import com.habitminer.analytics.PickupAnalyzer
import com.habitminer.analytics.PromptKind
import com.habitminer.analytics.PromptPolicy
import com.habitminer.analytics.SleepDetector
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.TimedEvent
import com.habitminer.analytics.WeekComparer
import com.habitminer.collection.DeviceEventReceiver
import com.habitminer.collection.UsageDataCollector
import com.habitminer.data.PrefsKeys
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.engine.RoutineAnalysis
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.FeedbackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides, every few minutes, whether to send a check-in, a nudge or the weekly digest.
 * The rules live in [PromptPolicy] (pure Kotlin, unit tested); this class only gathers
 * the inputs and posts the notification.
 */
@Singleton
class ProactiveEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val feedbackRepository: FeedbackRepository,
        private val usageDataCollector: UsageDataCollector,
        private val appIdentityResolver: AppIdentityResolver,
        private val routineAnalysis: RoutineAnalysis,
    ) {
        private val mutex = Mutex()

        /** The full analysis reads ~5 weeks of data, so it is reused for up to 10 minutes. */
        private var cached: Pair<Long, RoutineAnalysis.Result>? = null

        suspend fun tick() {
            if (!mutex.tryLock()) return
            try {
                runTick()
            } catch (e: Exception) {
                Log.w(TAG, "Proactive tick failed", e)
            } finally {
                mutex.unlock()
            }
        }

        private suspend fun runTick() {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PrefsKeys.COLLECTION_ENABLED, true)) return
            if (!Notifier.canPost(context)) return

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val sent = feedbackRepository.sentPrompts()
            val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

            val questionsOn = prefs.getBoolean(PrefsKeys.CHECKINS_ENABLED, true)
            val alertsOn = prefs.getBoolean(PrefsKeys.DEVIATION_ALERTS_ENABLED, true)
            val hour = TimeUtil.hourOf(now, zone)
            if ((questionsOn && screenOn) || (alertsOn && hour in 21 until 23)) {
                val analysis = analysis(now, zone)
                val answered = analysis.answers.keys

                // 1. "Were you asleep?" right after a likely nap.
                val nap = analysis.naps.lastOrNull { it.key !in answered }
                if (PromptPolicy.canAskNap(questionsOn, screenOn, nap, answered, sent, now, zone)) {
                    Notifier.postNapQuestion(context, nap!!, zone)
                    feedbackRepository.recordPromptSent(PromptKind.NAP, now)
                    return
                }
                // 2. "Your routine has changed — what's going on?"
                val shift = analysis.deviations.shift
                if (PromptPolicy.canAskPeriod(questionsOn, screenOn, shift, answered, sent, now, zone)) {
                    Notifier.postPeriodQuestion(context, shift!!)
                    feedbackRepository.recordPromptSent(PromptKind.PERIOD, now)
                    return
                }
                // 3. Evening summary of today's notable differences.
                val notable = todaysNotable(analysis.deviations, now, zone)
                if (PromptPolicy.canSendDeviationSummary(alertsOn, notable, sent, now, zone)) {
                    DeviationSummary.build(notable)?.let { Notifier.postDeviationSummary(context, it) }
                    feedbackRepository.recordPromptSent(PromptKind.DEVIATIONS, now)
                    return
                }
            }

            if (PromptPolicy.canSendCheckIn(questionsOn, screenOn, sent, now, zone)) {
                Notifier.postCheckIn(context, now)
                feedbackRepository.recordPromptSent(PromptKind.CHECK_IN, now)
                return
            }

            if (prefs.getBoolean(PrefsKeys.NUDGES_ENABLED, true) && screenOn) {
                val recent =
                    usageDataCollector.collectUsageSince(now - 3 * TimeUtil.HOUR, overlapMs = 30 * TimeUtil.MINUTE)
                        .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                        .map(AnalyticsMappers::session)
                val latest =
                    contextRepository.getLatestSnapshotWithSensors().first()
                        ?.takeIf { now - it.timestamp < 30 * TimeUtil.MINUTE }
                        ?.let(AnalyticsMappers::sample)
                val nudge = PromptPolicy.nudgeFor(true, recent, latest, sent, now, zone)
                if (nudge != null) {
                    Notifier.postNudge(context, nudge)
                    feedbackRepository.recordPromptSent(PromptKind.NUDGE, now)
                    return
                }
            }

            if (PromptPolicy.shouldSendDigest(prefs.getBoolean(PrefsKeys.DIGEST_ENABLED, true), feedbackRepository.lastDigestAt(), now, zone)) {
                Notifier.postDigest(context, buildDigest(now, zone))
                feedbackRepository.recordPromptSent(PromptKind.DIGEST, now)
            }
        }

        private fun todaysNotable(
            report: DeviationReport,
            now: Long,
            zone: ZoneId,
        ) = report.days.filter { it.date == TimeUtil.dateOf(now, zone) && it.notable }

        private suspend fun analysis(
            now: Long,
            zone: ZoneId,
        ): RoutineAnalysis.Result {
            cached?.let { (at, result) -> if (now - at < 10 * TimeUtil.MINUTE) return result }
            val since = now - 35 * TimeUtil.DAY
            val sessions =
                contextRepository.getAllUsage().first()
                    .filter { it.startTime >= since }
                    .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                    .map(AnalyticsMappers::session)
            val samples = contextRepository.getSnapshotsSince(since).map(AnalyticsMappers::sample)
            val unlocks =
                runCatching { usageDataCollector.getUnlockTimesSince(now - 8 * TimeUtil.DAY) }.getOrNull()
                    ?: contextRepository.getDeviceEventsSince(DeviceEventReceiver.EVENT_UNLOCK, now - 8 * TimeUtil.DAY).map { it.timestamp }
            val result =
                routineAnalysis.run(
                    sessions, samples, unlocks, feedbackRepository.napAndPeriodLabels(), feedbackRepository.getPlaces().first(), now, zone,
                )
            cached = now to result
            return result
        }

        /** Answers change what should be asked next, so forget the cached analysis. */
        fun invalidate() {
            cached = null
        }

        private suspend fun buildDigest(
            now: Long,
            zone: ZoneId,
        ): DigestBuilder.Digest {
            val since = now - 16 * TimeUtil.DAY
            val sessions =
                contextRepository.getAllUsage().first()
                    .filter { it.startTime >= since }
                    .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                    .map(AnalyticsMappers::session)
            val samples = contextRepository.getSnapshotsSince(now - 8 * TimeUtil.DAY).map(AnalyticsMappers::sample)
            val unlocks =
                usageDataCollector.getUnlockTimesSince(now - 8 * TimeUtil.DAY)
                    ?: contextRepository.getDeviceEventsSince(DeviceEventReceiver.EVENT_UNLOCK, now - 8 * TimeUtil.DAY).map { it.timestamp }
            val notifications =
                contextRepository.getDeviceEventsSince(DeviceEventReceiver.EVENT_NOTIFICATION, now - 8 * TimeUtil.DAY)
                    .map { TimedEvent(it.timestamp, it.packageName) }

            val today = TimeUtil.dateOf(now, zone)
            val weekStart = TimeUtil.startOfDay(today.minusDays(6), zone)
            val thisWeek = sessions.filter { it.start >= weekStart }
            val sleep = SleepDetector.summarize(SleepDetector.detectRange(sessions, unlocks, samples, today, 7, now, zone), zone)
            val pickups = PickupAnalyzer.analyze(unlocks, notifications, sessions, weekStart, now) { appIdentityResolver.getAppName(it) }
            val week = WeekComparer.compare(sessions, today, zone)
            return DigestBuilder.build(week, thisWeek, 7, sleep, pickups)
        }

        companion object {
            private const val TAG = "ProactiveEngine"
            const val TICK_INTERVAL_MS = 5 * 60 * 1000L
        }
    }
