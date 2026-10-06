package com.habitminer.engine

import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Computes "what is typical by this time of day" directly from usage history.
 *
 * This deliberately does NOT depend on the per-time-bin baselines: those need several
 * days of the same day type (WEEKDAY/WEEKEND) and the OS only exposes ~7 days of
 * history, so on weekends the Home screen used to sit on "Building..." forever.
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
object TypicalUsageCalculator {
    /** A single usage session. [durationMs] is the foreground time recorded for it. */
    data class Interval(
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
    )

    enum class Basis { SAME_DAY_TYPE, ALL_DAYS }

    data class TypicalUsage(
        /** Average screen time on comparable past days up to the current time of day. */
        val expectedByNowMs: Long,
        /** Average full-day screen time on comparable past days. */
        val expectedFullDayMs: Long,
        /** Number of past days averaged. */
        val daysUsed: Int,
        val basis: Basis,
        /** "WEEKDAY" or "WEEKEND" — today's day type. */
        val dayType: String,
    )

    /** Days of the same type needed before we stop falling back to "all days". */
    const val MIN_SAME_TYPE_DAYS = 2

    fun compute(
        history: List<Interval>,
        nowMs: Long,
        calendarFactory: () -> Calendar = { Calendar.getInstance() },
        /** Start-of-day times of days to leave out (periods you labelled, like exams). */
        excludedDayStarts: Set<Long> = emptySet(),
    ): TypicalUsage? {
        val todayStart = startOfDay(nowMs, calendarFactory)
        val past = history.filter { it.startTime < todayStart && it.durationMs > 0 }
        if (past.isEmpty()) return null

        val elapsedToday = nowMs - todayStart
        val todayType = dayType(nowMs, calendarFactory)

        // The earliest day in local history is almost always partial (the OS history window
        // starts mid-day, and retention pruning cuts mid-day too), so it is skipped unless it
        // is the only day available.
        val firstDay = startOfDay(past.minOf { it.startTime }, calendarFactory)
        val allDays = mutableListOf<Long>()
        val cal = calendarFactory().apply { timeInMillis = firstDay }
        while (cal.timeInMillis < todayStart) {
            allDays.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val candidateDays = (if (allDays.size > 1) allDays.drop(1) else allDays).filter { it !in excludedDayStarts }

        val sameType = candidateDays.filter { dayType(it, calendarFactory) == todayType }
        val (days, basis) =
            if (sameType.size >= MIN_SAME_TYPE_DAYS) {
                sameType to Basis.SAME_DAY_TYPE
            } else {
                candidateDays to Basis.ALL_DAYS
            }
        if (days.isEmpty()) return null

        var byNowTotal = 0L
        var fullTotal = 0L
        for (dayStart in days) {
            val nextDay = calendarFactory().apply {
                timeInMillis = dayStart
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val cutoff = min(dayStart + elapsedToday, nextDay)
            byNowTotal += usageIn(past, dayStart, cutoff)
            fullTotal += usageIn(past, dayStart, nextDay)
        }

        return TypicalUsage(
            expectedByNowMs = byNowTotal / days.size,
            expectedFullDayMs = fullTotal / days.size,
            daysUsed = days.size,
            basis = basis,
            dayType = todayType,
        )
    }

    /** Foreground time inside [windowStart, windowEnd), pro-rating sessions that straddle the edges. */
    internal fun usageIn(
        sessions: List<Interval>,
        windowStart: Long,
        windowEnd: Long,
    ): Long {
        if (windowEnd <= windowStart) return 0L
        var total = 0L
        for (s in sessions) {
            val span = s.endTime - s.startTime
            if (span <= 0L) {
                if (s.startTime in windowStart until windowEnd) total += s.durationMs
                continue
            }
            val overlap = min(s.endTime, windowEnd) - max(s.startTime, windowStart)
            if (overlap <= 0L) continue
            total += if (overlap >= span) s.durationMs else (s.durationMs.toDouble() * overlap / span).toLong()
        }
        return total
    }

    private fun startOfDay(
        ms: Long,
        calendarFactory: () -> Calendar,
    ): Long =
        calendarFactory().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayType(
        ms: Long,
        calendarFactory: () -> Calendar,
    ): String {
        val dow = calendarFactory().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) "WEEKEND" else "WEEKDAY"
    }
}
