package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId

enum class Confidence(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** A short wake-up inside a sleep, e.g. switching off an alarm or checking the time. */
data class BriefWake(
    val start: Long,
    val end: Long,
    /** True when the clock app was used (an alarm or snooze). */
    val alarm: Boolean,
)

/** Where a night's times come from. */
enum class SleepSource {
    /** Estimated from the phone alone. */
    PHONE,

    /** Estimated, then shifted by what your own corrections showed (see [SleepCorrections]). */
    ADJUSTED,

    /** Times you set yourself. */
    YOU,
}

/**
 * Signals that sharpen the estimate when they were recorded (Extended logs them; older data
 * and imports from the original HabitMiner don't have them, and everything still works).
 */
data class NightSignals(
    /** The screen turned on without an unlock and not because of a notification: a glance. */
    val glances: List<Long> = emptyList(),
    /** Do Not Disturb or bedtime mode on, as (start, end). */
    val quietHours: List<Pair<Long, Long>> = emptyList(),
    /** Alarm times the clock app had set. */
    val alarms: List<Long> = emptyList(),
) {
    companion object {
        /** A screen-on this soon after a notification was the notification lighting it up. */
        private const val NOTIFICATION_WAKE_MS = 15_000L

        /** A screen-on followed by an unlock this soon was an unlock, not a glance. */
        private const val UNLOCK_AFTER_MS = 60_000L

        /**
         * Builds the signals from the recorded events.
         *
         * @param screenOn times the screen turned on
         * @param unlocks unlock times
         * @param notifications notification times
         * @param quietModes Do Not Disturb changes as (time, on)
         * @param nextAlarms next-alarm changes as (time recorded, alarm time or null when none)
         */
        fun from(
            screenOn: List<Long>,
            unlocks: List<Long>,
            notifications: List<Long>,
            quietModes: List<Pair<Long, Boolean>>,
            nextAlarms: List<Pair<Long, Long?>>,
            now: Long,
        ): NightSignals {
            val unlockSorted = unlocks.sorted()
            val notesSorted = notifications.sorted()
            val glances =
                screenOn.sorted().filter { t ->
                    // First unlock at or after t - 2 s, last notification at or before t.
                    val u = firstAtOrAfter(unlockSorted, t - 2_000L)
                    val unlocked = u < unlockSorted.size && unlockSorted[u] - t <= UNLOCK_AFTER_MS
                    val n = firstAtOrAfter(notesSorted, t + 1) - 1
                    val byNotification = n >= 0 && t - notesSorted[n] <= NOTIFICATION_WAKE_MS
                    !unlocked && !byNotification
                }
            val quiet = mutableListOf<Pair<Long, Long>>()
            var onSince: Long? = null
            for ((t, on) in quietModes.sortedBy { it.first }) {
                if (on && onSince == null) onSince = t
                if (!on && onSince != null) {
                    quiet += onSince to t
                    onSince = null
                }
            }
            onSince?.let { quiet += it to now }
            // An alarm counts if it was still set when its time came.
            val records = nextAlarms.sortedBy { it.first }
            val alarms =
                records.mapIndexedNotNull { i, (recorded, at) ->
                    if (at == null || at <= recorded) return@mapIndexedNotNull null
                    val replacedAt = records.getOrNull(i + 1)?.first
                    if (at > now || (replacedAt != null && replacedAt < at - 60_000L)) null else at
                }.distinct()
            return NightSignals(glances, quiet, alarms)
        }

        /** Index of the first value >= [t] in a sorted list (size when none). */
        private fun firstAtOrAfter(
            sorted: List<Long>,
            t: Long,
        ): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < t) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/** Your own times for the night that ended on [wakeDate]; [notSleep] means "that wasn't sleep". */
data class SleepFix(
    val wakeDate: LocalDate,
    val start: Long,
    val end: Long,
    val notSleep: Boolean = false,
)

/** One night's estimated sleep, keyed by the date the person woke up. */
data class SleepEstimate(
    val wakeDate: LocalDate,
    val sleepStart: Long,
    val wakeTime: Long,
    val confidence: Confidence,
    /** Phone use in the hour before falling asleep. */
    val preSleepUseMs: Long,
    /** Share of that pre-sleep use that happened in the dark, when light readings exist. */
    val preSleepDarkShare: Float?,
    val lastAppBeforeSleep: String?,
    val firstAppAfterWake: String?,
    /** Short wake-ups that didn't end the night (alarm, time check, a quick message). */
    val briefWakes: List<BriefWake> = emptyList(),
    /** Why HabitMiner thinks you were asleep, in short phrases ("charging", "dark room"…). */
    val evidence: List<String> = emptyList(),
    val source: SleepSource = SleepSource.PHONE,
    /** Times the lock screen was looked at without unlocking, during the night. */
    val glances: Int = 0,
) {
    /** Time asleep: from falling asleep to waking up, minus the brief wake-ups. */
    val durationMs: Long get() = (wakeTime - sleepStart - briefWakes.sumOf { it.end - it.start }).coerceAtLeast(0L)
}

data class SleepSummary(
    val nights: Int,
    val avgDurationMs: Long,
    /** Minutes after midnight; may be negative for bedtimes before midnight. */
    val avgBedtimeMinutes: Int,
    val avgWakeMinutes: Int,
    val avgPreSleepUseMs: Long,
)

/** A daytime stretch that looks like a nap: phone untouched, no steps, often dark afterwards. */
data class NapCandidate(
    val start: Long,
    val end: Long,
    val confidence: Confidence,
    /** Short reasons, e.g. "no steps", "dark room when you picked the phone up". */
    val evidence: List<String>,
) {
    val durationMs: Long get() = end - start

    /** Stable key for the user's answer. */
    val key: String get() = "nap|$start|$end"
}

/**
 * Phone activity grouped into clusters: sessions and unlocks less than [CLUSTER_GAP_MS] apart
 * belong together. The gaps between clusters are stretches with the screen off.
 */
internal data class ActivityCluster(
    val start: Long,
    val end: Long,
    val usageMs: Long,
    val usedClock: Boolean,
) {
    /** An alarm, a glance at the time or a quick reply, not someone who is up. */
    val isBrief: Boolean
        get() = (usageMs <= 3 * TimeUtil.MINUTE && end - start <= 10 * TimeUtil.MINUTE) || (usedClock && usageMs <= 5 * TimeUtil.MINUTE)
}

internal object Activity {
    const val CLUSTER_GAP_MS = 5 * TimeUtil.MINUTE

    fun clusters(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        from: Long,
        to: Long,
    ): List<ActivityCluster> {
        data class Iv(val start: Long, val end: Long, val usage: Long, val clock: Boolean)
        val ivs =
            (
                sessions.filter { it.end > from && it.start < to }.map {
                    Iv(maxOf(it.start, from), minOf(it.end, to), TimeUtil.usageIn(it, from, to), isClock(it))
                } + unlocks.filter { it in from until to }.map { Iv(it, it, 0L, false) }
            ).sortedBy { it.start }
        val out = mutableListOf<ActivityCluster>()
        for (iv in ivs) {
            val last = out.lastOrNull()
            if (last != null && iv.start - last.end < CLUSTER_GAP_MS) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, iv.end), usageMs = last.usageMs + iv.usage, usedClock = last.usedClock || iv.clock)
            } else {
                out.add(ActivityCluster(iv.start, iv.end, iv.usage, iv.clock))
            }
        }
        return out
    }

    fun isClock(s: UsageSession): Boolean = s.packageName.contains("clock", ignoreCase = true) || s.appName.equals("Clock", ignoreCase = true)

    /** Steps counted between [from] and [to], or null when there are no step readings there. */
    fun stepsBetween(
        samples: List<ContextSample>,
        from: Long,
        to: Long,
    ): Int? {
        val inside = samples.filter { it.timestamp > from && it.timestamp <= to + 5 * TimeUtil.MINUTE && it.stepsSinceLast != null }
        return if (inside.isEmpty()) null else inside.sumOf { it.stepsSinceLast!! }
    }
}

