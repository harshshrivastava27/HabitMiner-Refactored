package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Which past days count as "comparable" to today. */
data class DaySelection(
    val days: List<LocalDate>,
    val sameDayType: Boolean,
    val dayTypeLabel: String,
) {
    val description: String
        get() {
            val word = if (days.size == 1) "day" else "days"
            return if (sameDayType) "${days.size} $dayTypeLabel $word" else "last ${days.size} $word"
        }
}

object DaySelector {
    /** Longest look-back used for typical-day and day-type analysis. */
    const val MAX_LOOKBACK_DAYS = 28

    /**
     * Full past days of history (at most [maxDays], most recent kept); the earliest day of
     * history is skipped because it is usually partial.
     */
    fun fullDays(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
        maxDays: Int = MAX_LOOKBACK_DAYS,
    ): List<LocalDate> {
        val first = sessions.minOfOrNull { it.start }?.let { TimeUtil.dateOf(it, zone) } ?: return emptyList()
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.toList()
        val usable = if (days.size > 1) days.drop(1) else days
        return usable.takeLast(maxDays)
    }

    /** Sessions bucketed by every calendar day they overlap. */
    fun byDay(
        sessions: List<UsageSession>,
        zone: ZoneId,
    ): Map<LocalDate, List<UsageSession>> {
        val out = mutableMapOf<LocalDate, MutableList<UsageSession>>()
        for (s in sessions) {
            var d = TimeUtil.dateOf(s.start, zone)
            val last = TimeUtil.dateOf(maxOf(s.start, s.end - 1), zone)
            while (!d.isAfter(last)) {
                out.getOrPut(d) { mutableListOf() }.add(s)
                d = d.plusDays(1)
            }
        }
        return out
    }

    /**
     * Same weekday/weekend type when at least two exist, otherwise all full days. Days in
     * [excluded] (periods you labelled, like exams) are left out of "usual".
     */
    fun comparable(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
        excluded: Set<LocalDate> = emptySet(),
    ): DaySelection {
        val all = fullDays(sessions, today, zone).filter { it !in excluded }
        val weekend = TimeUtil.isWeekend(today)
        val same = all.filter { TimeUtil.isWeekend(it) == weekend }
        val label = if (weekend) "weekend" else "weekday"
        return if (same.size >= 2) DaySelection(same, true, label) else DaySelection(all, false, label)
    }
}

// ---------------------------------------------------------------------------------------
// Week heatmap
// ---------------------------------------------------------------------------------------

data class HeatmapData(
    val days: List<LocalDate>,
    /** minutes[dayIndex][hour] */
    val minutes: List<IntArray>,
    /** Most-used app in each cell, or null. */
    val topApps: List<Array<String?>>,
) {
    val maxMinutes: Int get() = minutes.maxOfOrNull { row -> row.maxOrNull() ?: 0 } ?: 0

    /** Hour of day with the most use across the whole week. */
    val busiestHour: Int?
        get() {
            val totals = IntArray(24)
            minutes.forEach { row -> for (h in 0 until 24) totals[h] += row[h] }
            val max = totals.maxOrNull() ?: 0
            return if (max == 0) null else totals.indexOfFirst { it == max }
        }
}

