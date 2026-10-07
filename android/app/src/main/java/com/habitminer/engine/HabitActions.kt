package com.habitminer.engine

import com.habitminer.analytics.CheckInOption

/**
 * What the Today, History and Insights screens can ask for. [HabitViewModel] implements it;
 * screenshot tests pass a no-op version so screens render without the real data layer.
 */
interface HabitActions {
    fun checkPermissions()

    fun loadHistoricalData()

    fun answerCheckIn(option: CheckInOption)

    fun dismissCheckIn()

    /** [key] is a [com.habitminer.analytics.DayDeviation.key]; value EXPECTED or UNUSUAL. */
    fun giveDeviationFeedback(
        key: String,
        value: String,
    )

    /** Answer to "were you asleep?" for a [com.habitminer.analytics.NapCandidate.key]. */
    fun answerNap(
        key: String,
        asleep: Boolean,
    )

    /** What a routine change was (a [com.habitminer.analytics.PeriodOption] key). */
    fun labelPeriod(
        key: String,
        from: java.time.LocalDate,
        value: String,
    )

    fun selectHistoryDate(timeInMillis: Long)

    /** Your own times for the night that ended on [wakeDate]. */
    fun fixNight(
        wakeDate: java.time.LocalDate,
        start: Long,
        end: Long,
    ) = Unit

    /** "That wasn't sleep": drops the night that ended on [wakeDate]. */
    fun markNotSleep(wakeDate: java.time.LocalDate) = Unit

    /** Goes back to the estimate for the night that ended on [wakeDate]. */
    fun clearNightFix(wakeDate: java.time.LocalDate) = Unit

    /** How you feel after a check-in, 1 (low) to 5 (great); nulls skip. */
    fun answerMood(
        mood: Int?,
        energy: Int?,
    ) = Unit

    /** "useful", "fewer" or null (undo) for today's insight. */
    fun insightFeedback(
        key: String,
        value: String?,
    ) = Unit

    fun dismissInsightExplainer() = Unit

    /** "Not now" on the notification permission card. */
    fun dismissNotificationAsk() = Unit

    fun dismissWelcomeBack() = Unit
}