/**
 * Estimates sleep from the longest stretch without phone activity overnight, then joins
 * neighbouring stretches across brief wake-ups (an alarm, a glance at the time) as long as
 * the step counter doesn't show you getting up. So "asleep 03:31, alarm at 07:20, snoozed,
 * up at 08:10" is one night of about 4h 40m instead of 3h 48m.
 *
 * The usage collector closes every session when the screen turns off, so a long gap
 * between sessions is a long stretch with the screen off. Unlocks without any app use
 * also count as activity. Context snapshots (charging, darkness) raise the confidence.
 */
object SleepDetector {
    private const val MIN_ANCHOR_MS = 90 * TimeUtil.MINUTE
    private const val MIN_SLEEP_MS = 3 * TimeUtil.HOUR
    private const val MAX_SLEEP_MS = 14 * TimeUtil.HOUR
    private const val MIN_JOIN_GAP_MS = 15 * TimeUtil.MINUTE

    /** More steps than this around a wake-up means you got up (a bathroom trip is fewer). */
    const val GOT_UP_STEPS = 60

    fun detect(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        samples: List<ContextSample>,
        wakeDate: LocalDate,
        now: Long,
        zone: ZoneId,
        signals: NightSignals = NightSignals(),
    ): SleepEstimate? {
        // Night window: 18:00 the evening before → 14:00 on the wake date.
        val windowStart = TimeUtil.at(wakeDate, -6, 0, zone)
        val windowEnd = minOf(TimeUtil.at(wakeDate, 14, 0, zone), now)
        if (windowEnd <= windowStart) return null

        val clusters = Activity.clusters(sessions, unlocks, windowStart, windowEnd)
        if (clusters.size < 2) return null

        // Anchor: the longest stretch between two clusters.
        var anchor = -1
        var anchorLen = 0L
        for (i in 0 until clusters.size - 1) {
            val len = clusters[i + 1].start - clusters[i].end
            if (len > anchorLen) {
                anchorLen = len
                anchor = i
            }
        }
        if (anchor < 0 || anchorLen < MIN_ANCHOR_MS) return null

        var first = anchor // cluster before the sleep
        var last = anchor + 1 // cluster after the sleep
        val wakes = mutableListOf<BriefWake>()

        // Forward: brief wake-up, then back to sleep for a while, without walking around.
        while (last < clusters.size - 1) {
            val c = clusters[last]
            val next = clusters[last + 1]
            if (!c.isBrief || next.start - c.end < MIN_JOIN_GAP_MS) break
            if (next.start - clusters[first].end > MAX_SLEEP_MS) break
            val steps = Activity.stepsBetween(samples, c.start, next.start)
            if (steps != null && steps > GOT_UP_STEPS) break
            wakes.add(BriefWake(c.start, c.end, c.usedClock))
            last++
        }
        // Backward: a brief check before finally falling asleep.
        while (first > 0) {
            val c = clusters[first]
            val prev = clusters[first - 1]
            if (!c.isBrief || c.start - prev.end < MIN_JOIN_GAP_MS) break
            if (clusters[last].start - prev.end > MAX_SLEEP_MS) break
            val steps = Activity.stepsBetween(samples, prev.end, c.end)
            if (steps != null && steps > GOT_UP_STEPS) break
            wakes.add(0, BriefWake(c.start, c.end, c.usedClock))
            first--
        }

        val sleepStart = clusters[first].end
        val wakeTime = clusters[last].start
        val asleep = wakeTime - sleepStart - wakes.sumOf { it.end - it.start }
        if (asleep < MIN_SLEEP_MS || wakeTime - sleepStart > MAX_SLEEP_MS) return null

        // The middle of a sleep should fall between 23:00 and 11:00.
        val mid = sleepStart + (wakeTime - sleepStart) / 2
        val midMinutes = TimeUtil.minuteOfDay(mid, zone)
        val midOk = midMinutes >= 23 * 60 || midMinutes <= 11 * 60
        if (!midOk) return null
        val night = build(sessions, samples, wakeDate, sleepStart, wakeTime, wakes, signals, zone, SleepSource.PHONE)
        val untouched = clusters[anchor + 1].start - clusters[anchor].end
        return night.copy(evidence = listOf("phone untouched for ${Format.duration(untouched)}") + night.evidence)
    }

