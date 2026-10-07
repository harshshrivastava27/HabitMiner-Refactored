package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId

/**
 * Today on one 24-hour strip: minutes of phone use in each hour so far, the usual amount for
 * each hour, when you slept, and where "now" is. Drawn under the screen-time figure on Today.
 */
data class DayRibbon(
    val date: LocalDate,
    /** Minutes of use in each hour today (24 values; hours after now are 0). */
    val todayMinutes: IntArray,
    /** Usual minutes in each hour on comparable days, or null before there's history. */
    val usualMinutes: IntArray?,
    /** Sleep and naps today as minute-of-day spans. */
    val sleep: List<Pair<Int, Int>>,
    val nowMinute: Int,
) {
    val maxMinutes: Int get() = maxOf(todayMinutes.maxOrNull() ?: 0, usualMinutes?.maxOrNull() ?: 0, 10)

    override fun equals(other: Any?): Boolean =
        other is DayRibbon && date == other.date && todayMinutes.contentEquals(other.todayMinutes) &&
            (usualMinutes?.contentEquals(other.usualMinutes ?: IntArray(0)) ?: (other.usualMinutes == null)) &&
            sleep == other.sleep && nowMinute == other.nowMinute

    override fun hashCode(): Int = 31 * todayMinutes.contentHashCode() + nowMinute
}

object DayRibbonBuilder {
    fun build(
        todaySessions: List<UsageSession>,
        curve: TypicalDayCurve?,
        lastNight: SleepEstimate?,
        naps: List<Pair<Long, Long>>,
        date: LocalDate,
        now: Long,
        zone: ZoneId,
    ): DayRibbon {
        val dayStart = TimeUtil.startOfDay(date, zone)
        val today = IntArray(24)
        for (h in 0 until 24) {
            val from = dayStart + h * TimeUtil.HOUR
            val to = minOf(from + TimeUtil.HOUR, now)
            if (to <= from) break
            val ms = todaySessions.sumOf { TimeUtil.usageIn(it, from, to) }
            today[h] = ((ms + 30_000L) / 60_000L).toInt().coerceAtMost(60)
        }
        // The curve is cumulative minutes at each hour mark; the difference is that hour's use.
        val usual =
            curve?.median?.takeIf { it.size >= 25 }?.let { m ->
                IntArray(24) { h -> (m[h + 1] - m[h]).coerceIn(0, 60) }
            }
        val dayEnd = dayStart + TimeUtil.DAY
        val spans = mutableListOf<Pair<Int, Int>>()
        lastNight?.let { n ->
            val s = maxOf(n.sleepStart, dayStart)
            val e = minOf(n.wakeTime, dayEnd)
            if (e > s) spans += minuteOf(s, dayStart) to minuteOf(e, dayStart)
        }
        naps.forEach { (s0, e0) ->
            val s = maxOf(s0, dayStart)
            val e = minOf(e0, dayEnd)
            if (e > s) spans += minuteOf(s, dayStart) to minuteOf(e, dayStart)
        }
        return DayRibbon(date, today, usual, spans.sortedBy { it.first }, minuteOf(now.coerceIn(dayStart, dayEnd), dayStart))
    }

    private fun minuteOf(
        t: Long,
        dayStart: Long,
    ): Int = ((t - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60)
}

/** The longest time today, while awake, without touching the phone. */
data class PhoneFreeStretch(
    val start: Long,
    val end: Long,
) {
    val durationMs: Long get() = end - start
}

object PhoneFree {
    /**
     * Longest gap between uses of the phone (app sessions and unlocks) between waking up and
     * now. Confirmed naps aren't counted as phone-free time. Null before any use today.
     */
    fun longestToday(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        awakeFrom: Long,
        now: Long,
        naps: List<Pair<Long, Long>> = emptyList(),
    ): PhoneFreeStretch? {
        if (now <= awakeFrom) return null
        // Busy intervals: sessions, unlocks (a minute each) and naps (not "phone-free").
        val busy = mutableListOf<Pair<Long, Long>>()
        sessions.filter { it.end > awakeFrom && it.start < now }.forEach { busy += maxOf(it.start, awakeFrom) to minOf(it.end, now) }
        unlocks.filter { it in awakeFrom until now }.forEach { busy += it to minOf(it + TimeUtil.MINUTE, now) }
        naps.filter { (s, e) -> e > awakeFrom && s < now }.forEach { (s, e) -> busy += maxOf(s, awakeFrom) to minOf(e, now) }
        if (busy.none { it.first < now && it.second > awakeFrom && it.first >= awakeFrom }) return null
        busy.sortBy { it.first }
        var best: PhoneFreeStretch? = null
        var cursor = awakeFrom
        for ((s, e) in busy) {
            if (s > cursor) {
                val gap = PhoneFreeStretch(cursor, s)
                if (best == null || gap.durationMs > best.durationMs) best = gap
            }
            cursor = maxOf(cursor, e)
        }
        if (now > cursor) {
            val gap = PhoneFreeStretch(cursor, now)
            if (best == null || gap.durationMs > best.durationMs) best = gap
        }
        return best?.takeIf { it.durationMs >= TimeUtil.MINUTE }
    }
}
