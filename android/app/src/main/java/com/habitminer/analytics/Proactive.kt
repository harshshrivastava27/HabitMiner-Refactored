package com.habitminer.analytics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** What kind of prompt went out; stored so the daily budget can be enforced. */
enum class PromptKind { CHECK_IN, NUDGE, DIGEST, NAP, PERIOD, DEVIATIONS }

data class SentPrompt(
    val kind: PromptKind,
    val time: Long,
)

data class Nudge(
    val key: String,
    val title: String,
    val body: String,
)

/**
 * Rules for check-ins and nudges.
 *
 * - At most [MAX_PROMPTS_PER_DAY] check-ins + nudges per day (the weekly digest is separate).
 * - Check-ins only between 09:00 and 22:00, one per window (09–13, 13–17, 17–22),
 *   only while the phone is in use, and at least 2 hours apart.
 * - Nudges fire on a long continuous leisure stretch (late at night: 25 min; daytime: 60 min),
 *   at most one per 2 hours.
 * - "Were you asleep?" is asked once per likely nap, within 3 hours of picking the phone up.
 * - "What's different?" is asked at most once a day while an unexplained routine change lasts.
 * - The evening summary goes out once, between 21:00 and 23:00, on days with something notable.
 * All of these share the daily limit with check-ins and nudges.
 */
object PromptPolicy {
    const val MAX_PROMPTS_PER_DAY = 3
    private const val MIN_GAP_MS = 2 * TimeUtil.HOUR
    private val checkInWindows = listOf(9 until 13, 13 until 17, 17 until 22)

    fun promptsToday(
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): List<SentPrompt> {
        val start = TimeUtil.startOfDay(now, zone)
        return sent.filter { it.time >= start && it.kind != PromptKind.DIGEST }
    }

    fun canSendCheckIn(
        enabled: Boolean,
        screenOn: Boolean,
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (!enabled || !screenOn) return false
        val hour = TimeUtil.hourOf(now, zone)
        val window = checkInWindows.firstOrNull { hour in it } ?: return false
        val today = promptsToday(sent, now, zone)
        if (today.size >= MAX_PROMPTS_PER_DAY) return false
        if (today.any { now - it.time < MIN_GAP_MS }) return false
        val windowStart = TimeUtil.at(TimeUtil.dateOf(now, zone), window.first, 0, zone)
        return today.none { it.kind == PromptKind.CHECK_IN && it.time >= windowStart }
    }

    /**
     * Length of the continuous phone-use stretch ending now: sessions chained with gaps of at
     * most 2 minutes, where the last one ended (or is still running) within 3 minutes of [now].
     */
    fun continuousStretch(
        sessions: List<UsageSession>,
        now: Long,
    ): List<UsageSession> {
        val sorted = sessions.filter { it.start <= now }.sortedByDescending { it.end }
        val latest = sorted.firstOrNull() ?: return emptyList()
        if (now - latest.end > 3 * TimeUtil.MINUTE) return emptyList()
        val chain = mutableListOf(latest)
        var chainStart = latest.start
        for (s in sorted.drop(1)) {
            if (chainStart - s.end > 2 * TimeUtil.MINUTE) break
            chain.add(s)
            chainStart = minOf(chainStart, s.start)
        }
        return chain
    }