object Heatmap {
    fun build(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
        dayCount: Int = 7,
    ): HeatmapData {
        val days = (dayCount - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val minutes = days.map { IntArray(24) }
        val perCellApps = days.map { Array(24) { mutableMapOf<String, Long>() } }
        val rangeStart = TimeUtil.startOfDay(days.first(), zone)
        val rangeEnd = TimeUtil.startOfDay(today.plusDays(1), zone)
        val ms = days.map { LongArray(24) }

        for (s in sessions) {
            if (s.end <= rangeStart || s.start >= rangeEnd) continue
            // Walk the session hour by hour so long sessions spread across cells.
            var cursor = maxOf(s.start, rangeStart)
            val stop = minOf(maxOf(s.end, s.start + 1), rangeEnd)
            while (cursor < stop) {
                val z = TimeUtil.zoned(cursor, zone)
                val dayIdx = days.indexOf(z.toLocalDate())
                val hourEnd = TimeUtil.at(z.toLocalDate(), z.hour + 1, 0, zone)
                val sliceEnd = minOf(hourEnd, stop)
                if (dayIdx >= 0) {
                    val part = TimeUtil.usageIn(s, cursor, sliceEnd)
                    ms[dayIdx][z.hour] += part
                    val apps = perCellApps[dayIdx][z.hour]
                    apps[s.appName] = (apps[s.appName] ?: 0L) + part
                }
                cursor = sliceEnd
            }
        }
        for (d in days.indices) for (h in 0 until 24) minutes[d][h] = (ms[d][h] / TimeUtil.MINUTE).toInt().coerceAtMost(60)
        val topApps = perCellApps.map { row -> Array(24) { h -> row[h].maxByOrNull { it.value }?.key } }
        return HeatmapData(days, minutes, topApps)
    }
}

// ---------------------------------------------------------------------------------------
// Typical-day curve
// ---------------------------------------------------------------------------------------

data class TypicalDayCurve(
    /** Cumulative minutes at each hour mark 0..24 (25 values). */
    val median: IntArray,
    val low: IntArray,
    val high: IntArray,
    /** Today's cumulative minutes as (hour as float, minutes) points up to now. */
    val today: List<Pair<Float, Int>>,
    val selection: DaySelection,
)

object TypicalDay {
    fun cumulative(
        sessions: List<UsageSession>,
        date: LocalDate,
        zone: ZoneId,
    ): IntArray {
        val start = TimeUtil.startOfDay(date, zone)
        val end = TimeUtil.startOfDay(date.plusDays(1), zone)
        val relevant = sessions.filter { it.end > start && it.start < end }
        val out = IntArray(25)
        var running = 0L
        for (h in 0 until 24) {
            val hs = TimeUtil.at(date, h, 0, zone)
            val he = TimeUtil.at(date, h + 1, 0, zone)
            running += relevant.sumOf { TimeUtil.usageIn(it, hs, he) }
            out[h + 1] = (running / TimeUtil.MINUTE).toInt()
        }
        return out
    }

    fun build(
        sessions: List<UsageSession>,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
        excluded: Set<LocalDate> = emptySet(),
    ): TypicalDayCurve? {
        val selection = DaySelector.comparable(sessions, today, zone, excluded)
        if (selection.days.isEmpty()) return null
        val byDay = DaySelector.byDay(sessions, zone)
        val curves = selection.days.map { cumulative(byDay[it].orEmpty(), it, zone) }
        val median = IntArray(25)
        val low = IntArray(25)
        val high = IntArray(25)
        for (h in 0..24) {
            val values = curves.map { it[h] }.sorted()
            median[h] = percentile(values, 0.5)
            low[h] = percentile(values, 0.25)
            high[h] = percentile(values, 0.75)
        }

        val todaySessions = byDay[today].orEmpty()
        val todayCurve = cumulative(todaySessions, today, zone)
        val nowHour = TimeUtil.minuteOfDay(now, zone) / 60f
        val points = mutableListOf<Pair<Float, Int>>()
        for (h in 0..24) {
            if (h > nowHour) break
            points.add(h.toFloat() to todayCurve[h])
        }
        val dayStart = TimeUtil.startOfDay(today, zone)
        val soFar = (todaySessions.sumOf { TimeUtil.usageIn(it, dayStart, now) } / TimeUtil.MINUTE).toInt()
        points.add(nowHour to soFar)
        return TypicalDayCurve(median, low, high, points, selection)
    }

    internal fun percentile(
        sorted: List<Int>,
        p: Double,
    ): Int {
        if (sorted.isEmpty()) return 0
        val pos = p * (sorted.size - 1)
        val lo = sorted[floor(pos).toInt()]
        val hi = sorted[ceil(pos).toInt()]
        return (lo + (hi - lo) * (pos - floor(pos))).toInt()
    }
}

// ---------------------------------------------------------------------------------------
// Day types (k-means on each day's usage profile)
// ---------------------------------------------------------------------------------------

data class DayType(
    val name: String,
    val description: String,
    val days: List<LocalDate>,
    val avgTotalMs: Long,
    /** Average minutes in each 4-hour block (6 values, starting at midnight). */
    val blocks: DoubleArray,
)

data class DayTypes(
    val types: List<DayType>,
    val dayToType: Map<LocalDate, Int>,
)

object DayTypeClusterer {
    const val MIN_DAYS = 6
    private val blockNames = listOf("late-night", "early-morning", "morning", "afternoon", "evening", "night")
    private val blockRanges = listOf("00–04", "04–08", "08–12", "12–16", "16–20", "20–24")

