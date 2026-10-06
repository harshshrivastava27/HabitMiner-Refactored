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
