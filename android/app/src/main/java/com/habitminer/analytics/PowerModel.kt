package com.habitminer.analytics

import java.time.LocalDate
import java.time.ZoneId

/**
 * Estimates HabitMiner's own battery use per day, the way Android's battery screen does:
 * time × current for each part, summed, then as a share of the battery's capacity.
 *
 * - Sensors: each reading's sensing time × the power the phone reports for the sensors used
 *   (Sensor.getPower), plus the step counter for the hours it was listening.
 * - Processor: CPU time × an assumed current ([CPU_MA]); not measured on this phone.
 * - Keeping the phone awake: wake-lock time × [AWAKE_MA].
 * - Network: radio energy, when the phone reports it.
 *
 * It's an estimate, shown as one.
 */
object PowerModel {
    /** Assumed average current while HabitMiner's code runs on the processor. */
    const val CPU_MA = 100.0

    /** Extra current while a wake lock stops the phone from sleeping. */
    const val AWAKE_MA = 10.0

    /** Google's line for "excessive" partial wake locks: 2 hours in a day. */
    const val WAKELOCK_LIMIT_MS = 2 * TimeUtil.HOUR

    /** Sensor power in mA, as reported by the phone. */
    data class SensorPower(
        val accelerometer: Double,
        val gyroscope: Double,
        val light: Double,
        val proximity: Double,
        val stepCounter: Double,
    )

    /** One self-health sample: running totals at [time]. */
    data class Sample(
        val time: Long,
        val cpuMs: Long,
        val wakelockMs: Long,
        val sensorMs: Long,
        val radioMams: Long,
        val batteryLevel: Int,
        val chargeCounterUah: Long?,
    )

    /** One sensor reading HabitMiner took: how long sensors were on, and whether the gyroscope was. */
    data class Reading(
        val time: Long,
        val sensingMs: Long,
        val gyro: Boolean,
    )

    data class DayUse(
        val date: LocalDate,
        val cpuMs: Long,
        val wakelockMs: Long,
        val sensingMs: Long,
        val sensorMah: Double,
        val cpuMah: Double,
        val awakeMah: Double,
        val radioMah: Double,
        /** Share of a full battery, or null when the phone doesn't report its capacity. */
        val sharePct: Double?,
    ) {
        val totalMah: Double get() = sensorMah + cpuMah + awakeMah + radioMah
        val excessiveWakelocks: Boolean get() = wakelockMs >= WAKELOCK_LIMIT_MS
    }

    /** Battery capacity in mAh from the charge counter and level, when both are reported. */
    fun capacityMah(samples: List<Sample>): Double? =
        samples.lastOrNull { it.chargeCounterUah != null && it.batteryLevel in 5..100 }
            ?.let { it.chargeCounterUah!! / (it.batteryLevel / 100.0) / 1000.0 }
            ?.takeIf { it in 1000.0..20000.0 }

    fun daily(
        samples: List<Sample>,
        readings: List<Reading>,
        power: SensorPower,
        days: List<LocalDate>,
        zone: ZoneId,
    ): List<DayUse> {
        val sorted = samples.sortedBy { it.time }
        val cpu = HashMap<LocalDate, Long>()
        val wake = HashMap<LocalDate, Long>()
        val radio = HashMap<LocalDate, Long>()
        val listened = HashMap<LocalDate, Long>()
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val d = TimeUtil.dateOf(b.time, zone)
            // A total that went down was reset (reboot): count what's there since.
            fun delta(
                x: Long,
                y: Long,
            ) = if (y >= x) y - x else y
            cpu.merge(d, delta(a.cpuMs, b.cpuMs), Long::plus)
            wake.merge(d, delta(a.wakelockMs, b.wakelockMs), Long::plus)
            radio.merge(d, delta(a.radioMams, b.radioMams), Long::plus)
            // The step counter listens all the time HabitMiner runs; gaps over an hour mean it wasn't running.
            listened.merge(d, (b.time - a.time).coerceAtMost(TimeUtil.HOUR), Long::plus)
        }
        val capacity = capacityMah(sorted)
        val readingsByDay = readings.groupBy { TimeUtil.dateOf(it.time, zone) }
        return days.map { d ->
            val r = readingsByDay[d].orEmpty()
            val sensing = r.sumOf { it.sensingMs }
            val perReading = power.accelerometer + power.light + power.proximity
            val sensorMah =
                r.sumOf { it.sensingMs / 3_600_000.0 * (perReading + if (it.gyro) power.gyroscope else 0.0) } +
                    (listened[d] ?: 0L) / 3_600_000.0 * power.stepCounter
            val cpuMah = (cpu[d] ?: 0L) / 3_600_000.0 * CPU_MA
            val awakeMah = (wake[d] ?: 0L) / 3_600_000.0 * AWAKE_MA
            val radioMah = (radio[d] ?: 0L) / 3_600_000.0
            val total = sensorMah + cpuMah + awakeMah + radioMah
            DayUse(
                date = d,
                cpuMs = cpu[d] ?: 0L,
                wakelockMs = wake[d] ?: 0L,
                sensingMs = sensing,
                sensorMah = sensorMah,
                cpuMah = cpuMah,
                awakeMah = awakeMah,
                radioMah = radioMah,
                sharePct = capacity?.let { total / it * 100.0 },
            )
        }
    }
}
