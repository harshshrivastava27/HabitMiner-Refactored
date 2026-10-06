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
}
