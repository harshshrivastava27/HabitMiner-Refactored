package com.habitminer.proactive

import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.habitminer.analytics.AppCategory
import com.habitminer.analytics.DeviationReport
import com.habitminer.analytics.DeviationSummary
import com.habitminer.analytics.DigestBuilder
import com.habitminer.analytics.Format
import com.habitminer.analytics.InsightDelivery
import com.habitminer.analytics.InsightFrequency
import com.habitminer.analytics.LikelyActivity
import com.habitminer.analytics.Moment
import com.habitminer.analytics.PickupAnalyzer
import com.habitminer.analytics.PromptKind
import com.habitminer.analytics.PromptPolicy
import com.habitminer.analytics.Receptivity
import com.habitminer.analytics.ReminderPolicy
import com.habitminer.analytics.SleepDetector
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.TimedEvent
import com.habitminer.analytics.UsageSession
import com.habitminer.analytics.WeekComparer
import com.habitminer.collection.DeviceEventReceiver
import com.habitminer.collection.DeviceEvents
import com.habitminer.data.PrefsKeys
import com.habitminer.data.UserLabelEntity
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.goals.GoalsStore
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.FeedbackRepository
import com.habitminer.repository.InsightRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides, at each pickup and reading, whether anything is worth a notification: a reminder
 * you set, a quick question, the insight of the day, the evening summary, a nudge or the
 * weekly story. The rules live in pure Kotlin ([PromptPolicy], [Receptivity],
 * [InsightDelivery], [ReminderPolicy]); this class gathers the inputs and posts.
 *
 * Everything shares one limit of three a day (the Sunday story aside), waits for a receptive
 * moment, and stays quiet in your quiet hours, on Do Not Disturb, in meetings, on the move and
 * on low battery.
 */