    /**
     * A night with the given times: confidence, evidence and the before/after details. Used
     * for detected nights and for nights you set yourself.
     */
    internal fun build(
        sessions: List<UsageSession>,
        samples: List<ContextSample>,
        wakeDate: LocalDate,
        sleepStart: Long,
        wakeTime: Long,
        wakes: List<BriefWake>,
        signals: NightSignals,
        zone: ZoneId,
        source: SleepSource,
    ): SleepEstimate {
        val asleep = wakeTime - sleepStart - wakes.sumOf { it.end - it.start }
        val mid = sleepStart + (wakeTime - sleepStart) / 2
        val midMinutes = TimeUtil.minuteOfDay(mid, zone)

        // Confidence from duration, timing and context evidence inside the sleep.
        val inGap = samples.filter { it.timestamp in sleepStart..wakeTime }
        val chargingShare = if (inGap.isEmpty()) 0f else inGap.count { it.isCharging }.toFloat() / inGap.size
        val darkEvidence = inGap.any { (it.lightLux ?: 1000f) in 0f..10f }
        val steps = Activity.stepsBetween(samples, sleepStart, wakeTime)
        val stillEvidence = steps?.let { it <= GOT_UP_STEPS * (wakes.size + 1) } == true
        val quietMs = signals.quietHours.sumOf { (a, b) -> (minOf(b, wakeTime) - maxOf(a, sleepStart)).coerceAtLeast(0L) }
        val quiet = wakeTime > sleepStart && quietMs * 2 >= wakeTime - sleepStart
        val alarm = signals.alarms.filter { it in (sleepStart + TimeUtil.HOUR)..(wakeTime + 15 * TimeUtil.MINUTE) }.maxOrNull()
        val classicTiming = midMinutes in 0..(8 * 60)
        var score = 0
        if (asleep >= 5 * TimeUtil.HOUR) score++
        if (classicTiming) score++
        if (chargingShare >= 0.5f || darkEvidence || stillEvidence || quiet) score++
        val confidence =
            when {
                source == SleepSource.YOU -> Confidence.HIGH
                score >= 3 -> Confidence.HIGH
                score >= 2 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
        val evidence =
            buildList {
                if (chargingShare >= 0.5f) add("charging")
                if (darkEvidence) add("dark room")
                if (stillEvidence) add(if (steps == 0) "no steps" else "barely moved")
                if (quiet) add("Do Not Disturb on")
                if (alarm != null) add("alarm set for ${Format.clock(alarm, zone)}")
            }
        // A brief wake-up within a few minutes of a set alarm was the alarm.
        val markedWakes =
            wakes.map { w -> if (!w.alarm && signals.alarms.any { kotlin.math.abs(it - w.start) <= 5 * TimeUtil.MINUTE }) w.copy(alarm = true) else w }
        val glances = signals.glances.count { g -> g in sleepStart..wakeTime && markedWakes.none { g in (it.start - TimeUtil.MINUTE)..(it.end + TimeUtil.MINUTE) } }

        // Phone use in the hour before sleep, and how much of it was in the dark.
        val preStart = sleepStart - TimeUtil.HOUR
        val preSessions = sessions.filter { it.end > preStart && it.start < sleepStart }
        val preUse = preSessions.sumOf { TimeUtil.usageIn(it, preStart, sleepStart) }
        val index = SampleIndex(samples)
        var knownLight = 0L
        var dark = 0L
        for (s in preSessions) {
            val ms = TimeUtil.usageIn(s, preStart, sleepStart)
            val sample = index.nearestWithSensors(s.start + (s.end - s.start) / 2) ?: continue
            val light = ContextLabels.light(sample.lightLux) ?: continue
            knownLight += ms
            if (light == Light.DARK) dark += ms
        }
        val darkShare = if (knownLight >= 5 * TimeUtil.MINUTE) dark.toFloat() / knownLight else null

        val lastApp = sessions.filter { it.end <= sleepStart + TimeUtil.MINUTE }.maxByOrNull { it.end }?.appName
        val firstApp = sessions.filter { it.start >= wakeTime - TimeUtil.MINUTE }.minByOrNull { it.start }?.appName

        return SleepEstimate(
            wakeDate = wakeDate,
            sleepStart = sleepStart,
            wakeTime = wakeTime,
            confidence = confidence,
            preSleepUseMs = preUse,
            preSleepDarkShare = darkShare,
            lastAppBeforeSleep = lastApp,
            firstAppAfterWake = firstApp,
            briefWakes = markedWakes,
            evidence = evidence,
            source = source,
            glances = glances,
        )
    }

    /**
     * A night with times you set: phone use inside it counts as being awake, so the time
     * asleep stays honest.
     */
    fun fromTimes(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        samples: List<ContextSample>,
        wakeDate: LocalDate,
        start: Long,
        end: Long,
        zone: ZoneId,
        signals: NightSignals = NightSignals(),
        source: SleepSource = SleepSource.YOU,
    ): SleepEstimate {
        val inside = Activity.clusters(sessions, unlocks, start, end).filter { it.start > start && it.end < end }
        val wakes = inside.map { BriefWake(it.start, maxOf(it.end, it.start + TimeUtil.MINUTE / 2), it.usedClock) }
        return build(sessions, samples, wakeDate, start, end, wakes, signals, zone, source)
    }

    /** Estimates for each of the last [days] wake dates, oldest first. */
    fun detectRange(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        samples: List<ContextSample>,
        today: LocalDate,
        days: Int,
        now: Long,
        zone: ZoneId,
        signals: NightSignals = NightSignals(),
    ): List<SleepEstimate> =
        (days - 1 downTo 0).mapNotNull { back ->
            detect(sessions, unlocks, samples, today.minusDays(back.toLong()), now, zone, signals)
        }

    fun summarize(
        nights: List<SleepEstimate>,
        zone: ZoneId,
    ): SleepSummary? {
        if (nights.isEmpty()) return null
        // Bedtimes are averaged relative to midnight with evening times as negatives
        // so 23:30 and 00:30 average to 00:00 instead of 12:00.
        val bedtimes = nights.map { bedtimeMinutes(it.sleepStart, zone) }
        val wakes = nights.map { TimeUtil.minuteOfDay(it.wakeTime, zone) }
        return SleepSummary(
            nights = nights.size,
            avgDurationMs = nights.map { it.durationMs }.average().toLong(),
            avgBedtimeMinutes = bedtimes.average().toInt(),
            avgWakeMinutes = wakes.average().toInt(),
            avgPreSleepUseMs = nights.map { it.preSleepUseMs }.average().toLong(),
        )
    }

    /** Minutes from midnight, with evening times negative (23:30 → -30). */
    fun bedtimeMinutes(
        time: Long,
        zone: ZoneId,
    ): Int {
        val m = TimeUtil.minuteOfDay(time, zone)
        return if (m >= 12 * 60) m - 24 * 60 else m
    }
}

/**
 * Finds daytime stretches that look like a nap so the app can ask "Were you asleep?".
 * It never decides on its own: an exam or a lecture also means a still, untouched phone.
 *
 * A candidate is a screen-off stretch of at least [MIN_NAP_MS] that ends between 10:30 and
 * 21:00, joined across brief wake-ups like a night's sleep, with no walking during it (when
 * step data exists), not overlapping the night's sleep, and not at a known place other than
 * home. Darkness or charging when the phone is picked up again raises the confidence.
 */
object NapDetector {
    const val MIN_NAP_MS = 45 * TimeUtil.MINUTE
    private const val MAX_NAP_MS = 5 * TimeUtil.HOUR
    private const val MAX_NAP_STEPS = 30

