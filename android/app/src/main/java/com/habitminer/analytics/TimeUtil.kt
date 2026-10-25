package com.habitminer.analytics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Small time helpers shared by the analytics code. All functions take an explicit zone. */
object TimeUtil {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    fun dateOf(
        ms: Long,
        zone: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun startOfDay(
        date: LocalDate,
        zone: ZoneId,
    ): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfDay(
        ms: Long,
        zone: ZoneId,
    ): Long = startOfDay(dateOf(ms, zone), zone)

    /** Time on [date] at [hour]:[minute]; hour may be negative or ≥ 24 to reach neighbouring days. */
    fun at(
        date: LocalDate,
        hour: Int,
        minute: Int,
        zone: ZoneId,
    ): Long {
        val dayShift = Math.floorDiv(hour, 24).toLong()
        val h = Math.floorMod(hour, 24)
        return date.plusDays(dayShift).atTime(h, minute).atZone(zone).toInstant().toEpochMilli()
    }

    fun zoned(
        ms: Long,
        zone: ZoneId,
    ): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun hourOf(
        ms: Long,
        zone: ZoneId,
    ): Int = zoned(ms, zone).hour

    fun minuteOfDay(
        ms: Long,
        zone: ZoneId,
    ): Int = zoned(ms, zone).let { it.hour * 60 + it.minute }

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun overlap(
        aStart: Long,
        aEnd: Long,
        bStart: Long,
        bEnd: Long,
    ): Long = max(0L, min(aEnd, bEnd) - max(aStart, bStart))

    /**
     * Foreground time of [session] that falls inside [windowStart, windowEnd), pro-rating
     * sessions that straddle the edges.
     */
    fun usageIn(
        session: UsageSession,
        windowStart: Long,
        windowEnd: Long,
    ): Long {
        val span = session.end - session.start
        if (span <= 0L) return if (session.start in windowStart until windowEnd) session.durationMs else 0L
        val ov = overlap(session.start, session.end, windowStart, windowEnd)
        if (ov <= 0L) return 0L
        return if (ov >= span) session.durationMs else (session.durationMs.toDouble() * ov / span).toLong()
    }
}

/** Human-readable formatting used across the UI. */
object Format {
    /** "2h 5m", "45m", "<1m". */
    fun duration(ms: Long): String {
        val totalMin = ms / TimeUtil.MINUTE
        if (ms in 1 until TimeUtil.MINUTE) return "<1m"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Clock time like "23:40". */
    fun clock(
        ms: Long,
        zone: ZoneId,
    ): String {
        val z = TimeUtil.zoned(ms, zone)
        return String.format(Locale.US, "%02d:%02d", z.hour, z.minute)
    }

    /** Clock time from minutes after midnight; wraps past 24h. */
    fun clockFromMinutes(minutes: Int): String {
        val m = Math.floorMod(minutes, 24 * 60)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** "5 min ago", "2 h ago", "just now". */
    fun ago(ageMs: Long): String =
        when {
            ageMs < TimeUtil.MINUTE -> "just now"
            ageMs < TimeUtil.HOUR -> "${ageMs / TimeUtil.MINUTE} min ago"
            ageMs < TimeUtil.DAY -> "${ageMs / TimeUtil.HOUR} h ago"
            else -> "${ageMs / TimeUtil.DAY} d ago"
        }

    fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"

    fun hourRange(hour: Int): String = String.format(Locale.US, "%02d:00–%02d:00", hour, (hour + 1) % 24)
}

/**
 * Splits app sessions at midnight, so each day's screen time is what was actually used that
 * day. A film from 23:30 to 01:00 is 30 minutes on one day and an hour on the next.
 */
object DaySplit {
    /** One piece of a session inside a single day. */
    data class Piece(
        val start: Long,
        val end: Long,
        val durationMs: Long,
    )

    /**
     * [start] to [end] cut at each local midnight. [durationMs] (time actually in front, which
     * can be less than the span when visits were merged) is shared in proportion to each
     * piece's span; the pieces always add up to it exactly.
     */
    fun split(
        start: Long,
        end: Long,
        durationMs: Long,
        zone: java.time.ZoneId,
    ): List<Piece> {
        if (end <= start) return listOf(Piece(start, end, durationMs))
        val bounds = mutableListOf(start)
        var day = TimeUtil.dateOf(start, zone).plusDays(1)
        while (true) {
            val midnight = TimeUtil.startOfDay(day, zone)
            if (midnight >= end) break
            bounds += midnight
            day = day.plusDays(1)
        }
        if (bounds.size == 1) return listOf(Piece(start, end, durationMs))
        bounds += end
        val span = (end - start).toDouble()
        val pieces = mutableListOf<Piece>()
        var given = 0L
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            val share = if (i == bounds.size - 2) durationMs - given else Math.round(durationMs * ((b - a) / span))
            given += share
            pieces += Piece(a, b, share)
        }
        return pieces
    }

    /** True when the session runs past a local midnight. */
    fun crossesMidnight(
        start: Long,
        end: Long,
        zone: java.time.ZoneId,
    ): Boolean = end > start && TimeUtil.dateOf(start, zone) != TimeUtil.dateOf(end - 1, zone)
}

/** Telling alarm and timer apps apart from other apps. */
object AlarmApps {
    /**
     * True for the phone's clock app and other alarm apps: the package or name says clock or
     * alarm, or it's the app that set the next alarm ([alarmPackage], when known).
     */
    fun isAlarmApp(
        packageName: String,
        appName: String = "",
        alarmPackage: String? = null,
    ): Boolean =
        packageName == alarmPackage ||
            packageName.contains("clock", ignoreCase = true) ||
            packageName.contains("alarm", ignoreCase = true) ||
            appName.equals("Clock", ignoreCase = true) ||
            appName.equals("Alarm", ignoreCase = true)
}