    fun nudgeFor(
        enabled: Boolean,
        sessions: List<UsageSession>,
        latestSample: ContextSample?,
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): Nudge? {
        if (!enabled) return null
        val today = promptsToday(sent, now, zone)
        if (today.size >= MAX_PROMPTS_PER_DAY) return null
        if (sent.any { it.kind == PromptKind.NUDGE && now - it.time < MIN_GAP_MS }) return null

        val chain = continuousStretch(sessions, now)
        if (chain.isEmpty()) return null
        val stretchMs = now - chain.minOf { it.start }
        val leisureMs = chain.filter { it.category.isLeisure }.sumOf { it.durationMs }
        val totalMs = chain.sumOf { it.durationMs }.coerceAtLeast(1)
        if (leisureMs.toFloat() / totalMs < 0.6f) return null
        val topApp = chain.groupBy { it.appName }.maxByOrNull { e -> e.value.sumOf { it.durationMs } }?.key ?: "your phone"

        val hour = TimeUtil.hourOf(now, zone)
        val lateNight = hour >= 23 || hour < 5
        val dark = ContextLabels.light(latestSample?.lightLux)?.let { it == Light.DARK }
        return when {
            lateNight && stretchMs >= 25 * TimeUtil.MINUTE ->
                Nudge(
                    key = "late_night",
                    title = "Late night: ${Format.duration(stretchMs)} on $topApp",
                    body =
                        if (dark == true) {
                            "You've been on your phone in the dark for a while. Maybe time to wind down?"
                        } else {
                            "You've been on your phone for a while. Maybe time to wind down?"
                        },
                )
            !lateNight && stretchMs >= 60 * TimeUtil.MINUTE ->
                Nudge(
                    key = "long_stretch",
                    title = "${Format.duration(stretchMs)} straight on $topApp",
                    body = "A short break to stretch or look away from the screen could help.",
                )
            else -> null
        }
    }

    fun canAskNap(
        enabled: Boolean,
        screenOn: Boolean,
        nap: NapCandidate?,
        answered: Set<String>,
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (!enabled || !screenOn || nap == null) return false
        if (nap.confidence == Confidence.LOW || nap.key in answered) return false
        if (now < nap.end || now - nap.end > 3 * TimeUtil.HOUR) return false
        if (sent.any { it.kind == PromptKind.NAP && it.time >= nap.end }) return false
        return promptsToday(sent, now, zone).size < MAX_PROMPTS_PER_DAY
    }

    fun canAskPeriod(
        enabled: Boolean,
        screenOn: Boolean,
        shift: RoutineShift?,
        answered: Set<String>,
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (!enabled || !screenOn || shift == null || shift.label != null || shift.key in answered) return false
        if (TimeUtil.hourOf(now, zone) !in 9 until 22) return false
        if (sent.any { it.kind == PromptKind.PERIOD && now - it.time < TimeUtil.DAY }) return false
        val today = promptsToday(sent, now, zone)
        if (today.size >= MAX_PROMPTS_PER_DAY) return false
        return today.none { now - it.time < 30 * TimeUtil.MINUTE }
    }

    fun canSendDeviationSummary(
        enabled: Boolean,
        notableToday: List<DayDeviation>,
        sent: List<SentPrompt>,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (!enabled || notableToday.isEmpty()) return false
        if (TimeUtil.hourOf(now, zone) !in 21 until 23) return false
        val today = promptsToday(sent, now, zone)
        if (today.any { it.kind == PromptKind.DEVIATIONS }) return false
        return today.size < MAX_PROMPTS_PER_DAY
    }

    /** Weekly digest goes out once per ISO week, on Sunday from 19:00. */
    fun shouldSendDigest(
        enabled: Boolean,
        lastDigestAt: Long?,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (!enabled) return false
        val z = TimeUtil.zoned(now, zone)
        if (z.dayOfWeek != DayOfWeek.SUNDAY || z.hour < 19) return false
        if (lastDigestAt == null) return true
        val last = TimeUtil.zoned(lastDigestAt, zone)
        return last.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) ||
            last.get(IsoFields.WEEK_BASED_YEAR) != z.get(IsoFields.WEEK_BASED_YEAR)
    }
}

/** Builds the Sunday summary notification text. */
object DigestBuilder {
    data class Digest(val title: String, val lines: List<String>)

