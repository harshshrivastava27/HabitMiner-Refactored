package com.habitminer.analytics

import java.time.ZoneId
import kotlin.math.abs

/**
 * Applies the nights you corrected, and learns from them.
 *
 * The phone only sees when it was put down and picked up. Most people fall asleep a while
 * after putting the phone down and lie awake a little before picking it up, by an amount that
 * is fairly steady for each person. Once you have corrected [MIN_FIXES] nights, the typical
 * difference between your times and the estimate (the median, so one odd night doesn't
 * matter) shifts every other night too, and those nights say so.
 */
object SleepCorrections {
    const val MIN_FIXES = 3

    /** Larger shifts than this are not applied: something else is going on. */
    const val MAX_SHIFT_MS = 90 * TimeUtil.MINUTE

    /** Smaller shifts than this aren't worth applying. */
    const val MIN_SHIFT_MS = 5 * TimeUtil.MINUTE

    /** Fixes this far from the estimate were about a different stretch, not the same night. */
    private const val SAME_NIGHT_MS = 3 * TimeUtil.HOUR

    data class Shift(
        /** Added to the estimated time you fell asleep. */
        val startMs: Long,
        /** Added to the estimated time you woke up. */
        val endMs: Long,
        /** How many corrected nights it's based on. */
        val nights: Int,
    )

    /** The typical difference between your corrections and the estimates, or null. */
    fun shift(
        detected: List<SleepEstimate>,
        fixes: List<SleepFix>,
    ): Shift? {
        val pairs =
            fixes.filterNot { it.notSleep }.mapNotNull { f ->
                val d = detected.firstOrNull { it.wakeDate == f.wakeDate } ?: return@mapNotNull null
                val ds = f.start - d.sleepStart
                val de = f.end - d.wakeTime
                if (abs(ds) > SAME_NIGHT_MS || abs(de) > SAME_NIGHT_MS) null else ds to de
            }
        if (pairs.size < MIN_FIXES) return null
        val start = median(pairs.map { it.first }).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
        val end = median(pairs.map { it.second }).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
        val s = if (abs(start) < MIN_SHIFT_MS) 0L else start
        val e = if (abs(end) < MIN_SHIFT_MS) 0L else end
        if (s == 0L && e == 0L) return null
        return Shift(s, e, pairs.size)
    }

    /**
     * Nights with your corrections applied, oldest first: corrected nights use your times,
     * nights you said weren't sleep are dropped, and the rest are shifted by [shift].
     */
    fun apply(
        detected: List<SleepEstimate>,
        fixes: List<SleepFix>,
        sessions: List<UsageSession>,
        unlocks: List<Long>,
        samples: List<ContextSample>,
        zone: ZoneId,
        signals: NightSignals = NightSignals(),
    ): List<SleepEstimate> {
        if (fixes.isEmpty()) return detected
        val latest = fixes.associateBy { it.wakeDate }
        val shift = shift(detected, latest.values.toList())
        val adjusted =
            detected.filter { it.wakeDate !in latest }.map { n -> if (shift == null) n else shifted(n, shift) }
        val yours =
            latest.values.filterNot { it.notSleep }.map { f ->
                SleepDetector.fromTimes(sessions, unlocks, samples, f.wakeDate, f.start, f.end, zone, signals)
            }
        return (adjusted + yours).sortedBy { it.wakeDate }
    }

    private fun shifted(
        n: SleepEstimate,
        shift: Shift,
    ): SleepEstimate {
        val start = n.sleepStart + shift.startMs
        val end = n.wakeTime + shift.endMs
        if (end - start < TimeUtil.HOUR) return n
        val wakes = n.briefWakes.filter { it.start > start && it.end < end }
        val why = "shifted by what your ${shift.nights} corrected nights showed"
        return n.copy(sleepStart = start, wakeTime = end, briefWakes = wakes, source = SleepSource.ADJUSTED, evidence = n.evidence + why)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
