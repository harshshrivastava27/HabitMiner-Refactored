package com.habitminer.engine

import com.habitminer.analytics.ContextSample
import com.habitminer.analytics.DeviationFinder
import com.habitminer.analytics.DeviationReport
import com.habitminer.analytics.LabelledPeriod
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.NapDetector
import com.habitminer.analytics.NightSignals
import com.habitminer.analytics.PlaceInference
import com.habitminer.analytics.SleepCorrections
import com.habitminer.analytics.SleepDetector
import com.habitminer.analytics.SleepEstimate
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.UsageSession
import com.habitminer.data.PlaceEntity
import com.habitminer.data.UserLabelEntity
import com.habitminer.repository.FeedbackRepository
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sleep, naps and deviations from the same inputs, shared by the Insights screen and the
 * background prompts so both see the same thing.
 */
@Singleton
class RoutineAnalysis
    @Inject
    constructor(
        private val feedbackRepository: FeedbackRepository,
    ) {
        data class Result(
            /** Up to 30 nights, oldest first. */
            val nights: List<SleepEstimate>,
            val deviations: DeviationReport,
            /** Likely naps yesterday and today, oldest first. */
            val naps: List<NapCandidate>,
            val periods: List<LabelledPeriod>,
            /** Nap and period answers by key. */
            val answers: Map<String, String>,
            /** Naps you confirmed, (start, end). */
            val confirmedNaps: List<Pair<Long, Long>>,
            /** How your corrected nights shift the other estimates, if they do. */
            val sleepShift: SleepCorrections.Shift? = null,
        )

        suspend fun run(
            sessions: List<UsageSession>,
            samples: List<ContextSample>,
            unlocks: List<Long>,
            labels: List<UserLabelEntity>,
            places: List<PlaceEntity>,
            now: Long,
            zone: ZoneId,
            signals: NightSignals = NightSignals(),
        ): Result {
            val today = TimeUtil.dateOf(now, zone)
            val periods = LabelMappers.periods(labels)
            val detected = SleepDetector.detectRange(sessions, unlocks, samples, today, 30, now, zone, signals)
            // Nights you corrected use your times and teach the estimate for the others.
            val fixes = LabelMappers.sleepFixes(labels)
            val nights = SleepCorrections.apply(detected, fixes, sessions, unlocks, samples, zone, signals)
            val report = DeviationFinder.find(sessions, unlocks, nights, today, now, zone, periods)

            // A labelled period keeps growing while the change it explains continues.
            report.shift?.takeIf { it.label != null }?.let { shift ->
                val label =
                    labels.firstOrNull { l ->
                        l.kind == UserLabelEntity.KIND_PERIOD &&
                            LabelMappers.periodFrom(l)?.let { kotlin.math.abs(ChronoUnit.DAYS.between(it, shift.since)) <= 2 } == true
                    }
                val period = label?.let { l -> periods.firstOrNull { it.from == LabelMappers.periodFrom(l) } }
                if (label?.refKey != null && period != null && period.to.isBefore(today)) {
                    feedbackRepository.extendPeriod(label.refKey, period.from, today)
                }
            }

            val homePlace =
                places.firstOrNull { it.label.equals("Home", ignoreCase = true) }?.placeHash
                    ?: PlaceInference.suggestNames(samples, zone).entries.firstOrNull { it.value == "Home" }?.key
            val naps =
                (1 downTo 0).flatMap { back ->
                    val day = today.minusDays(back.toLong())
                    NapDetector.candidates(sessions, unlocks, samples, day, now, zone, nights.firstOrNull { it.wakeDate == day }, homePlace)
                }
            return Result(
                nights = nights,
                deviations = report,
                naps = naps,
                periods = periods,
                answers = LabelMappers.answers(labels),
                confirmedNaps = LabelMappers.confirmedNaps(labels),
                sleepShift = SleepCorrections.shift(detected, fixes),
            )
        }
    }