    fun candidates(
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        samples: List<ContextSample>,
        day: LocalDate,
        now: Long,
        zone: ZoneId,
        night: SleepEstimate?,
        homePlace: String? = null,
    ): List<NapCandidate> {
        val from = TimeUtil.at(day, 9, 0, zone)
        val to = minOf(TimeUtil.at(day, 21, 0, zone), now)
        if (to <= from) return emptyList()
        val clusters = Activity.clusters(sessions, unlocks, from, to)
        if (clusters.size < 2) return emptyList()

        val out = mutableListOf<NapCandidate>()
        var i = 0
        while (i < clusters.size - 1) {
            var last = i + 1
            // Join across brief wake-ups, as for sleep.
            while (last < clusters.size - 1 &&
                clusters[last].isBrief &&
                clusters[last + 1].start - clusters[last].end >= 15 * TimeUtil.MINUTE &&
                clusters[last + 1].start - clusters[i].end <= MAX_NAP_MS
            ) {
                last++
            }
            // Short gaps at the start are dozing off with the phone in hand, not the nap itself.
            var first = i
            while (first < last - 1 && clusters[first + 1].start - clusters[first].end < 30 * TimeUtil.MINUTE) first++
            val start = clusters[first].end
            val end = clusters[last].start
            val wakes = (first + 1 until last).sumOf { clusters[it].end - clusters[it].start }
            val asleep = end - start - wakes
            val endMinute = TimeUtil.minuteOfDay(end, zone)
            val overlapsNight = night != null && start < night.wakeTime && end > night.sleepStart
            if (asleep >= MIN_NAP_MS && end - start <= MAX_NAP_MS && endMinute >= 10 * 60 + 30 && !overlapsNight) {
                candidate(samples, start, end, homePlace)?.let(out::add)
            }
            i = last
        }
        return out
    }