    fun profile(
        sessions: List<UsageSession>,
        date: LocalDate,
        zone: ZoneId,
    ): DoubleArray =
        DoubleArray(6) { b ->
            val s = TimeUtil.at(date, b * 4, 0, zone)
            val e = TimeUtil.at(date, b * 4 + 4, 0, zone)
            sessions.sumOf { TimeUtil.usageIn(it, s, e) }.toDouble() / TimeUtil.MINUTE
        }

    fun cluster(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
    ): DayTypes? {
        val days = DaySelector.fullDays(sessions, today, zone)
        if (days.size < MIN_DAYS) return null
        val byDay = DaySelector.byDay(sessions, zone)
        val vectors = days.map { profile(byDay[it].orEmpty(), it, zone) }
        val k = if (days.size < 10) 2 else 3
        val assignment = kMeans(vectors, k)

        // Reference: the average day. Each group is named by how it differs from it,
        // because "busiest block" alone gives every group the same name when one habit
        // (e.g. late-night gaming) dominates every day.
        val overall = DoubleArray(6) { b -> vectors.map { it[b] }.average() }
        val meanTotal = overall.sum().coerceAtLeast(1.0)

        val clusters =
            (0 until k).mapNotNull { c ->
                val members = days.indices.filter { assignment[it] == c }
                if (members.isEmpty()) return@mapNotNull null
                val centroid = DoubleArray(6) { b -> members.map { vectors[it][b] }.average() }
                members to centroid
            }

        // k-means always returns k groups, even when every day looks the same. If no group
        // differs from the average day by at least 15% in volume or 30 minutes in any 4-hour
        // block, say the days are consistent instead of inventing weak distinctions.
        val distinct =
            clusters.any { (_, centroid) ->
                kotlin.math.abs(centroid.sum() / meanTotal - 1.0) >= 0.15 ||
                    centroid.indices.any { kotlin.math.abs(centroid[it] - overall[it]) >= 30.0 }
            }
        if (!distinct) {
            val avgMs = (meanTotal * TimeUtil.MINUTE).toLong()
            val peak = overall.indices.maxByOrNull { overall[it] } ?: 0
            val single =
                DayType(
                    name = "Consistent days",
                    description = "Your days look alike: about ${Format.duration(avgMs)} a day · busiest ${blockRanges[peak]}",
                    days = days,
                    avgTotalMs = avgMs,
                    blocks = overall,
                )
            return DayTypes(listOf(single), days.associateWith { 0 })
        }

        // Name groups by rank of daily volume (plain words people understand), and describe
        // where each group's extra use sits compared with the average day.
        val ranked = clusters.sortedByDescending { it.second.sum() }
        val rankNames =
            when (ranked.size) {
                1 -> listOf("Your usual days")
                2 -> listOf("Busier days", "Quieter days")
                else -> listOf("Busier days", "In-between days", "Quieter days")
            }
        val types =
            ranked.mapIndexed { rank, (members, centroid) ->
                val total = centroid.sum()
                val memberDays = members.map { days[it] }
                val weekendShare = memberDays.count { TimeUtil.isWeekend(it) }.toDouble() / memberDays.size
                val mix =
                    when {
                        memberDays.size >= 2 && weekendShare >= 0.7 -> " (mostly weekends)"
                        memberDays.size >= 3 && weekendShare == 0.0 -> " (weekdays)"
                        else -> ""
                    }
                val diffs = DoubleArray(6) { b -> centroid[b] - overall[b] }
                val extra = diffs.indices.maxByOrNull { diffs[it] }!!
                val peak = centroid.indices.maxByOrNull { centroid[it] } ?: 0
                val where =
                    if (diffs[extra] >= 10.0) {
                        "extra use ${blockRanges[extra]}"
                    } else {
                        "busiest ${blockRanges[peak]}"
                    }
                val avgMs = (total * TimeUtil.MINUTE).toLong()
                DayType(
                    name = rankNames.getOrElse(rank) { "Group ${rank + 1}" } + mix,
                    description = "About ${Format.duration(avgMs)} a day · $where",
                    days = memberDays,
                    avgTotalMs = avgMs,
                    blocks = centroid,
                )
            }

        val dayToType = mutableMapOf<LocalDate, Int>()
        types.forEachIndexed { idx, t -> t.days.forEach { dayToType[it] = idx } }
        return DayTypes(types, dayToType)
    }

