package com.habitminer.analytics

import java.time.ZoneId

/**
 * Honest, measured predictability: how often the next-app model guessed right on the most
 * recent days, compared against two baselines so the number means something.
 */
data class PredictabilityResult(
    val hitRate: Float,
    val top3HitRate: Float,
    /** Always guessing your single most-used next app. */
    val mostUsedBaseline: Float,
    /** Picking uniformly at random among the apps you use. */
    val randomBaseline: Float,
    val testedTransitions: Int,
    val testDays: Int,
    /** Right within the top 5 guesses. */
    val top5HitRate: Float = 0f,
    /** Mean reciprocal rank: 1 when always first, 0.5 when always second, … */
    val meanReciprocalRank: Float = 0f,
    /** Always guessing what most often followed the current app (no weighting by recency). */
    val markovBaseline: Float = 0f,
    /** Always guessing the app used just before the current one. */
    val recentBaseline: Float = 0f,
    /** Switches into an app right after it posted a notification. */
    val notificationSwitches: Int = 0,
    val notificationHitRate: Float? = null,
    /** Accuracy on switches you started yourself (no notification from the opened app). */
    val selfStartedHitRate: Float? = null,
)

object PredictabilityEvaluator {
    const val MIN_TEST_TRANSITIONS = 20

    /** Measured with [NextAppModel]: guess before each switch of the last [testDays] days, then learn. */
    fun evaluate(
        sessions: List<UsageSession>,
        now: Long,
        zone: ZoneId,
        testDays: Int = 3,
    ): PredictabilityResult? = NextAppModel.evaluate(sessions, now, zone, testDays).result
}

// ---------------------------------------------------------------------------------------
// Patterns: group the same app sequence across time slots
// ---------------------------------------------------------------------------------------

data class PatternRow(
    val sequence: String,
    val timeSlot: String,
    val dayType: String,
    val occurrences: Int,
    val confidence: Float,
    val lastSeen: Long,
)

data class PatternGroup(
    val sequence: String,
    val apps: List<String>,
    /** "Weekday evenings & nights" */
    val whenText: String,
    /** "Seen on 10 of the last 10 weekday nights" */
    val evidenceText: String,
    val bestConfidence: Float,
    val totalOccurrences: Int,
    val lastSeen: Long,
    val slots: List<String>,
)

object PatternGrouper {
    private val slotOrder = listOf("MORNING", "AFTERNOON", "EVENING", "NIGHT")
    private val slotPlural = mapOf("MORNING" to "mornings", "AFTERNOON" to "afternoons", "EVENING" to "evenings", "NIGHT" to "nights")

    fun group(rows: List<PatternRow>): List<PatternGroup> =
        rows.groupBy { it.sequence }
            .map { (sequence, list) ->
                val slots = list.map { it.timeSlot }.distinct().sortedBy { slotOrder.indexOf(it) }
                val dayTypes = list.map { it.dayType }.distinct()
                val dayWord =
                    when {
                        dayTypes.size > 1 || "ANY" in dayTypes -> "Every day"
                        dayTypes.single() == "WEEKEND" -> "Weekend"
                        else -> "Weekday"
                    }
                val slotText = joinNatural(slots.map { slotPlural[it] ?: it.lowercase() })
                val best = list.maxByOrNull { it.confidence }!!
                val bestDays = if (best.confidence > 0f) Math.round(best.occurrences / best.confidence) else best.occurrences
                val bestSlotWord = (slotPlural[best.timeSlot] ?: best.timeSlot.lowercase())
                val bestDayWord = if (best.dayType == "WEEKEND") "weekend" else if (best.dayType == "WEEKDAY") "weekday" else ""
                PatternGroup(
                    sequence = sequence,
                    apps = sequence.split(" → "),
                    whenText = "$dayWord $slotText",
                    evidenceText = "Seen on ${best.occurrences} of the last $bestDays ${"$bestDayWord $bestSlotWord".trim()}",
                    bestConfidence = best.confidence,
                    totalOccurrences = list.sumOf { it.occurrences },
                    lastSeen = list.maxOf { it.lastSeen },
                    slots = slots,
                )
            }
            .sortedWith(compareByDescending<PatternGroup> { it.bestConfidence * Math.log(it.totalOccurrences + 1.0) }.thenBy { it.sequence })

    private fun joinNatural(items: List<String>): String =
        when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " & " + items.last()
        }
}
