package com.habitminer.analytics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One card of the Weekly Story. */
data class StoryCard(
    val kind: Kind,
    /** Small label above the headline. */
    val label: String,
    /** The one big thing: a duration, a count or a short phrase. */
    val headline: String,
    val body: String,
    /** Optional per-day values for a small bar strip (Mon…Sun order of the week shown). */
    val bars: List<Pair<String, Long>> = emptyList(),
) {
    enum class Kind { WEEK, PHONE_FREE, ROUTINE, CHANGE, SLEEP, MILESTONES, EXPERIMENT }
}

data class WeeklyStory(
    /** First and last day covered. */
    val from: LocalDate,
    val to: LocalDate,
    val cards: List<StoryCard>,
    /** Screen time per day for the month heatmap, oldest first (5 full weeks, Monday first). */
    val month: List<Pair<LocalDate, Long?>>,
)

/**
 * The Sunday recap: a handful of cards about the last seven days, written plainly and kindly,
 * ending with one small experiment to try. Built only from things HabitMiner already measures.
 */
object WeeklyStoryBuilder {
    fun build(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        nights: List<SleepEstimate>,
        naps: List<Pair<Long, Long>>,
        patterns: List<PatternGroup>,
        dailyTargetMinutes: Int?,
        useLess: Set<String>,
        today: LocalDate,
        now: Long,
        zone: ZoneId,
    ): WeeklyStory? {
        // The last seven full days; from Sunday 18:00 the week includes that Sunday.
        val to = if (today.dayOfWeek == DayOfWeek.SUNDAY && TimeUtil.hourOf(now, zone) >= 18) today else today.minusDays(1)
        val from = to.minusDays(6)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val byDay = sessions.groupBy { TimeUtil.dateOf(it.start, zone) }
        val totals = days.map { d -> d to (byDay[d]?.sumOf { it.durationMs } ?: 0L) }
        if (totals.count { it.second > 0 } < 3) return null
        val cards = mutableListOf<StoryCard>()
        val shortDay = { d: LocalDate -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

        // 1. The week in phone time.
        val withData = totals.filter { it.second > 0 }
        val avg = withData.sumOf { it.second } / withData.size
        val prevDays = days.map { it.minusDays(7) }
        val prevTotals = prevDays.mapNotNull { d -> byDay[d]?.sumOf { it.durationMs }?.takeIf { it > 0 } }
        val prevAvg = if (prevTotals.size >= 3) prevTotals.sum() / prevTotals.size else null
        cards +=
            StoryCard(
                StoryCard.Kind.WEEK,
                "Your week",
                "${Format.duration(avg)} a day",
                when {
                    prevAvg == null -> "Across ${withData.size} days with data."
                    avg < prevAvg -> "${Format.duration(prevAvg - avg)} a day less than the week before."
                    avg > prevAvg -> "${Format.duration(avg - prevAvg)} a day more than the week before."
                    else -> "About the same as the week before."
                } + (withData.minByOrNull { it.second }?.let { " Lightest day: ${shortDay(it.first)}." } ?: ""),
                bars = totals.map { shortDay(it.first) to it.second },
            )

        // 2. Phone-free best.
        val phoneFree =
            days.mapNotNull { d ->
                val wake = nights.firstOrNull { it.wakeDate == d }?.wakeTime ?: TimeUtil.at(d, 8, 0, zone)
                val end = minOf(nights.firstOrNull { it.wakeDate == d.plusDays(1) }?.sleepStart ?: TimeUtil.at(d, 23, 0, zone), TimeUtil.at(d.plusDays(1), 0, 0, zone), now)
                if (end <= wake) null else PhoneFree.longestToday(sessions, unlocks, wake, end, naps)?.let { d to it }
            }
        phoneFree.maxByOrNull { it.second.durationMs }?.let { (d, best) ->
            cards +=
                StoryCard(
                    StoryCard.Kind.PHONE_FREE,
                    "Longest phone-free stretch",
                    Format.duration(best.durationMs),
                    "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${Format.clock(best.start, zone)} to ${Format.clock(best.end, zone)}, awake and not using the phone.",
                )
        }

        // 3. A routine you kept.
        patterns.filter { TimeUtil.dateOf(it.lastSeen, zone) >= from }.maxByOrNull { it.bestConfidence * it.totalOccurrences }?.let { g ->
            cards +=
                StoryCard(
                    StoryCard.Kind.ROUTINE,
                    "A routine you kept",
                    g.sequence,
                    "${g.whenText}. ${g.evidenceText}.",
                )
        }

        // 4. Biggest change by app.
        val thisWeek = sessions.filter { TimeUtil.dateOf(it.start, zone) in days }
        val lastWeek = sessions.filter { TimeUtil.dateOf(it.start, zone) in prevDays }
        if (prevTotals.size >= 3) {
            val names = (thisWeek + lastWeek).associate { it.appName to it.packageName }
            val now7 = thisWeek.groupBy { it.appName }.mapValues { (_, v) -> v.sumOf { it.durationMs } / 7 }
            val prev7 = lastWeek.groupBy { it.appName }.mapValues { (_, v) -> v.sumOf { it.durationMs } / 7 }
            val change = (now7.keys + prev7.keys).map { app -> app to ((now7[app] ?: 0L) - (prev7[app] ?: 0L)) }.maxByOrNull { kotlin.math.abs(it.second) }
            if (change != null && kotlin.math.abs(change.second) >= 10 * TimeUtil.MINUTE) {
                val (app, delta) = change
                val goal = names[app]?.let { it in useLess } == true
                cards +=
                    StoryCard(
                        StoryCard.Kind.CHANGE,
                        "Biggest change",
                        "$app ${if (delta < 0) "−" else "+"}${Format.duration(kotlin.math.abs(delta))} a day",
                        "${Format.duration(now7[app] ?: 0L)} a day this week, ${Format.duration(prev7[app] ?: 0L)} the week before." +
                            if (goal && delta < 0) " That's one of the apps you wanted to use less." else "",
                    )
            }
        }

        // 5. Sleep.
        val weekNights = nights.filter { it.wakeDate in days }
        if (weekNights.size >= 3) {
            val avgNight = weekNights.sumOf { it.durationMs } / weekNights.size
            val beds = weekNights.map { SleepDetector.bedtimeMinutes(it.sleepStart, zone) }
            val spread = beds.maxOrNull()!! - beds.minOrNull()!!
            cards +=
                StoryCard(
                    StoryCard.Kind.SLEEP,
                    "Sleep",
                    "${Format.duration(avgNight)} a night",
                    "Usually asleep around ${Format.clockFromMinutes(beds.average().toInt())}" +
                        if (spread <= 60) ", within an hour each night. Steady." else ", varying by ${Format.duration(spread * TimeUtil.MINUTE)} across the week.",
                )
        }

        // 6. Milestones.
        val milestones = mutableListOf<String>()
        dailyTargetMinutes?.let { t ->
            val under = withData.count { it.second <= t * TimeUtil.MINUTE }
            if (under > 0) milestones += "$under of ${withData.size} days under your ${Format.duration(t * TimeUtil.MINUTE)} target"
        }
        val earlier = sessions.filter { TimeUtil.dateOf(it.start, zone).isBefore(from) }.groupBy { TimeUtil.dateOf(it.start, zone) }.mapValues { (_, v) -> v.sumOf { it.durationMs } }
        val lightest = withData.minByOrNull { it.second }
        if (lightest != null && earlier.size >= 14 && lightest.second < (earlier.values.minOrNull() ?: Long.MAX_VALUE)) {
            milestones += "${lightest.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} was your lightest day in ${earlier.size + 7} days"
        }
        val unlockCounts = days.map { d -> unlocks.count { TimeUtil.dateOf(it, zone) == d } }.filter { it > 0 }
        if (unlockCounts.size >= 3) milestones += "About ${unlockCounts.sum() / unlockCounts.size} pickups a day"
        if (milestones.isNotEmpty()) {
            cards += StoryCard(StoryCard.Kind.MILESTONES, "This week", milestones.first(), milestones.drop(1).joinToString(". ").let { if (it.isEmpty()) "" else "$it." })
        }

        // 7. One small experiment, from the week's own data.
        cards += experiment(thisWeek, useLess, avg, zone)

        // Month heatmap: five full weeks ending this week, Monday first.
        val lastMonday = to.minusDays((to.dayOfWeek.value - 1).toLong())
        val start = lastMonday.minusWeeks(4)
        val month =
            generateSequence(start) { it.plusDays(1) }.take(35).map { d ->
                d to (if (d.isAfter(today)) null else byDay[d]?.sumOf { it.durationMs } ?: if (d.isBefore(sessions.minOfOrNull { TimeUtil.dateOf(it.start, zone) } ?: today)) null else 0L)
            }.toList()
        return WeeklyStory(from, to, cards, month)
    }

    private fun experiment(
        week: List<UsageSession>,
        useLess: Set<String>,
        avgPerDay: Long,
        zone: ZoneId,
    ): StoryCard {
        val late = week.filter { TimeUtil.hourOf(it.start, zone) >= 23 || TimeUtil.hourOf(it.start, zone) < 4 }
        val lateDays = late.map { TimeUtil.dateOf(it.start, zone) }.distinct().size
        val lessApp =
            week.filter { it.packageName in useLess }.groupBy { it.appName }.maxByOrNull { (_, v) -> v.sumOf { it.durationMs } }?.key
        val (headline, body) =
            when {
                lateDays >= 3 ->
                    "Phone down by 23:00, twice" to
                        "You used the phone after 23:00 on $lateDays nights. Try leaving it out of reach by 23:00 on two nights and see how mornings feel."
                lessApp != null ->
                    "$lessApp after 10:00 only" to
                        "On two days, keep $lessApp closed until 10:00. Mornings tend to set the tone for the rest of the day."
                avgPerDay >= 4 * TimeUtil.HOUR ->
                    "One phone-free hour a day" to
                        "Pick one hour each day, like a meal or a walk, and leave the phone in another room. Watch how the phone-free card changes."
                else ->
                    "Notice your first pickup" to
                        "For two mornings, notice what makes you pick up the phone first. No need to change it, just notice."
            }
        return StoryCard(StoryCard.Kind.EXPERIMENT, "One small experiment", headline, body)
    }
}
