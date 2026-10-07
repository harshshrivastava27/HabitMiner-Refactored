package com.habitminer.analytics

/** One mood answer: when, and 1–5 mood and energy (either may be missing). */
data class MoodAnswer(
    val time: Long,
    val mood: Int?,
    val energy: Int?,
)

/** How your answers move with phone time in the hours before them. */
data class MoodPattern(
    val answers: Int,
    /** Rank correlation with phone time in the previous [MoodPatterns.WINDOW_MS], or null with too few answers. */
    val moodLink: Float?,
    val energyLink: Float?,
    val averageMood: Float?,
    val averageEnergy: Float?,
) {
    /** A plain sentence about the clearest link, or null when there isn't one. */
    val summary: String?
        get() {
            val m = moodLink?.takeIf { kotlin.math.abs(it) >= MoodPatterns.MIN_LINK }
            val e = energyLink?.takeIf { kotlin.math.abs(it) >= MoodPatterns.MIN_LINK }
            return when {
                m != null && (e == null || kotlin.math.abs(m) >= kotlin.math.abs(e)) ->
                    "After more phone time in the previous three hours, you tended to rate your mood ${if (m < 0) "lower" else "higher"}."
                e != null -> "After more phone time in the previous three hours, you tended to rate your energy ${if (e < 0) "lower" else "higher"}."
                else -> null
            }
        }
}

/**
 * Within-person links between how you feel and recent phone time. Real but usually weak in
 * research, so they're shown as patterns in your own answers, never as causes, and only with
 * enough answers.
 */
object MoodPatterns {
    const val WINDOW_MS = 3 * TimeUtil.HOUR
    const val MIN_ANSWERS = 8
    const val MIN_LINK = 0.3f

    fun compute(
        answers: List<MoodAnswer>,
        sessions: List<UsageSession>,
    ): MoodPattern? {
        if (answers.isEmpty()) return null
        val use = answers.map { a -> sessions.sumOf { TimeUtil.usageIn(it, a.time - WINDOW_MS, a.time) }.toDouble() }
        fun link(values: List<Int?>): Float? {
            val pairs = values.zip(use).filter { it.first != null }.map { it.first!!.toDouble() to it.second }
            if (pairs.size < MIN_ANSWERS) return null
            return spearman(pairs.map { it.first }, pairs.map { it.second })?.toFloat()
        }
        return MoodPattern(
            answers = answers.size,
            moodLink = link(answers.map { it.mood }),
            energyLink = link(answers.map { it.energy }),
            averageMood = answers.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            averageEnergy = answers.mapNotNull { it.energy }.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        )
    }

    /** Spearman's rank correlation with average ranks for ties; null when either side is constant. */
    fun spearman(
        x: List<Double>,
        y: List<Double>,
    ): Double? {
        if (x.size != y.size || x.size < 3) return null
        val rx = ranks(x)
        val ry = ranks(y)
        val mx = rx.average()
        val my = ry.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in rx.indices) {
            num += (rx[i] - mx) * (ry[i] - my)
            dx += (rx[i] - mx) * (rx[i] - mx)
            dy += (ry[i] - my) * (ry[i] - my)
        }
        if (dx == 0.0 || dy == 0.0) return null
        return num / kotlin.math.sqrt(dx * dy)
    }

    private fun ranks(v: List<Double>): List<Double> {
        val order = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val avg = (i + j) / 2.0 + 1
            for (k in i..j) r[order[k]] = avg
            i = j + 1
        }
        return r.toList()
    }

    /** Parses a stored "mood=4;energy=3" value. */
    fun parse(
        time: Long,
        value: String,
    ): MoodAnswer? {
        val parts = value.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }.toMap()
        val mood = parts["mood"]?.takeIf { it in 1..5 }
        val energy = parts["energy"]?.takeIf { it in 1..5 }
        return if (mood == null && energy == null) null else MoodAnswer(time, mood, energy)
    }
}