    fun build(
        week: WeekComparison?,
        thisWeekSessions: List<UsageSession>,
        days: Int,
        sleep: SleepSummary?,
        pickups: PickupStats?,
    ): Digest {
        val lines = mutableListOf<String>()
        val perDay = if (days > 0) thisWeekSessions.sumOf { it.durationMs } / days else 0L
        val title =
            if (week != null && kotlin.math.abs(week.deltaMs) >= 5 * TimeUtil.MINUTE) {
                val dir = if (week.deltaMs < 0) "less" else "more"
                "Your week: ${Format.duration(week.currentPerDayMs)}/day (${Format.duration(kotlin.math.abs(week.deltaMs))} $dir)"
            } else {
                "Your week: ${Format.duration(perDay)} a day on your phone"
            }
        SessionGrouper.byApp(thisWeekSessions).firstOrNull()?.let { (app, ms) ->
            lines.add("Top app: $app (${Format.duration(ms / days.coerceAtLeast(1))}/day)")
        }
        week?.biggestMovers?.firstOrNull()?.let {
            val dir = if (it.deltaMs > 0) "up" else "down"
            lines.add("Biggest change: ${it.name} $dir ${Format.duration(kotlin.math.abs(it.deltaMs))}/day")
        }
        sleep?.let {
            lines.add("Sleep: about ${Format.duration(it.avgDurationMs)} a night, usually from ${Format.clockFromMinutes(it.avgBedtimeMinutes)}")
        }
        pickups?.takeIf { it.total > 0 }?.let {
            lines.add("${Format.percent(it.notificationShare)} of pickups followed a notification")
        }
        return Digest(title, lines)
    }
}

// ---------------------------------------------------------------------------------------
// Battery-aware sensing
// ---------------------------------------------------------------------------------------

enum class SensingMode(val label: String, val intervalMs: Long, val explanation: String) {
    ACTIVE("Active", 5 * TimeUtil.MINUTE, "You're moving with the screen on, so context changes quickly"),
    NORMAL("Normal", 15 * TimeUtil.MINUTE, "Screen on or charging"),
    IDLE("Idle", 30 * TimeUtil.MINUTE, "Screen off, so sampling less to save battery"),
    LOW_BATTERY("Battery saver", 30 * TimeUtil.MINUTE, "Battery saver on or battery below 20%, so sampling less often (sensors pause below 15%)"),
}

object SensingPolicy {
    fun choose(
        screenOn: Boolean,
        recentlyMoving: Boolean?,
        charging: Boolean,
        batteryLevel: Int,
        powerSave: Boolean = false,
    ): SensingMode =
        when {
            !charging && (powerSave || batteryLevel in 0 until 20) -> SensingMode.LOW_BATTERY
            screenOn && recentlyMoving == true -> SensingMode.ACTIVE
            screenOn || charging -> SensingMode.NORMAL
            else -> SensingMode.IDLE
        }
}

// ---------------------------------------------------------------------------------------
// Wi-Fi places
// ---------------------------------------------------------------------------------------

object PlaceInference {
    /**
     * Suggests names for Wi-Fi places: the place seen most at night (00–06) becomes "Home",
     * the place seen most on weekday daytime (09–17) becomes "Campus", others are numbered.
     */
    fun suggestNames(
        samples: List<ContextSample>,
        zone: ZoneId,
    ): Map<String, String> {
        val placed = samples.filter { it.place != null }
        if (placed.isEmpty()) return emptyMap()
        val night = mutableMapOf<String, Int>()
        val day = mutableMapOf<String, Int>()
        val all = mutableMapOf<String, Int>()
        for (s in placed) {
            val p = s.place!!
            all.merge(p, 1, Int::plus)
            val z = TimeUtil.zoned(s.timestamp, zone)
            if (z.hour < 6) night.merge(p, 1, Int::plus)
            if (z.hour in 9 until 17 && !TimeUtil.isWeekend(z.toLocalDate())) day.merge(p, 1, Int::plus)
        }
        val names = mutableMapOf<String, String>()
        night.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { names[it.key] = "Home" }
        day.filterKeys { it !in names }.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { names[it.key] = "Campus" }
        var n = 1
        all.entries.sortedByDescending { it.value }.forEach { (p, _) ->
            if (p !in names) names[p] = "Place ${n++}"
        }
        return names
    }
}

/** Days between two dates, inclusive of both; helper for "this week" ranges. */
fun daysInclusive(
    from: LocalDate,
    to: LocalDate,
): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
