package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Kinds of difference from your usual pattern. */
enum class DeviationKind {
    LESS_USE,
    MORE_USE,
    LATE_NIGHT,
    APP_DROP,
    APP_SPIKE,
    APP_NEW,
    MORE_UNLOCKS,
    FEWER_UNLOCKS,
    SHORT_SLEEP,
    LONG_SLEEP,
    LATE_BEDTIME,
    EARLY_BEDTIME,
    LATE_WAKE,
    EARLY_WAKE,
}

/** One way a day differed from your usual days. */
data class DayDeviation(
    val date: LocalDate,
    val kind: DeviationKind,
    /** "Quieter day than usual" */
    val title: String,
    /** "1h 35m by 18:11, usually about 3h 38m by now. Mostly in the afternoon." */
    val detail: String,
    /** App name, or "ALL" / "SLEEP" / "UNLOCKS". */
    val subject: String,
    /** How unusual, roughly in standard deviations (robust); used for ordering and alerts. */
    val score: Float,
    val occurredAt: Long,
    /** Today, compared up to the current time of day. */
    val partialDay: Boolean,
    /** Set when the day falls in a period you labelled, e.g. "Exams". */
    val explainedBy: String? = null,
) {
    /** Stable identity for feedback; matches the stored deviation's fingerprint. */
    val key: String get() = "$date|${kind.name}|DAY|$subject"

    /** Worth an evening notification. */
    val notable: Boolean get() = explainedBy == null && score >= DeviationFinder.NOTABLE_SCORE
}

data class AppChange(
    val app: String,
    val usualPerDayMs: Long,
    val nowPerDayMs: Long,
)

/** Several days in a row clearly above or below your usual phone use. */
data class RoutineShift(
    val since: LocalDate,
    val days: Int,
    /** -0.4 = 40% less than usual. */
    val change: Float,
    val avgPerDayMs: Long,
    val usualPerDayMs: Long,
    val appChanges: List<AppChange>,
    /** What you said it was ("exams", "travel"…), or null if not answered yet. */
    val label: String? = null,
) {
    val less: Boolean get() = change < 0f

    /** Key for the "what's different?" answer. */
    val key: String get() = "period|$since"
}

