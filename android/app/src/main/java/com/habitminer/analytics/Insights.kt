package com.habitminer.analytics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** Topics an insight can be about; each can be switched off in Settings. */
enum class InsightFamily(
    val label: String,
    val description: String,
) {
    SCREEN_TIME("Screen time", "A clearly quieter or busier day than usual"),
    APPS("Apps", "An app you used much more or less than usual"),
    UNLOCKS("Pickups", "Many more or fewer unlocks than usual"),
    LATE_NIGHT("Late night", "Phone use late at night, unlike your usual"),
    SLEEP("Sleep", "Bedtime, wake-up or sleep length unlike your usual"),
    RECORDS("Personal bests", "Your longest phone-free stretch or lightest day in weeks"),
    ROUTINES("Routines", "A routine that stopped, or app habits that changed"),
    WEEK("Your week", "Last week against the week before, on Mondays"),
    ;

    companion object {
        fun of(name: String?): InsightFamily? = entries.firstOrNull { it.name == name }
    }
}

/** One thing worth knowing about a day, built only from your own data. */
data class Insight(
    val family: InsightFamily,
    /** Stable for the day and topic, e.g. "2026-10-07|SCREEN_TIME|LESS_USE|ALL". */
    val key: String,
    val date: LocalDate,
    val title: String,
    val body: String,
    /** Why it's shown: what it was compared with. */
    val why: String,
    /** How unusual it is, roughly in robust standard deviations. */
    val effect: Float,
    /** Where tapping it goes: "changes", "sleep", "trends", "story" or "app:<package>". */
    val open: String,
)

/**
 * Turns the day's analysis into insight candidates. Every candidate passes the same gates:
 * clearly unusual for you (robust z of 2 or more, from [DeviationFinder], or a personal best
 * over at least two weeks), on a day with enough data, and not explained by a period you
 * labelled. On ordinary days there are none, and the app says nothing.
 */
object InsightEngine {
    const val MIN_EFFECT = 2.0f

    /** A day needs this share of its waking hours recorded before it is compared. */
    const val MIN_COVERAGE = 0.8f

    /** Records need this many earlier days to compare with. */
    private const val RECORD_DAYS = 14