    /** Deterministic k-means: seeds with the heaviest day, then farthest-point picks. */
    internal fun kMeans(
        vectors: List<DoubleArray>,
        k: Int,
        iterations: Int = 50,
    ): IntArray {
        val n = vectors.size
        val centroids = mutableListOf(vectors[vectors.indices.maxByOrNull { vectors[it].sum() }!!].copyOf())
        while (centroids.size < k) {
            val next = vectors.indices.maxByOrNull { i -> centroids.minOf { dist(vectors[i], it) } }!!
            centroids.add(vectors[next].copyOf())
        }
        val assign = IntArray(n) { -1 }
        repeat(iterations) {
            var changed = false
            for (i in 0 until n) {
                val best = centroids.indices.minByOrNull { dist(vectors[i], centroids[it]) }!!
                if (assign[i] != best) {
                    assign[i] = best
                    changed = true
                }
            }
            for (c in centroids.indices) {
                val members = (0 until n).filter { assign[it] == c }
                if (members.isNotEmpty()) {
                    centroids[c] = DoubleArray(vectors[0].size) { d -> members.map { vectors[it][d] }.average() }
                }
            }
            if (!changed) return assign
        }
        return assign
    }

    private fun dist(
        a: DoubleArray,
        b: DoubleArray,
    ): Double = sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
}

// ---------------------------------------------------------------------------------------
// This week vs last week
// ---------------------------------------------------------------------------------------

data class Change(
    val name: String,
    val currentPerDayMs: Long,
    val previousPerDayMs: Long,
) {
    val deltaMs: Long get() = currentPerDayMs - previousPerDayMs
}

data class WeekComparison(
    val currentPerDayMs: Long,
    val previousPerDayMs: Long,
    val currentDays: Int,
    val previousDays: Int,
    val categories: List<Change>,
    val biggestMovers: List<Change>,
) {
    val deltaMs: Long get() = currentPerDayMs - previousPerDayMs
}

object WeekComparer {
    /** Last 7 full days vs the 7 before them, as per-day averages over days that have data. */
    fun compare(
        sessions: List<UsageSession>,
        today: LocalDate,
        zone: ZoneId,
    ): WeekComparison? {
        val full = DaySelector.fullDays(sessions, today, zone).toSet()
        val current = (1..7).map { today.minusDays(it.toLong()) }.filter { it in full }
        val previous = (8..14).map { today.minusDays(it.toLong()) }.filter { it in full }
        if (current.size < 3 || previous.size < 3) return null

        fun inDays(days: List<LocalDate>): List<UsageSession> {
            val ranges = days.map { TimeUtil.startOfDay(it, zone) to TimeUtil.startOfDay(it.plusDays(1), zone) }
            return sessions.filter { s -> ranges.any { (a, b) -> s.start in a until b } }
        }
        val cur = inDays(current)
        val prev = inDays(previous)
        val curTotal = cur.sumOf { it.durationMs } / current.size
        val prevTotal = prev.sumOf { it.durationMs } / previous.size

        val curCat = SessionGrouper.byCategory(cur).toMap()
        val prevCat = SessionGrouper.byCategory(prev).toMap()
        val categories =
            (curCat.keys + prevCat.keys).map { c ->
                Change(c.label, (curCat[c] ?: 0L) / current.size, (prevCat[c] ?: 0L) / previous.size)
            }.sortedByDescending { it.currentPerDayMs }

        val curApp = SessionGrouper.byApp(cur).toMap()
        val prevApp = SessionGrouper.byApp(prev).toMap()
        val movers =
            (curApp.keys + prevApp.keys).map { a ->
                Change(a, (curApp[a] ?: 0L) / current.size, (prevApp[a] ?: 0L) / previous.size)
            }.filter { kotlin.math.abs(it.deltaMs) >= 10 * TimeUtil.MINUTE }
                .sortedByDescending { kotlin.math.abs(it.deltaMs) }
                .take(3)

        return WeekComparison(curTotal, prevTotal, current.size, previous.size, categories, movers)
    }
}