/** A stretch of days you labelled; kept out of "usual" when comparing. */
data class LabelledPeriod(
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

data class DeviationReport(
    /** Newest first. */
    val days: List<DayDeviation>,
    val shift: RoutineShift?,
)

/**
 * Compares each of the last days, and today so far, with your usual days.
 *
 * "Usual" is the median of up to 28 earlier days of the same kind (weekday or weekend, when
 * there are at least 4 of them, otherwise all days), leaving out the first, partial day of
 * data and any period you labelled (exams, travel…). Spread is the median absolute
 * deviation, with floors so a very regular habit doesn't make every small change "unusual".
 * Today is compared with the same days up to the same time of day, so a quiet morning
 * isn't flagged against whole days.
 *
 * Differences are reported in both directions, and only when they are both unusual
 * (robust z of 2 or more) and large enough to matter (e.g. 45 minutes of screen time).
 */
object DeviationFinder {
    const val NOTABLE_SCORE = 2.5f
    private const val REF_DAYS = 28L
    private const val MIN_REF_DAYS = 5
    private const val MIN_SAME_TYPE = 4
    private const val MIN_TOTAL_DIFF_MS = 45 * TimeUtil.MINUTE
    private const val MIN_APP_DIFF_MS = 30 * TimeUtil.MINUTE

    private data class Block(val name: String, val from: Int, val to: Int)

    private val blocks =
        listOf(
            Block("late at night", 0, 6),
            Block("in the morning", 6, 12),
            Block("in the afternoon", 12, 17),
            Block("in the evening", 17, 24),
        )

    fun find(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        nights: List<SleepEstimate>,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
        periods: List<LabelledPeriod> = emptyList(),
        daysBack: Int = 7,
    ): DeviationReport {
        if (sessions.isEmpty()) return DeviationReport(emptyList(), null)
        val ctx = Ctx(sessions.sortedBy { it.start }, unlocks.sorted(), nights, today, now, zone, periods)
        val out = mutableListOf<DayDeviation>()
        for (back in 0 until daysBack) {
            val date = today.minusDays(back.toLong())
            if (date.isBefore(ctx.firstFullDay)) break
            out += ctx.dayDeviations(date)
        }
        val shift = ctx.routineShift()
        return DeviationReport(out.sortedWith(compareByDescending<DayDeviation> { it.date }.thenByDescending { it.score }), shift)
    }

    private class Ctx(
        val sessions: List<UsageSession>,
        val unlocks: List<Long>,
        val nights: List<SleepEstimate>,
        val today: LocalDate,
        val now: Long,
        val zone: ZoneId,
        val periods: List<LabelledPeriod>,
    ) {
        /** The first day of data is usually partial, so comparisons start the day after. */
        val firstFullDay: LocalDate = TimeUtil.dateOf(sessions.first().start, zone).plusDays(1)
        private val firstUnlockDay: LocalDate? = unlocks.firstOrNull()?.let { TimeUtil.dateOf(it, zone).plusDays(1) }

        fun periodOf(date: LocalDate): LabelledPeriod? = periods.firstOrNull { date in it }

        /** Minute of day the comparison runs up to: now for today, the whole day otherwise. */
        fun cutoff(date: LocalDate): Int = if (date == today) TimeUtil.minuteOfDay(now, zone) else 24 * 60

        private fun window(
            date: LocalDate,
            fromMinute: Int,
            toMinute: Int,
        ): Pair<Long, Long> {
            val start = TimeUtil.startOfDay(date, zone)
            return (start + fromMinute * TimeUtil.MINUTE) to (start + toMinute * TimeUtil.MINUTE)
        }

        private val cache = HashMap<String, Map<String, Long>>()

        /** Minutes of use per app between [fromMinute] and [toMinute] on [date]. */
        fun perApp(
            date: LocalDate,
            fromMinute: Int,
            toMinute: Int,
        ): Map<String, Long> =
            cache.getOrPut("$date|$fromMinute|$toMinute") {
                val (a, b) = window(date, fromMinute, toMinute)
                val out = HashMap<String, Long>()
                for (s in sessions) {
                    if (s.start >= b) break
                    if (s.end <= a) continue
                    val ms = TimeUtil.usageIn(s, a, b)
                    if (ms > 0) out.merge(s.appName, ms, Long::plus)
                }
                out
            }

        fun total(
            date: LocalDate,
            fromMinute: Int,
            toMinute: Int,
        ): Long = perApp(date, fromMinute, toMinute).values.sum()

        /** Earlier days to compare [date] with. */
        fun references(
            date: LocalDate,
            before: LocalDate = date,
        ): List<LocalDate> {
            val all =
                (1..REF_DAYS).map { before.minusDays(it) }
                    .filter { !it.isBefore(firstFullDay) && periodOf(it) == null }
            val same = all.filter { TimeUtil.isWeekend(it) == TimeUtil.isWeekend(date) }
            return if (same.size >= MIN_SAME_TYPE) same else all
        }

        fun dayDeviations(date: LocalDate): List<DayDeviation> {
            val refs = references(date)
            val explained = periodOf(date)?.label
            val partial = date == today
            val until = cutoff(date)
            val out = mutableListOf<DayDeviation>()
            val dayStart = TimeUtil.startOfDay(date, zone)
            val at = if (partial) now else dayStart + TimeUtil.DAY - 1
            val byNow = if (partial) " by ${Format.clock(now, zone)}" else ""
            val usuallyByNow = if (partial) " by now" else ""

            if (refs.size >= MIN_REF_DAYS && (!partial || until >= 10 * 60)) {
                // Total screen time.
                val x = total(date, 0, until)
                val ref = refs.map { total(it, 0, until) }
                val st = Stats.of(ref, floorFraction = 0.2, floorAbs = 15 * TimeUtil.MINUTE)
                val z = st.z(x)
                // Phone use varies a lot from day to day, so a big relative change also counts
                // even when the spread keeps z below 2 (e.g. 1h 35m against a usual 3h 34m).
                val ratio = if (st.median > 0) x.toDouble() / st.median else 1.0
                val bigChange = ratio <= 0.65 || ratio >= 1.5
                val score = maxOf(abs(z), if (bigChange) (2.0 + 2.0 * abs(1.0 - ratio)).toFloat() else 0f).coerceAtMost(6f)
                if ((abs(z) >= 2f || bigChange) && abs(x - st.median) >= MIN_TOTAL_DIFF_MS) {
                    val less = x < st.median
                    val where = biggestBlock(date, refs, until, less)
                    out +=
                        DayDeviation(
                            date = date,
                            kind = if (less) DeviationKind.LESS_USE else DeviationKind.MORE_USE,
                            title =
                                when {
                                    less && partial -> "Quieter than usual so far"
                                    less -> "Quieter day than usual"
                                    partial -> "Busier than usual so far"
                                    else -> "Busier day than usual"
                                },
                            detail =
                                "${Format.duration(x)} on your phone$byNow, usually about ${Format.duration(st.median)}$usuallyByNow." +
                                    (where?.let { " Mostly $it." } ?: ""),
                            subject = "ALL",
                            score = score,
                            occurredAt = at,
                            partialDay = partial,
                            explainedBy = explained,
                        )
                }

                // Late-night use (only once that part of the day is over).
                if (until >= 6 * 60) {
                    val ln = total(date, 0, 6 * 60)
                    val lnRef = refs.map { total(it, 0, 6 * 60) }
                    val lst = Stats.of(lnRef, floorFraction = 0.25, floorAbs = 15 * TimeUtil.MINUTE)
                    if (lst.z(ln) >= 2f && ln - lst.median >= 40 * TimeUtil.MINUTE) {
                        out +=
                            DayDeviation(
                                date, DeviationKind.LATE_NIGHT, "More late-night phone use",
                                "${Format.duration(ln)} between midnight and 6:00, usually about ${Format.duration(lst.median)}.",
                                "LATE_NIGHT", lst.z(ln), dayStart + 6 * TimeUtil.HOUR, partial, explained,
                            )
                    }
                }

                out += appDeviations(date, refs, until, partial, at, byNow, usuallyByNow, explained)
                unlockDeviation(date, until, partial, at, byNow, usuallyByNow, explained)?.let(out::add)
            }
            out += sleepDeviations(date, explained)
            return out
        }

        /** The part of the day that contributed most to a quieter / busier total. */
        private fun biggestBlock(
            date: LocalDate,
            refs: List<LocalDate>,
            until: Int,
            less: Boolean,
        ): String? {
            var best: Pair<String, Long>? = null
            for (b in blocks) {
                if (b.from * 60 >= until) continue
                val to = minOf(b.to * 60, until)
                val x = total(date, b.from * 60, to)
                val m = Stats.median(refs.map { total(it, b.from * 60, to) })
                val diff = if (less) m - x else x - m
                if (diff > 20 * TimeUtil.MINUTE && (best == null || diff > best.second)) best = b.name to diff
            }
            return best?.let { "${it.first} (${if (less) "−" else "+"}${Format.duration(it.second)})" }
        }

        private fun appDeviations(
            date: LocalDate,
            refs: List<LocalDate>,
            until: Int,
            partial: Boolean,
            at: Long,
            byNow: String,
            usuallyByNow: String,
            explained: String?,
        ): List<DayDeviation> {
            val today = perApp(date, 0, until)
            val refMaps = refs.map { perApp(it, 0, until) }
            val apps = (today.keys + refMaps.flatMap { it.keys }).toSet()
            val found = mutableListOf<Pair<DayDeviation, Long>>()
            for (app in apps) {
                val x = today[app] ?: 0L
                val values = refMaps.map { it[app] ?: 0L }
                val presence = values.count { it >= 5 * TimeUtil.MINUTE }.toFloat() / values.size
                val st = Stats.of(values, floorFraction = 0.25, floorAbs = 10 * TimeUtil.MINUTE)
                val m = st.median
                when {
                    presence <= 0.15f && m < 5 * TimeUtil.MINUTE && x >= 25 * TimeUtil.MINUTE ->
                        found +=
                            DayDeviation(
                                date, DeviationKind.APP_NEW, "Lots of $app",
                                "${Format.duration(x)}$byNow. You rarely use it.",
                                app, 3f, at, partial, explained,
                            ) to x
                    presence >= 0.6f && m >= 20 * TimeUtil.MINUTE && x <= m / 4 && m - x >= MIN_APP_DIFF_MS ->
                        found +=
                            DayDeviation(
                                date, DeviationKind.APP_DROP, "Much less $app",
                                "${if (x < TimeUtil.MINUTE) "Almost none" else Format.duration(x)}$byNow, usually about ${Format.duration(m)}$usuallyByNow.",
                                app, maxOf(2f, abs(st.z(x))).coerceAtMost(6f), at, partial, explained,
                            ) to (m - x)
                    x >= MIN_APP_DIFF_MS && x >= 2 * m && x - m >= maxOf(3 * st.spread, MIN_APP_DIFF_MS) ->
                        found +=
                            DayDeviation(
                                date, DeviationKind.APP_SPIKE, "More $app than usual",
                                "${Format.duration(x)}$byNow, usually about ${Format.duration(m)}$usuallyByNow.",
                                app, st.z(x).coerceAtMost(6f), at, partial, explained,
                            ) to (x - m)
                }
            }
            return found.sortedByDescending { it.second }.take(3).map { it.first }
        }

        private fun unlockDeviation(
            date: LocalDate,
            until: Int,
            partial: Boolean,
            at: Long,
            byNow: String,
            usuallyByNow: String,
            explained: String?,
        ): DayDeviation? {
            val first = firstUnlockDay ?: return null
            fun count(d: LocalDate): Long {
                val (a, b) = window(d, 0, until)
                return unlocks.count { it in a until b }.toLong()
            }
            if (date.isBefore(first)) return null
            val refs = references(date).filter { !it.isBefore(first) }
            if (refs.size < MIN_REF_DAYS) return null
            val x = count(date)
            val st = Stats.of(refs.map { count(it) }, floorFraction = 0.2, floorAbs = 5)
            val z = st.z(x)
            if (abs(z) < 2f || abs(x - st.median) < 15) return null
            val more = x > st.median
            return DayDeviation(
                date,
                if (more) DeviationKind.MORE_UNLOCKS else DeviationKind.FEWER_UNLOCKS,
                if (more) "More pickups than usual" else "Fewer pickups than usual",
                "$x unlocks$byNow, usually about ${st.median}$usuallyByNow.",
                "UNLOCKS", abs(z).coerceAtMost(6f), at, partial, explained,
            )
        }

        private fun sleepDeviations(
            date: LocalDate,
            explained: String?,
        ): List<DayDeviation> {
            val night = nights.firstOrNull { it.wakeDate == date } ?: return emptyList()
            val refNights =
                nights.filter {
                    it.wakeDate.isBefore(date) && !it.wakeDate.isBefore(date.minusDays(REF_DAYS)) && periodOf(it.wakeDate) == null
                }
            if (refNights.size < 4) return emptyList()
            val out = mutableListOf<DayDeviation>()
            val partial = date == today

            val dur = Stats.of(refNights.map { it.durationMs }, floorFraction = 0.1, floorAbs = 30 * TimeUtil.MINUTE)
            val asleep = night.durationMs
            if (asleep < 6 * TimeUtil.HOUR && dur.median - asleep >= maxOf(90 * TimeUtil.MINUTE, 2 * dur.spread)) {
                out +=
                    DayDeviation(
                        date, DeviationKind.SHORT_SLEEP, "Short night",
                        "About ${Format.duration(asleep)} of sleep, usually about ${Format.duration(dur.median)}.",
                        "SLEEP", abs(dur.z(asleep)).coerceIn(2.5f, 6f), night.wakeTime, partial, explained,
                    )
            } else if (asleep - dur.median >= maxOf(2 * TimeUtil.HOUR, 2 * dur.spread)) {
                out +=
                    DayDeviation(
                        date, DeviationKind.LONG_SLEEP, "Long night",
                        "About ${Format.duration(asleep)} of sleep, usually about ${Format.duration(dur.median)}.",
                        "SLEEP", abs(dur.z(asleep)).coerceIn(2f, 6f), night.wakeTime, partial, explained,
                    )
            }

            val bed = SleepDetector.bedtimeMinutes(night.sleepStart, zone).toLong()
            val bedMedian = Stats.median(refNights.map { SleepDetector.bedtimeMinutes(it.sleepStart, zone).toLong() })
            val bedShift = bed - bedMedian
            if (abs(bedShift) >= 120) {
                val late = bedShift > 0
                out +=
                    DayDeviation(
                        date, if (late) DeviationKind.LATE_BEDTIME else DeviationKind.EARLY_BEDTIME,
                        if (late) "Later night than usual" else "Earlier night than usual",
                        "Fell asleep around ${Format.clock(night.sleepStart, zone)}, usually around ${Format.clockFromMinutes(bedMedian.toInt())}.",
                        "SLEEP_START", (abs(bedShift) / 60f).coerceIn(2f, 6f), night.sleepStart, partial, explained,
                    )
            }
            val wake = TimeUtil.minuteOfDay(night.wakeTime, zone).toLong()
            val wakeMedian = Stats.median(refNights.map { TimeUtil.minuteOfDay(it.wakeTime, zone).toLong() })
            val wakeShift = wake - wakeMedian
            if (abs(wakeShift) >= 120) {
                val late = wakeShift > 0
                out +=
                    DayDeviation(
                        date, if (late) DeviationKind.LATE_WAKE else DeviationKind.EARLY_WAKE,
                        if (late) "Woke up later than usual" else "Woke up earlier than usual",
                        "Up around ${Format.clock(night.wakeTime, zone)}, usually around ${Format.clockFromMinutes(wakeMedian.toInt())}.",
                        "SLEEP_END", (abs(wakeShift) / 60f).coerceIn(2f, 6f), night.wakeTime, partial, explained,
                    )
            }
            return out
        }

        /** Ratio of a day's use to its usual (by now for today); null without enough history. */
        private fun ratio(
            date: LocalDate,
            refsBefore: LocalDate = date,
        ): Pair<Long, Long>? {
            val refs = references(date, refsBefore)
            if (refs.size < MIN_REF_DAYS) return null
            val until = cutoff(date)
            val m = Stats.median(refs.map { total(it, 0, until) })
            if (m < 30 * TimeUtil.MINUTE) return null
            return total(date, 0, until) to m
        }

        fun routineShift(): RoutineShift? {
            // The last three days, counting today only once enough of it has passed.
            val includeToday = TimeUtil.minuteOfDay(now, zone) >= 14 * 60
            val end = if (includeToday) today else today.minusDays(1)
            val recent = (0L until 3L).map { end.minusDays(it) }
            if (recent.any { it.isBefore(firstFullDay) }) return null
            val ratios = recent.map { d -> ratio(d)?.let { (x, m) -> x.toDouble() / m } ?: return null }
            val lower = ratios.all { it <= 0.8 } && ratios.average() <= 0.75
            val higher = ratios.all { it >= 1.25 } && ratios.average() >= 1.33
            if (!lower && !higher) return null

            // Walk back to where it started.
            var since = recent.last()
            for (i in 1..14) {
                val d = recent.last().minusDays(i.toLong())
                if (d.isBefore(firstFullDay)) break
                val r = ratio(d)?.let { (x, m) -> x.toDouble() / m } ?: break
                if ((lower && r <= 0.85) || (higher && r >= 1.18)) since = d else break
            }

            // Compare every day of the shift with the usual from before it started.
            val days = generateSequence(since) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            val pairs = days.mapNotNull { ratio(it, since) }
            if (pairs.isEmpty()) return null
            val avg = pairs.map { it.first }.average().toLong()
            val usual = pairs.map { it.second }.average().toLong()

            val refs = references(since, since)
            val appNow = HashMap<String, Long>()
            val appUsual = HashMap<String, Long>()
            for (d in days) {
                val until = cutoff(d)
                perApp(d, 0, until).forEach { (app, ms) -> appNow.merge(app, ms, Long::plus) }
                val refMaps = refs.map { perApp(it, 0, until) }
                (refMaps.flatMap { it.keys }.toSet()).forEach { app ->
                    appUsual.merge(app, Stats.median(refMaps.map { it[app] ?: 0L }), Long::plus)
                }
            }
            val n = days.size
            val changes =
                (appNow.keys + appUsual.keys).map { app ->
                    AppChange(app, (appUsual[app] ?: 0L) / n, (appNow[app] ?: 0L) / n)
                }.filter { abs(it.nowPerDayMs - it.usualPerDayMs) >= 15 * TimeUtil.MINUTE }
                    .sortedByDescending { abs(it.nowPerDayMs - it.usualPerDayMs) }
                    .take(3)
            val label = periods.firstOrNull { !since.isBefore(it.from.minusDays(2)) && !since.isAfter(it.from.plusDays(2)) }?.label
            return RoutineShift(
                since = since,
                days = n,
                change = ((avg - usual).toDouble() / usual).toFloat(),
                avgPerDayMs = avg,
                usualPerDayMs = usual,
                appChanges = changes,
                label = label,
            )
        }
    }

    /** "Sun 4 Oct". */
    fun shortDate(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth} " +
            date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

/** Median and a robust spread (median absolute deviation, with floors). */
internal class Stats(val median: Long, val spread: Long) {
    fun z(x: Long): Float = if (spread <= 0) 0f else (x - median).toFloat() / spread

    companion object {
        fun median(values: List<Long>): Long {
            if (values.isEmpty()) return 0L
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        fun of(
            values: List<Long>,
            floorFraction: Double,
            floorAbs: Long,
        ): Stats {
            val m = median(values)
            val mad = median(values.map { abs(it - m) })
            val spread = maxOf((1.4826 * mad).toLong(), (floorFraction * m).toLong(), floorAbs)
            return Stats(m, spread)
        }
    }
}

/** Text for the evening notification about today's notable deviations. */
object DeviationSummary {
    data class Summary(
        val title: String,
        val lines: List<String>,
        /** Deviation keys the Expected / Unusual buttons answer for. */
        val keys: List<String>,
    )

    fun build(notable: List<DayDeviation>): Summary? {
        val top = notable.sortedByDescending { it.score }.take(3)
        if (top.isEmpty()) return null
        val title = if (top.size == 1) top[0].title else "${top[0].title} (+${top.size - 1} more)"
        return Summary(title, top.map { "${it.title}: ${it.detail}" }, top.map { it.key })
    }
}