    fun candidates(
        report: DeviationReport,
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        nights: List<SleepEstimate>,
        naps: List<Pair<Long, Long>>,
        patterns: List<PatternGroup>,
        week: WeekComparison?,
        driftAt: Long?,
        coverage: Map<LocalDate, Float>,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
    ): List<Insight> {
        val yesterday = today.minusDays(1)
        fun covered(d: LocalDate) = (coverage[d] ?: 1f) >= MIN_COVERAGE
        val out = mutableListOf<Insight>()

        // 1. Differences from your usual days, today so far and yesterday.
        report.days.filter { (it.date == today || it.date == yesterday) && it.explainedBy == null && it.score >= MIN_EFFECT && covered(it.date) }
            .forEach { d ->
                val family =
                    when (d.kind) {
                        DeviationKind.LESS_USE, DeviationKind.MORE_USE -> InsightFamily.SCREEN_TIME
                        DeviationKind.LATE_NIGHT -> InsightFamily.LATE_NIGHT
                        DeviationKind.APP_DROP, DeviationKind.APP_SPIKE, DeviationKind.APP_NEW -> InsightFamily.APPS
                        DeviationKind.MORE_UNLOCKS, DeviationKind.FEWER_UNLOCKS -> InsightFamily.UNLOCKS
                        else -> InsightFamily.SLEEP
                    }
                val day = if (d.date == today) "Today" else "Yesterday"
                out +=
                    Insight(
                        family = family,
                        key = "${d.date}|${family.name}|${d.kind.name}|${d.subject}",
                        date = d.date,
                        title = if (d.date == today) d.title else "$day: ${d.title.replaceFirstChar { it.lowercase() }}",
                        body = d.detail,
                        why = "Compared with your usual ${if (TimeUtil.isWeekend(d.date)) "weekend days" else "weekdays"} over the last four weeks" +
                            if (d.partialDay) ", up to this time of day." else ".",
                        effect = d.score,
                        open = if (family == InsightFamily.SLEEP) "sleep" else "changes",
                    )
            }

        // 2. Personal bests over the last four weeks.
        out += records(sessions, unlocks, nights, naps, coverage, today, now, zone)

        // 3. A strong routine that stopped, and a shift in which apps follow which.
        patterns.filter { it.bestConfidence >= 0.8f && it.totalOccurrences >= 8 }
            .filter { now - it.lastSeen in (3 * TimeUtil.DAY)..(10 * TimeUtil.DAY) }
            .maxByOrNull { it.totalOccurrences }
            ?.let { g ->
                out +=
                    Insight(
                        family = InsightFamily.ROUTINES,
                        key = "$today|ROUTINES|stopped|${g.sequence}",
                        date = today,
                        title = "A routine has paused: ${g.sequence}",
                        body = "Usually ${g.whenText.lowercase()}, last seen ${(now - g.lastSeen) / TimeUtil.DAY} days ago.",
                        why = "It was one of your most regular routines (${g.evidenceText.replaceFirstChar { it.lowercase() }}).",
                        effect = 2.2f,
                        open = "trends",
                    )
            }
        driftAt?.takeIf { now - it in 0..(2 * TimeUtil.DAY) }?.let { at ->
            out +=
                Insight(
                    family = InsightFamily.ROUTINES,
                    key = "${TimeUtil.dateOf(at, zone)}|ROUTINES|drift",
                    date = TimeUtil.dateOf(at, zone),
                    title = "Your app habits shifted",
                    body = "Since about ${Format.clock(at, zone)} ${if (TimeUtil.dateOf(at, zone) == today) "today" else "yesterday"}, the apps you open after each other have changed.",
                    why = "HabitMiner's guesses about your next app suddenly got more or less accurate, which usually means a change in routine.",
                    effect = 2.0f,
                    open = "trends",
                )
        }

        // 4. Mondays: the week that ended against the one before.
        if (today.dayOfWeek == DayOfWeek.MONDAY && week != null && week.currentDays >= 5 && week.previousDays >= 5 && week.previousPerDayMs > 0) {
            val change = week.deltaMs.toFloat() / week.previousPerDayMs
            if (kotlin.math.abs(change) >= 0.2f && kotlin.math.abs(week.deltaMs) >= 30 * TimeUtil.MINUTE) {
                val less = week.deltaMs < 0
                out +=
                    Insight(
                        family = InsightFamily.WEEK,
                        key = "$today|WEEK|${if (less) "less" else "more"}",
                        date = today,
                        title = "Last week: ${Format.duration(kotlin.math.abs(week.deltaMs))} ${if (less) "less" else "more"} a day",
                        body = "About ${Format.duration(week.currentPerDayMs)} a day, against ${Format.duration(week.previousPerDayMs)} the week before" +
                            (week.biggestMovers.firstOrNull()?.let { ". Biggest change: ${it.name}." } ?: "."),
                        why = "Per-day averages over the days with data in each week.",
                        // A 20% change counts as notable; 40% as very notable.
                        effect = (2f + (kotlin.math.abs(change) - 0.2f) * 5f).coerceAtMost(4f),
                        open = "story",
                    )
            }
        }
        return out.sortedByDescending { it.effect }
    }

