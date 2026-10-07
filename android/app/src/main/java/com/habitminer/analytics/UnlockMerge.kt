package com.habitminer.analytics

/**
 * Combines the two records of unlocks into one list.
 *
 * The system's lock-screen log (KEYGUARD_HIDDEN) covers every unlock, even while HabitMiner
 * wasn't running, but it is copied into the database only every few minutes. Our own receiver
 * sees unlocks immediately, but only while the app runs. An unlock that both recorded is
 * counted once: a receiver unlock within [toleranceMs] of a system one is the same unlock.
 */
object UnlockMerge {
    const val DEFAULT_TOLERANCE_MS = 15_000L

    /** Both inputs oldest first; the result is oldest first. */
    fun merge(
        system: List<Long>,
        receiver: List<Long>,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    ): List<Long> {
        if (receiver.isEmpty()) return system
        if (system.isEmpty()) return receiver
        val out = ArrayList<Long>(system.size + receiver.size)
        var i = 0
        for (r in receiver) {
            // Copy system unlocks up to this receiver unlock's window.
            while (i < system.size && system[i] < r - toleranceMs) out.add(system[i++])
            val matched = i < system.size && system[i] <= r + toleranceMs
            if (!matched) out.add(r)
        }
        while (i < system.size) out.add(system[i++])
        out.sort()
        return out
    }
}
