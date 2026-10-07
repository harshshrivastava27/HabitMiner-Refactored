package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId

/** Totals for one app over today, the last 7 days and the whole window. */
data class AppSummary(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val todayMs: Long,
    val todayOpens: Int,
    val weekMs: Long,
    val weekOpens: Int,
    val windowMs: Long,
    val windowOpens: Int,
    /** Minutes per day for the last 14 days, oldest first. */
    val last14: List<Float>,
)

/** Everything on an app's detail page. */
data class AppDetail(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val days: Int,
    val totalMs: Long,
    val opens: Int,
    val perDayMs: Long,
    val medianSessionMs: Long,
    val todayMs: Long,
    val weekMs: Long,
    val previousWeekMs: Long,
    /** Minutes by weekday (Mon = 0) and hour, averaged over the weeks in the window. */
    val weekdayHour: Array<IntArray>,
    val busiestHour: Int?,
    /** Opens that came within a minute of a notification from the same app. */
    val opensAfterNotification: Int,
    val notifications: Int,
    /** Minutes per day for the last 14 days, oldest first. */
    val last14: List<Float>,
) {
    override fun equals(other: Any?): Boolean = other is AppDetail && packageName == other.packageName && totalMs == other.totalMs && opens == other.opens

    override fun hashCode(): Int = packageName.hashCode() * 31 + totalMs.hashCode()
}

object UsageSummaries {
    /** Screen time per day for the [days] days up to [today], oldest first. */
    fun daily(
        sessions: List<UsageSession>,
        days: Int,
        today: LocalDate,
        zone: ZoneId,
    ): List<Pair<LocalDate, Long>> =
        (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val from = TimeUtil.startOfDay(d, zone)
            val to = TimeUtil.startOfDay(d.plusDays(1), zone)
            d to sessions.sumOf { TimeUtil.usageIn(it, from, to) }
        }

    fun apps(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
    ): List<AppSummary> {
        val todayStart = TimeUtil.startOfDay(today, zone)
        val weekStart = TimeUtil.startOfDay(today.minusDays(6), zone)
        val fortnightStart = today.minusDays(13)
        return sessions.groupBy { it.packageName }.map { (pkg, list) ->
            val name = list.last().appName
            val perDay = FloatArray(14)
            list.forEach { s ->
                val d = TimeUtil.dateOf(s.start, zone)
                val idx = java.time.temporal.ChronoUnit.DAYS.between(fortnightStart, d).toInt()
                if (idx in 0 until 14) perDay[idx] += s.durationMs / 60_000f
            }
            AppSummary(
                packageName = pkg,
                appName = name,
                category = list.last().category,
                todayMs = list.filter { it.start >= todayStart }.sumOf { it.durationMs },
                todayOpens = list.count { it.start >= todayStart },
                weekMs = list.filter { it.start >= weekStart }.sumOf { it.durationMs },
                weekOpens = list.count { it.start >= weekStart },
                windowMs = list.sumOf { it.durationMs },
                windowOpens = list.size,
                last14 = perDay.toList(),
            )
        }.sortedByDescending { it.weekMs }
    }

    /**
     * The detail page for one app from its sessions ([sessions] all belong to it) and the
     * notifications it posted.
     */
    fun detail(
        sessions: List<UsageSession>,
        notifications: List<Long>,
        today: LocalDate,
        zone: ZoneId,
    ): AppDetail? {
        if (sessions.isEmpty()) return null
        val sorted = sessions.sortedBy { it.start }
        val first = TimeUtil.dateOf(sorted.first().start, zone)
        val days = (java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt() + 1).coerceAtLeast(1)
        val total = sorted.sumOf { it.durationMs }
        val durations = sorted.map { it.durationMs }.sorted()
        val median = durations[durations.size / 2]
        val todayStart = TimeUtil.startOfDay(today, zone)
        val weekStart = TimeUtil.startOfDay(today.minusDays(6), zone)
        val prevStart = TimeUtil.startOfDay(today.minusDays(13), zone)
        val grid = Array(7) { IntArray(24) }
        val weeks = (days / 7.0).coerceAtLeast(1.0)
        val minutes = Array(7) { DoubleArray(24) }
        sorted.forEach { s ->
            // Spread each session over the hours it covers.
            var t = s.start
            while (t < s.end) {
                val z = TimeUtil.zoned(t, zone)
                val hourEnd = TimeUtil.startOfDay(z.toLocalDate(), zone) + (z.hour + 1) * TimeUtil.HOUR
                val segEnd = minOf(hourEnd, s.end)
                minutes[z.dayOfWeek.value - 1][z.hour] += TimeUtil.usageIn(s, t, segEnd) / 60_000.0
                t = segEnd
            }
        }
        for (d in 0 until 7) for (h in 0 until 24) grid[d][h] = (minutes[d][h] / weeks).toInt()
        val byHour = (0 until 24).map { h -> h to (0 until 7).sumOf { minutes[it][h] } }
        val busiest = byHour.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
        val sortedNotes = notifications.sorted()
        val afterNote =
            sorted.count { s ->
                val i = sortedNotes.binarySearch(s.start).let { if (it < 0) -it - 2 else it }
                i >= 0 && s.start - sortedNotes[i] in 0..60_000L
            }
        val fortnightStart = today.minusDays(13)
        val perDay = FloatArray(14)
        sorted.forEach { s ->
            val idx = java.time.temporal.ChronoUnit.DAYS.between(fortnightStart, TimeUtil.dateOf(s.start, zone)).toInt()
            if (idx in 0 until 14) perDay[idx] += s.durationMs / 60_000f
        }
        return AppDetail(
            packageName = sorted.last().packageName,
            appName = sorted.last().appName,
            category = sorted.last().category,
            days = days,
            totalMs = total,
            opens = sorted.size,
            perDayMs = total / days,
            medianSessionMs = median,
            todayMs = sorted.filter { it.start >= todayStart }.sumOf { it.durationMs },
            weekMs = sorted.filter { it.start >= weekStart }.sumOf { it.durationMs },
            previousWeekMs = sorted.filter { it.start in prevStart until weekStart }.sumOf { it.durationMs },
            weekdayHour = grid,
            busiestHour = busiest,
            opensAfterNotification = afterNote,
            notifications = notifications.size,
            last14 = perDay.toList(),
        )
    }
}