    private fun candidate(
        samples: List<ContextSample>,
        start: Long,
        end: Long,
        homePlace: String?,
    ): NapCandidate? {
        val steps = Activity.stepsBetween(samples, start, end)
        if (steps != null && steps > MAX_NAP_STEPS) return null
        val inside = samples.filter { it.timestamp in start..end }
        val places = inside.mapNotNull { it.place }.distinct()
        if (places.isNotEmpty() && (homePlace == null || places.any { it != homePlace })) return null

        // The first reading after picking the phone up (within 10 minutes).
        val after = samples.filter { it.timestamp in end..(end + 10 * TimeUtil.MINUTE) && it.lightLux != null }.minByOrNull { it.timestamp }
        val evidence = mutableListOf<String>()
        var score = 0
        if (steps != null) {
            evidence.add("no steps")
            score++
        }
        if (after != null && (after.lightLux ?: 1000f) <= 10f) {
            evidence.add("dark room when you picked the phone up")
            score++
        }
        if (inside.isNotEmpty() && inside.count { it.isCharging } * 2 >= inside.size) {
            evidence.add("charging")
            score++
        }
        if (places.isNotEmpty()) {
            evidence.add("at home")
            score++
        }
        if (end - start >= 75 * TimeUtil.MINUTE) score++
        val confidence =
            when {
                score >= 3 -> Confidence.HIGH
                score >= 2 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
        return NapCandidate(start, end, confidence, evidence)
    }
}