    private fun records(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        nights: List<SleepEstimate>,
        naps: List<Pair<Long, Long>>,
        coverage: Map<LocalDate, Float>,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val firstDay = sessions.minOfOrNull { it.start }?.let { TimeUtil.dateOf(it, zone).plusDays(1) } ?: return out
        val days = generateSequence(today.minusDays(28)) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.filter { !it.isBefore(firstDay) }.toList()
        if (days.size < RECORD_DAYS) return out
        fun covered(d: LocalDate) = (coverage[d] ?: 1f) >= MIN_COVERAGE

        // Longest phone-free stretch while awake, per day.
        fun phoneFree(d: LocalDate): PhoneFreeStretch? {
            val wake = nights.firstOrNull { it.wakeDate == d }?.wakeTime ?: TimeUtil.at(d, 8, 0, zone)
            val nextNight = nights.firstOrNull { it.wakeDate == d.plusDays(1) }?.sleepStart
            val end = minOf(nextNight ?: TimeUtil.at(d, 23, 0, zone), TimeUtil.at(d.plusDays(1), 0, 0, zone), now)
            if (end <= wake) return null
            // Only stretches that have ended count: a stretch still going on isn't a result yet.
            val stretch = PhoneFree.longestToday(sessions, unlocks, wake, end, naps) ?: return null
            return stretch.takeIf { it.end < now - TimeUtil.MINUTE }
        }
        val history = days.filter(::covered).mapNotNull { d -> phoneFree(d)?.durationMs }
        val yesterday = today.minusDays(1)
        for (d in listOf(today, yesterday)) {
            if (!covered(d)) continue
            val best = phoneFree(d) ?: continue
            val before = days.filter { it.isBefore(d) && covered(it) }.mapNotNull { phoneFree(it)?.durationMs }
            if (before.size >= RECORD_DAYS && best.durationMs >= 2 * TimeUtil.HOUR && best.durationMs > before.max()) {
                out +=
                    Insight(
                        family = InsightFamily.RECORDS,
                        key = "$d|RECORDS|phone_free",
                        date = d,
                        title = "${Format.duration(best.durationMs)} phone-free, your longest in ${before.size} days",
                        body = "From ${Format.clock(best.start, zone)} to ${Format.clock(best.end, zone)}${if (d == today) " today" else " yesterday"}, without unlocking.",
                        why = "Your longest stretch without using the phone while awake, against each day of the last four weeks.",
                        effect = 2.5f,
                        open = "trends",
                    )
                break
            }
        }
        if (history.isEmpty()) return out

        // Lightest full day in four weeks (yesterday only; today isn't over).
        val totals = sessions.groupBy { TimeUtil.dateOf(it.start, zone) }.mapValues { (_, v) -> v.sumOf { it.durationMs } }
        val y = totals[yesterday]
        val earlier = days.filter { it.isBefore(yesterday) && covered(it) }.mapNotNull { totals[it] }
        if (y != null && covered(yesterday) && earlier.size >= RECORD_DAYS && y < earlier.min() && y >= 15 * TimeUtil.MINUTE) {
            out +=
                Insight(
                    family = InsightFamily.RECORDS,
                    key = "$yesterday|RECORDS|lightest_day",
                    date = yesterday,
                    title = "Yesterday was your lightest phone day in ${earlier.size + 1} days",
                    body = "${Format.duration(y)} in total, against a usual ${Format.duration(earlier.sorted()[earlier.size / 2])}.",
                    why = "Total screen time per day over the last four weeks.",
                    effect = 2.5f,
                    open = "trends",
                )
        }
        return out
    }
}

/** What happened to an insight before: when it was shown and what you said about it. */
data class InsightHistory(
    val date: LocalDate,
    val family: InsightFamily,
    val key: String,
    /** "useful", "fewer" or null. */
    val feedback: String?,
)

/**
 * Picks at most one insight a day.
 *
 * Score = effect × rarity × recency × a Thompson-sampled "is this topic useful to you" factor
 * (Beta from your Useful / Fewer-like-this answers). A topic shown yesterday is never picked
 * again today. If nothing scores above [SEND_NOTHING] the answer is nothing: "provide
 * nothing" is a deliberate option, not a failure.
 */
object InsightPicker {
    const val SEND_NOTHING = 1.6

    data class Pick(
        val insight: Insight,
        val score: Double,
        /** Estimated chance this insight would be picked, from repeated sampling (for the export). */
        val probability: Double,
    )

