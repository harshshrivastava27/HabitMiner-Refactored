package com.habitminer.analytics

data class PickupTrigger(
    val appName: String,
    val count: Int,
)

data class PickupStats(
    val total: Int,
    val afterNotification: Int,
    /** Unlocks with under 30 s of app use: a quick check of the lock screen or a message. */
    val quickChecks: Int,
    val topTriggers: List<PickupTrigger>,
    /** First app opened after unlocking, most common first. */
    val topFirstApps: List<PickupTrigger>,
) {
    val selfInitiated: Int get() = total - afterNotification
    val notificationShare: Float get() = if (total == 0) 0f else afterNotification.toFloat() / total
}

/**
 * Classifies each unlock as notification-driven (a notification arrived shortly before)
 * or self-initiated. A classic interruption metric from mobile HCI research.
 */
object PickupAnalyzer {
    /**
     * Pickups on a usual day: the median over the last [days] full days before [today], leaving
     * out days you set aside and days with no pickups at all (the phone was off or not
     * recording). Null with fewer than three days to go on.
     */
    fun usualPerDay(
        unlocks: List<Long>,
        today: java.time.LocalDate,
        zone: java.time.ZoneId,
        days: Int = 14,
        excluded: Set<java.time.LocalDate> = emptySet(),
    ): Int? {
        val from = today.minusDays(days.toLong())
        val counts =
            unlocks.asSequence()
                .map { TimeUtil.dateOf(it, zone) }
                .filter { !it.isBefore(from) && it.isBefore(today) && it !in excluded }
                .groupingBy { it }
                .eachCount()
                .values
                .sorted()
        if (counts.size < 3) return null
        val mid = counts.size / 2
        return if (counts.size % 2 == 1) counts[mid] else (counts[mid - 1] + counts[mid] + 1) / 2
    }

    const val NOTIFICATION_WINDOW_MS = 2 * TimeUtil.MINUTE
    private const val FIRST_APP_WINDOW_MS = TimeUtil.MINUTE
    private const val QUICK_CHECK_MS = 30_000L

    fun analyze(
        unlocks: List<Long>,
        notifications: List<TimedEvent>,
        sessions: List<UsageSession>,
        windowStart: Long,
        windowEnd: Long,
        appNameFor: (String) -> String,
    ): PickupStats {
        val inWindow = unlocks.filter { it in windowStart until windowEnd }.sorted().distinct()
        val notifs = notifications.filter { it.packageName != null }.sortedBy { it.timestamp }
        val sortedSessions = sessions.sortedBy { it.start }

        var afterNotification = 0
        var quick = 0
        val triggerCounts = mutableMapOf<String, Int>()
        val firstAppCounts = mutableMapOf<String, Int>()

        for ((i, unlock) in inWindow.withIndex()) {
            val trigger =
                notifs.lastOrNull { it.timestamp in (unlock - NOTIFICATION_WINDOW_MS)..unlock }
            if (trigger != null) {
                afterNotification++
                val name = appNameFor(trigger.packageName!!)
                triggerCounts[name] = (triggerCounts[name] ?: 0) + 1
            }

            val first =
                sortedSessions.firstOrNull { it.start in unlock..(unlock + FIRST_APP_WINDOW_MS) }
            if (first != null) firstAppCounts[first.appName] = (firstAppCounts[first.appName] ?: 0) + 1

            // App use between this unlock and the next one (or 10 min, whichever first).
            val until = minOf(inWindow.getOrNull(i + 1) ?: Long.MAX_VALUE, unlock + 10 * TimeUtil.MINUTE)
            val used = sortedSessions.sumOf { TimeUtil.usageIn(it, unlock, until) }
            if (used < QUICK_CHECK_MS) quick++
        }

        return PickupStats(
            total = inWindow.size,
            afterNotification = afterNotification,
            quickChecks = quick,
            topTriggers = triggerCounts.toTriggers(),
            topFirstApps = firstAppCounts.toTriggers(),
        )
    }

    private fun Map<String, Int>.toTriggers(): List<PickupTrigger> =
        entries.sortedByDescending { it.value }.take(3).map { PickupTrigger(it.key, it.value) }
}
