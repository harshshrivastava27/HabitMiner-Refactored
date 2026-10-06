package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId

/** A coloured stretch on the 24-hour strip, in minutes after midnight. */
data class TimelineSegment(
    val startMinute: Int,
    val endMinute: Int,
    val category: AppCategory,
    val topApp: String,
)

/** One context reading on the strip's second lane. */
data class ContextMark(
    val minute: Int,
    val light: Light?,
    val motion: Motion?,
    val charging: Boolean,
)

data class DayTimelineData(
    val date: LocalDate,
    val segments: List<TimelineSegment>,
    val marks: List<ContextMark>,
    /** Sleep periods overlapping this day, in minutes after midnight. */
    val sleepBands: List<Pair<Int, Int>>,
    val totalMs: Long,
)

object DayTimelineBuilder {
    fun build(
        sessions: List<UsageSession>,
        samples: List<ContextSample>,
        sleeps: List<SleepEstimate>,
        date: LocalDate,
        zone: ZoneId,
        /** Naps you confirmed, (start, end); drawn in the sleep lane too. */
        naps: List<Pair<Long, Long>> = emptyList(),
    ): DayTimelineData {
        val dayStart = TimeUtil.startOfDay(date, zone)
        val dayEnd = TimeUtil.startOfDay(date.plusDays(1), zone)
        fun minute(ms: Long): Int = (((ms.coerceIn(dayStart, dayEnd)) - dayStart) / TimeUtil.MINUTE).toInt()

        val raw =
            sessions.filter { it.end > dayStart && it.start < dayEnd }
                .sortedBy { it.start }
                .map { TimelineSegment(minute(it.start), maxOf(minute(it.end), minute(it.start) + 1), it.category, it.appName) }

        // Merge touching segments of the same category so the strip stays readable.
        val merged = mutableListOf<TimelineSegment>()
        for (seg in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.category == seg.category && seg.startMinute - last.endMinute <= 1) {
                merged[merged.lastIndex] = last.copy(endMinute = maxOf(last.endMinute, seg.endMinute))
            } else {
                merged.add(seg)
            }
        }

        val marks =
            samples.filter { it.timestamp in dayStart until dayEnd }
                .sortedBy { it.timestamp }
                .map {
                    ContextMark(
                        minute = minute(it.timestamp),
                        light = ContextLabels.light(it.lightLux),
                        motion = ContextLabels.motion(it),
                        charging = it.isCharging,
                    )
                }

        val bands =
            (sleeps.map { it.sleepStart to it.wakeTime } + naps)
                .filter { (start, end) -> end > dayStart && start < dayEnd }
                .map { (start, end) -> minute(start) to minute(end) }

        val total = sessions.sumOf { TimeUtil.usageIn(it, dayStart, dayEnd) }
        return DayTimelineData(date, merged, marks, bands, total)
    }
}