    fun pick(
        candidates: List<Insight>,
        history: List<InsightHistory>,
        enabled: Set<InsightFamily>,
        today: LocalDate,
        random: Random = Random.Default,
        simulations: Int = 200,
    ): Pick? {
        val shownKeys = history.map { it.key }.toSet()
        val yesterdayFamilies = history.filter { it.date == today.minusDays(1) }.map { it.family }.toSet()
        val eligible =
            candidates.filter { it.family in enabled && it.effect >= InsightEngine.MIN_EFFECT && it.key !in shownKeys && it.family !in yesterdayFamilies }
        if (eligible.isEmpty()) return null

        val stats =
            InsightFamily.entries.associateWith { f ->
                val h = history.filter { it.family == f }
                Triple(h.count { it.feedback == "useful" }, h.count { it.feedback == "fewer" }, h.count { !it.date.isBefore(today.minusDays(14)) })
            }

        fun base(i: Insight): Double {
            val (_, _, recentCount) = stats.getValue(i.family)
            val rarity = 1.0 / (1.0 + 0.3 * recentCount)
            val lastShown = history.filter { it.family == i.family }.maxOfOrNull { it.date }
            val recency = if (lastShown != null && !lastShown.isBefore(today.minusDays(3))) 0.6 else 1.0
            return i.effect * rarity * recency
        }

        fun sample(i: Insight): Double {
            val (useful, fewer, _) = stats.getValue(i.family)
            // Beta(1 + useful, 1 + fewer) has mean 0.5 with no answers; scale so that's 1.
            return 2.0 * betaSample(1.0 + useful, 1.0 + fewer, random)
        }

        fun once(): Insight? {
            val scored = eligible.map { it to base(it) * sample(it) }
            val best = scored.maxByOrNull { it.second } ?: return null
            return best.first.takeIf { best.second >= SEND_NOTHING }
        }
        val chosen = once() ?: return null
        val wins = (0 until simulations).count { once()?.key == chosen.key }
        return Pick(chosen, base(chosen), wins.toDouble() / simulations)
    }

    /** Beta(a, b) from two Gamma draws (Marsaglia–Tsang). */
    internal fun betaSample(
        a: Double,
        b: Double,
        random: Random,
    ): Double {
        val x = gamma(a, random)
        val y = gamma(b, random)
        return if (x + y <= 0.0) 0.5 else x / (x + y)
    }

    private fun gamma(
        shape: Double,
        random: Random,
    ): Double {
        if (shape < 1.0) return gamma(shape + 1.0, random) * random.nextDouble().pow(1.0 / shape)
        val d = shape - 1.0 / 3.0
        val c = 1.0 / kotlin.math.sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian(random)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(random: Random): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/** How often insights may arrive as notifications. They always appear in the app (unless off). */
enum class InsightFrequency(
    val label: String,
    val description: String,
) {
    OFF("Off", "No insights, in the app or as notifications"),
    STANDOUT("When it stands out", "A notification only for very unusual days and personal bests"),
    DAILY("Daily", "A notification whenever there's an insight, at most one a day"),
    WEEKLY("Weekly", "No daily notifications; see insights in the app and the Weekly Story"),
    ;

    /** Whether today's pick is worth a notification at this setting. */
    fun notifies(effect: Float): Boolean =
        when (this) {
            OFF, WEEKLY -> false
            STANDOUT -> effect >= DeviationFinder.NOTABLE_SCORE
            DAILY -> true
        }

    companion object {
        fun of(name: String?): InsightFrequency = entries.firstOrNull { it.name == name } ?: STANDOUT
    }
}

/**
 * Share of a day's waking hours (08:00–23:00, or until now) with data: hours not covered by a
 * phone shutdown or by HabitMiner being paused.
 */
object DayCoverage {
    fun compute(
        gaps: List<Pair<Long, Long>>,
        days: List<LocalDate>,
        now: Long,
        zone: ZoneId,
    ): Map<LocalDate, Float> =
        days.associateWith { d ->
            val from = TimeUtil.at(d, 8, 0, zone)
            val to = minOf(TimeUtil.at(d, 23, 0, zone), now)
            if (to <= from) {
                1f
            } else {
                val missing = gaps.sumOf { (a, b) -> (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L) }
                (1f - missing.toFloat() / (to - from)).coerceIn(0f, 1f)
            }
        }

    /** Gaps from shutdown/startup and pause/resume events, as (start, end). */
    fun gaps(
        events: List<Pair<Long, Boolean>>,
        now: Long,
    ): List<Pair<Long, Long>> {
        // Each event is (time, gapStarts): shutdown/pause start a gap, startup/resume end it.
        val out = mutableListOf<Pair<Long, Long>>()
        var since: Long? = null
        for ((t, starts) in events.sortedBy { it.first }) {
            if (starts && since == null) since = t
            if (!starts && since != null) {
                out += since to t
                since = null
            }
        }
        since?.let { out += it to now }
        return out
    }
}

/** Quiet hours as minutes of the day; may wrap past midnight (22:00–08:00). */
data class QuietHours(
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 8 * 60,
) {
    operator fun contains(minuteOfDay: Int): Boolean =
        when {
            startMinute == endMinute -> false
            startMinute < endMinute -> minuteOfDay in startMinute until endMinute
            else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
}