@Singleton
class ProactiveEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val feedbackRepository: FeedbackRepository,
        private val usageIngestor: com.habitminer.collection.UsageIngestor,
        private val appIdentityResolver: AppIdentityResolver,
        private val analysisRepository: com.habitminer.engine.AnalysisRepository,
        private val insightRepository: InsightRepository,
        private val goalsStore: GoalsStore,
        private val calendarBusy: com.habitminer.sources.CalendarBusy,
        private val readingRunner: com.habitminer.collection.ReadingRunner,
    ) {
        private val mutex = Mutex()

        /** [afterUnlock] when called right after the phone was unlocked. */
        suspend fun tick(afterUnlock: Boolean = false) {
            if (!mutex.tryLock()) return
            try {
                runTick(afterUnlock)
            } catch (e: Exception) {
                Log.w(TAG, "Proactive tick failed", e)
            } finally {
                mutex.unlock()
            }
        }

        private suspend fun runTick(afterUnlock: Boolean) {
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PrefsKeys.COLLECTION_ENABLED, true)) return
            if (!Notifier.canPost(context)) return

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val sent = feedbackRepository.sentPrompts()
            val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val settings = insightRepository.settings.value
            val quiet = TimeUtil.minuteOfDay(now, zone) in settings.quiet

            if (screenOn) usageIngestor.ingest(minIntervalMs = 60_000L)
            val recent =
                if (screenOn) {
                    contextRepository.getUsageSince(now - 3 * TimeUtil.HOUR)
                        .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                        .map(AnalyticsMappers::session)
                } else {
                    emptyList()
                }

            // 0. Reminders you asked for: they only fire while you're in the app concerned.
            if (screenOn && goalReminder(recent, sent, now, zone)) return

            val moment = moment(now, zone, screenOn, afterUnlock, quiet, recent)
            val questionsOn = prefs.getBoolean(PrefsKeys.CHECKINS_ENABLED, true)
            val alertsOn = prefs.getBoolean(PrefsKeys.DEVIATION_ALERTS_ENABLED, true)
            val hour = TimeUtil.hourOf(now, zone)
            val canAsk = Receptivity.hold(moment) == null

            val wantsAnalysis = (screenOn && (questionsOn || settings.frequency != InsightFrequency.OFF)) || (alertsOn && hour in 21 until 23)
            if (!quiet && wantsAnalysis) {
                val analysis = analysis() ?: return
                val answered = analysis.answers.keys

                // 1. "Were you asleep?" right after a likely nap.
                val nap = analysis.naps.lastOrNull { it.key !in answered }
                if (canAsk && PromptPolicy.canAskNap(questionsOn, screenOn, nap, answered, sent, now, zone)) {
                    Notifier.postNapQuestion(context, nap!!, zone)
                    feedbackRepository.recordPromptSent(PromptKind.NAP, now)
                    return
                }
                // 2. "Your routine has changed — what's going on?"
                val shift = analysis.deviations.shift
                if (canAsk && PromptPolicy.canAskPeriod(questionsOn, screenOn, shift, answered, sent, now, zone)) {
                    Notifier.postPeriodQuestion(context, shift!!)
                    feedbackRepository.recordPromptSent(PromptKind.PERIOD, now)
                    return
                }
                // 3. The insight of the day, at a receptive moment.
                val insight = analysis.insight
                if (insight != null &&
                    Notifier.channelEnabled(context, Notifier.CHANNEL_INSIGHTS) &&
                    InsightDelivery.canNotify(
                        settings.frequency, insight.effect, insight.notifiedAt != null,
                        settings.windowStartHour, settings.windowEndHour, moment, sent, zone,
                    )
                ) {
                    Notifier.postInsight(context, insight)
                    insightRepository.markNotified(insight.id)
                    analysisRepository.invalidate()
                    feedbackRepository.recordPromptSent(PromptKind.INSIGHT, now)
                    return
                }
                // 4. Evening summary of today's notable differences, with an unsent insight folded in.
                val notable = todaysNotable(analysis.deviations, now, zone)
                if (PromptPolicy.canSendDeviationSummary(alertsOn, notable, sent, now, zone)) {
                    val fold =
                        insight?.takeIf {
                            it.notifiedAt == null && settings.frequency != InsightFrequency.OFF && settings.frequency != InsightFrequency.WEEKLY
                        }
                    DeviationSummary.build(notable)?.let { Notifier.postDeviationSummary(context, it, fold) }
                    feedbackRepository.recordPromptSent(PromptKind.DEVIATIONS, now)
                    return
                }
            }

            if (!quiet && canAsk && PromptPolicy.canSendCheckIn(questionsOn, screenOn, sent, now, zone)) {
                Notifier.postCheckIn(context, now, likelyActivities(now, zone))
                feedbackRepository.recordPromptSent(PromptKind.CHECK_IN, now)
                return
            }

            // Late-night nudges still come in quiet hours: they're about using the phone right then.
            if (prefs.getBoolean(PrefsKeys.NUDGES_ENABLED, true) && screenOn) {
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

            if (!quiet && PromptPolicy.shouldSendDigest(prefs.getBoolean(PrefsKeys.DIGEST_ENABLED, true), feedbackRepository.lastDigestAt(), now, zone)) {
                Notifier.postDigest(context, buildDigest(now, zone))
                feedbackRepository.recordPromptSent(PromptKind.DIGEST, now)
            }
        }

        /** What's going on right now, for [Receptivity]. */
        private suspend fun moment(
            now: Long,
            zone: ZoneId,
            screenOn: Boolean,
            afterUnlock: Boolean,
            quiet: Boolean,
            recent: List<UsageSession>,
        ): Moment {
            val unlocks = contextRepository.unlockTimesSince(now - 12 * TimeUtil.HOUR)
            // Idle before this pickup: from the last use before the latest unlock.
            val idle =
                if (afterUnlock) {
                    unlocks.lastOrNull()?.let { u ->
                        val lastUse = maxOf(recent.filter { it.end <= u - 1_000 }.maxOfOrNull { it.end } ?: 0L, unlocks.lastOrNull { it < u - 1_000 } ?: 0L)
                        if (lastUse > 0) u - lastUse else 12 * TimeUtil.HOUR
                    }
                } else {
                    null
                }
            // Just left a chat: the last app was a messaging app, closed in the last couple of minutes.
            val foreground = usageIngestor.foreground
            val inChat = foreground != null && recent.any { it.packageName == foreground && it.category == AppCategory.MESSAGING }
            val leftChat =
                if (inChat) {
                    null
                } else {
                    recent.filter { it.packageName != foreground }.maxByOrNull { it.end }
                        ?.takeIf { it.category == AppCategory.MESSAGING }
                        ?.let { now - it.end }
                }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dnd = nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            val activity =
                contextRepository.getDeviceEventsOfTypesSince(listOf(DeviceEvents.ACTIVITY), now - 3 * TimeUtil.HOUR).lastOrNull()
                    ?.let { DeviceEvents.detailValue(it.detail, "type") }
            val battery = readingRunner.batteryState()
            return Moment(
                now = now,
                screenOn = screenOn,
                idleBeforeMs = idle,
                leftMessagingMs = leftChat,
                doNotDisturb = dnd,
                busy = runCatching { calendarBusy.isBusyNow(now) }.getOrDefault(false),
                moving = activity in setOf("vehicle", "running", "cycling"),
                batteryLow = battery.level in 0 until 15 && !battery.charging,
                quiet = quiet,
            )
        }

        /**
         * Reminders for apps you want to use less (after N minutes straight, then every N more,
         * snoozable) and for the daily target (once a day). If you're in such an app but not at
         * N yet, a check is scheduled for when you would be.
         */
        private suspend fun goalReminder(
            recent: List<UsageSession>,
            sent: List<com.habitminer.analytics.SentPrompt>,
            now: Long,
            zone: ZoneId,
        ): Boolean {
            val goals = goalsStore.goals.value
            val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val today = TimeUtil.dateOf(now, zone).toString()
            val budgetLeft = PromptPolicy.promptsToday(sent, now, zone).size < PromptPolicy.MAX_PROMPTS_PER_DAY
            val foreground = usageIngestor.foreground

            if (goals.reminderMinutes > 0 && foreground != null && foreground in goals.useLess) {
                val stretch = ReminderPolicy.currentStretch(recent, foreground, now)
                val due = ReminderPolicy.dueCount(stretch, goals.reminderMinutes)
                val doneKey = PrefsKeys.GOAL_REMINDED_PREFIX + foreground
                val stretchId = "$today@${ReminderPolicy.stretchStart(recent, foreground, now)}"
                val done = prefs.getString(doneKey, null)?.split('|')?.takeIf { it.firstOrNull() == stretchId }?.getOrNull(1)?.toIntOrNull() ?: 0
                if (due > done && now >= GoalSnooze.snoozedUntil(context, foreground) && budgetLeft) {
                    val app = appIdentityResolver.getAppName(foreground)
                    Notifier.postGoalReminder(
                        context,
                        title = "${Format.duration(stretch)} on $app",
                        body = "You asked for a reminder after ${goals.reminderMinutes} minutes in a row. Carry on if you meant to.",
                        packageName = foreground,
                        snoozeKey = foreground,
                    )
                    prefs.edit().putString(doneKey, "$stretchId|$due").apply()
                    feedbackRepository.recordPromptSent(PromptKind.REMINDER, now)
                    return true
                }
                // Check again when the next reminder would be due.
                GoalCheck.schedule(context, now + ReminderPolicy.untilNext(stretch, goals.reminderMinutes) + 30_000L)
            }

            val target = goals.dailyTargetMinutes
            if (target != null && prefs.getString(PrefsKeys.GOAL_TARGET_REMINDED, null) != today && budgetLeft &&
                now >= GoalSnooze.snoozedUntil(context, "daily")
            ) {
                val startOfDay = TimeUtil.startOfDay(TimeUtil.dateOf(now, zone), zone)
                val todayMs = recent.filter { it.start >= startOfDay }.sumOf { it.durationMs }.let { r ->
                    if (now - startOfDay > 3 * TimeUtil.HOUR) {
                        contextRepository.getUsageSince(startOfDay).filterNot { appIdentityResolver.isLauncher(it.packageName) }.sumOf { it.durationMs }
                    } else {
                        r
                    }
                }
                if (todayMs >= target * TimeUtil.MINUTE) {
                    Notifier.postGoalReminder(
                        context,
                        title = "${Format.duration(target * TimeUtil.MINUTE)} on your phone today",
                        body = "That's the daily target you set. No pressure: it's a nudge, nothing is blocked.",
                        packageName = null,
                        snoozeKey = "daily",
                    )
                    prefs.edit().putString(PrefsKeys.GOAL_TARGET_REMINDED, today).apply()
                    feedbackRepository.recordPromptSent(PromptKind.REMINDER, now)
                    return true
                }
            }
            return false
        }

        /** Your two most usual answers around this hour, for the check-in buttons. */
        private suspend fun likelyActivities(
            now: Long,
            zone: ZoneId,
        ) = LikelyActivity.top2(
            feedbackRepository.getCheckIns().first().filter { it.kind == UserLabelEntity.KIND_CHECK_IN }.map { it.timestamp to it.value },
            now,
            zone,
        )

        private fun todaysNotable(
            report: DeviationReport,
            now: Long,
            zone: ZoneId,
        ) = report.days.filter { it.date == TimeUtil.dateOf(now, zone) && it.notable }

        /** The shared analysis; the background accepts it up to 10 minutes old. */
        private suspend fun analysis(): com.habitminer.engine.InsightsBundle? = analysisRepository.get(maxAgeMs = 10 * TimeUtil.MINUTE)

        /** Answers change what should be asked next, so forget the cached analysis. */
        fun invalidate() {
            analysisRepository.invalidate()
        }

        private suspend fun buildDigest(
            now: Long,
            zone: ZoneId,
        ): DigestBuilder.Digest {
            val since = now - 16 * TimeUtil.DAY
            val sessions =
                contextRepository.getUsageSince(since)
                    .filterNot { appIdentityResolver.isLauncher(it.packageName) }
                    .map(AnalyticsMappers::session)
            val samples = contextRepository.getSnapshotsSince(now - 8 * TimeUtil.DAY).map(AnalyticsMappers::sample)
            val unlocks = contextRepository.unlockTimesSince(now - 8 * TimeUtil.DAY)
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
