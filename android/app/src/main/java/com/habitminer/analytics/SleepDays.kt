package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * All the sleep that belongs to one day: the night that ended that morning plus the naps
 * you confirmed that day. Days are keyed by the date you woke up, as most sleep trackers
 * do, so each night is counted once and days can be compared across a week.
 */
data class DailySleep(
    val date: LocalDate,
    val night: SleepEstimate?,
    /** Confirmed naps that started on [date], (start, end). */
    val naps: List<Pair<Long, Long>>,
) {
    val nightMs: Long get() = night?.durationMs ?: 0L
    val napMs: Long get() = naps.sumOf { it.second - it.first }
    val totalMs: Long get() = nightMs + napMs

    /** From falling asleep to waking up, including brief wake-ups. */
    val inBedMs: Long get() = night?.let { it.wakeTime - it.sleepStart } ?: 0L
    val wakeUpMs: Long get() = night?.briefWakes?.sumOf { it.end - it.start } ?: 0L
}

data class SleepWeek(
    val days: Int,
    val avgTotalMs: Long,
    val avgNightMs: Long,
    val napCount: Int,
    val napTotalMs: Long,
    /** Typical bedtime in minutes from midnight (evening times negative). */
    val avgBedtimeMinutes: Int?,
    /** How much bedtimes vary (standard deviation, minutes); lower is more regular. */
    val bedtimeSpreadMinutes: Int?,
    val shortest: DailySleep?,
    val longest: DailySleep?,
)

object SleepDays {
    /** The last [days] days ending [today], oldest first; days with neither a night nor a nap are skipped. */
    fun build(
        nights: List<SleepEstimate>,
        naps: List<Pair<Long, Long>>,
        today: LocalDate,
        days: Int,
        zone: ZoneId,
    ): List<DailySleep> =
        (days - 1 downTo 0).mapNotNull { back ->
            val date = today.minusDays(back.toLong())
            val night = nights.firstOrNull { it.wakeDate == date }
            val dayNaps = naps.filter { TimeUtil.dateOf(it.first, zone) == date }.sortedBy { it.first }
            if (night == null && dayNaps.isEmpty()) null else DailySleep(date, night, dayNaps)
        }

    /** Weekly numbers; today counts only if its night is known (naps may still be to come). */
    fun summarize(
        days: List<DailySleep>,
        zone: ZoneId,
    ): SleepWeek? {
        val counted = days.filter { it.night != null || it.naps.isNotEmpty() }
        if (counted.isEmpty()) return null
        val bedtimes = counted.mapNotNull { d -> d.night?.let { SleepDetector.bedtimeMinutes(it.sleepStart, zone) } }
        val avgBed = bedtimes.takeIf { it.isNotEmpty() }?.average()
        val spread =
            avgBed?.takeIf { bedtimes.size >= 3 }?.let { m -> sqrt(bedtimes.map { (it - m) * (it - m) }.average()).toInt() }
        val withNight = counted.filter { it.night != null }
        return SleepWeek(
            days = counted.size,
            avgTotalMs = counted.map { it.totalMs }.average().toLong(),
            avgNightMs = if (withNight.isEmpty()) 0L else withNight.map { it.nightMs }.average().toLong(),
            napCount = counted.sumOf { it.naps.size },
            napTotalMs = counted.sumOf { it.napMs },
            avgBedtimeMinutes = avgBed?.toInt(),
            bedtimeSpreadMinutes = spread,
            shortest = withNight.minByOrNull { it.totalMs },
            longest = withNight.maxByOrNull { it.totalMs },
        )
    }
}

/** A nap on record: yours (answers that overlap are one nap) or one from Health Connect. */
data class RecordedNap(
    val start: Long,
    val end: Long,
    /** The answers it's made of ("nap|start|end"), to edit or remove it; empty for Health Connect. */
    val keys: List<String> = emptyList(),
) {
    val durationMs: Long get() = end - start
    val fromHealthConnect: Boolean get() = keys.isEmpty()
}

/**
 * What you said about a stretch of time: asleep ([asleep] true), not a nap (false), or
 * replaced by times you set yourself (null). [at] is when you said it.
 */
data class NapAnswer(
    val key: String,
    val start: Long,
    val end: Long,
    val asleep: Boolean?,
    val at: Long,
)

/** Naps from your answers, the ones you added, and Health Connect. */
object NapRecords {
    fun key(
        start: Long,
        end: Long,
    ): String = "nap|$start|$end"

    /** A detected nap worth asking about: a likely one, or a long one even if less certain. */
    fun worthAsking(c: NapCandidate): Boolean = c.confidence != Confidence.LOW || c.durationMs >= TimeUtil.HOUR

    /** Your latest answer for each stretch. */
    fun latest(answers: List<NapAnswer>): List<NapAnswer> = answers.groupBy { it.key }.values.map { list -> list.maxBy { it.at } }

    /**
     * Your naps, oldest first: the latest answer per stretch, with "asleep" answers that overlap
     * (the same nap asked about twice as it grew) merged into one, plus Health Connect's naps.
     */
    fun build(
        answers: List<NapAnswer>,
        health: List<Pair<Long, Long>> = emptyList(),
    ): List<RecordedNap> {
        val all =
            latest(answers).filter { it.asleep == true && it.end > it.start }.map { RecordedNap(it.start, it.end, listOf(it.key)) } +
                health.filter { it.second > it.first }.map { RecordedNap(it.first, it.second) }
        val out = mutableListOf<RecordedNap>()
        for (n in all.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && n.start < last.end) {
                out[out.lastIndex] = RecordedNap(last.start, maxOf(last.end, n.end), last.keys + n.keys)
            } else {
                out += n
            }
        }
        return out
    }

    /**
     * Whether you've already said something about this stretch: answered it, or answered one
     * overlapping it (the same nap seen again once it had grown, or covered by times you set).
     */
    fun answered(
        c: NapCandidate,
        answers: List<NapAnswer>,
    ): Boolean = answers.any { it.key == c.key || (it.start < c.end && it.end > c.start) }
}
