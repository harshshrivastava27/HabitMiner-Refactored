package com.habitminer.analytics

import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln

/** One guess for the next app, with its share of the model's belief (0–1). */
data class AppGuess(
    val appName: String,
    val share: Float,
)

/** What the model guessed before a past app switch, and what actually happened. */
data class GuessRecord(
    val time: Long,
    val afterApp: String,
    val guesses: List<String>,
    val actual: String,
) {
    val hit: Boolean get() = guesses.firstOrNull() == actual
    val inTop3: Boolean get() = actual in guesses.take(3)
}

/**
 * Predicts the next app from the apps just used, the hour of day and recent habits.
 *
 * It learns online: every app switch is first used as a test (guess, then compare) and then
 * learned from, so it keeps adapting. Older switches count less (half-life [HALF_LIFE_MS]),
 * so a change of routine such as exam week shows up within days instead of being outvoted
 * by weeks of older history.
 *
 * Each candidate app gets a weighted sum of six normalised signals:
 * - what usually follows the current app,
 * - what usually follows the last two apps (A → B → ?),
 * - what is usually opened around this hour,
 * - apps used in the last hour (people bounce between a few apps),
 * - how often each app is opened overall,
 * - going back to the app before the current one.
 *
 * The weights were picked on one week of real data and checked on the following three days,
 * where this model was right first time 34% of the time (top 3: 61%) against 21% (41%) for
 * the old time-slot Markov model and 22% for always guessing the most-used app.
 */
object NextAppModel {
    /** Screens that open on the way to something else rather than apps chosen on purpose. */
    val TRANSIENT_PACKAGES =
        setOf(
            "com.habitminer",
            "com.android.intentresolver",
            "com.google.android.photopicker",
            "com.google.android.captiveportallogin",
            "com.google.android.gms",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.systemui",
            "com.vivo.systemuiplugin",
            "com.android.incallui",
        )

    const val MAX_GAP_MS = 15 * TimeUtil.MINUTE
    const val HALF_LIFE_MS = 7 * TimeUtil.DAY
    private const val RECENT_WINDOW_MS = 60 * TimeUtil.MINUTE
    private const val RECENT_DECAY_MS = 15.0 * TimeUtil.MINUTE
    private const val CANDIDATES_PER_SIGNAL = 12

    private const val W_FROM = 1.0
    private const val W_PAIR = 0.5
    private const val W_HOUR = 1.0
    private const val W_RECENT = 2.0
    private const val W_GLOBAL = 0.5
    private const val W_BACK = 1.0

    private data class Switch(
        val before: String?,
        val from: String,
        val to: String,
        val time: Long,
        val hour: Int,
    )

    fun isTransient(packageName: String): Boolean = packageName in TRANSIENT_PACKAGES

    private fun switches(
        sessions: List<UsageSession>,
        zone: ZoneId,
    ): List<Switch> {
        val sorted = sessions.filterNot { isTransient(it.packageName) }.sortedBy { it.start }
        val out = mutableListOf<Switch>()
        var before: String? = null
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (b.start - a.end > MAX_GAP_MS) {
                before = null
                continue
            }
            if (a.appName == b.appName) continue
            out.add(Switch(before, a.appName, b.appName, b.start, TimeUtil.hourOf(b.start, zone)))
            before = a.appName
        }
        return out
    }

    /**
     * Decayed counts kept incrementally. Weights grow as exp(λ·(t − t0)) instead of shrinking
     * with age; every signal is normalised before use, so the common factor cancels out.
     */
    private class Learner(private val t0: Long) {
        private val lambda = ln(2.0) / HALF_LIFE_MS
        private val from = HashMap<String, HashMap<String, Double>>()
        private val pair = HashMap<String, HashMap<String, Double>>()
        private val hour = Array(24) { HashMap<String, Double>() }
        private val global = HashMap<String, Double>()
        private var backHits = 0.0
        private var backTotal = 0.0

        fun observe(s: Switch) {
            val w = exp(lambda * (s.time - t0))
            global.merge(s.to, w, Double::plus)
            from.getOrPut(s.from) { HashMap() }.merge(s.to, w, Double::plus)
            if (s.before != null) {
                pair.getOrPut("${s.before}\u0000${s.from}") { HashMap() }.merge(s.to, w, Double::plus)
                backTotal += w
                if (s.to == s.before) backHits += w
            }
            hour[s.hour].merge(s.to, w, Double::plus)
        }

        val globalCounts: Map<String, Double> get() = global

        fun rank(
            before: String?,
            current: String,
            hourOfDay: Int,
            recent: Map<String, Double>,
        ): List<Pair<String, Double>> {
            val hourMix = HashMap<String, Double>()
            for (d in -1..1) {
                val weight = if (d == 0) 1.0 else 0.5
                hour[Math.floorMod(hourOfDay + d, 24)].forEach { (app, v) -> hourMix.merge(app, v * weight, Double::plus) }
            }
            val signals =
                listOf(
                    W_FROM to normalise(from[current]),
                    W_PAIR to normalise(before?.let { pair["$it\u0000$current"] }),
                    W_HOUR to normalise(hourMix),
                    W_RECENT to normalise(recent),
                    W_GLOBAL to normalise(global),
                )
            val back = if (backTotal > 0) backHits / backTotal else 0.0
            val candidates =
                (
                    signals.flatMap { (_, m) -> m.entries.sortedByDescending { it.value }.take(CANDIDATES_PER_SIGNAL).map { it.key } } +
                        listOfNotNull(before)
                ).distinct().filter { it != current }
            return candidates.map { app ->
                var score = 0.0
                for ((w, m) in signals) score += w * (m[app] ?: 0.0)
                if (app == before) score += W_BACK * back
                app to score
            }.filter { it.second > 0.0 }.sortedByDescending { it.second }
        }

        private fun normalise(m: Map<String, Double>?): Map<String, Double> {
            if (m.isNullOrEmpty()) return emptyMap()
            val total = m.values.sum()
            return if (total <= 0.0) emptyMap() else m.mapValues { it.value / total }
        }
    }

    /** Apps used in the hour before [time], weighted by how recently and how long they were used. */
    private fun recentUse(
        sorted: List<UsageSession>,
        time: Long,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (s in sorted) {
            if (s.start >= time) break
            val age = time - s.end
            if (age < 0 || age > RECENT_WINDOW_MS) continue
            val weight = exp(-age / RECENT_DECAY_MS) * minOf(1.0, s.durationMs / 60_000.0 + 0.2)
            out.merge(s.appName, weight, Double::plus)
        }
        return out
    }

    /**
     * Likely next apps right now, after the most recent app. Empty when there is too little
     * history to say anything.
     */
    fun predict(
        sessions: List<UsageSession>,
        now: Long,
        zone: ZoneId,
        count: Int = 3,
    ): List<AppGuess> {
        val usable = sessions.filterNot { isTransient(it.packageName) }.filter { it.start <= now }.sortedBy { it.start }
        val all = switches(usable, zone)
        if (all.size < 20) return emptyList()
        val learner = Learner(all.first().time)
        all.forEach(learner::observe)
        val current = usable.lastOrNull() ?: return emptyList()
        val previous = usable.lastOrNull { it.end <= current.start && it.appName != current.appName }
        val before = previous?.takeIf { current.start - it.end <= MAX_GAP_MS }?.appName
        val ranked = learner.rank(before, current.appName, TimeUtil.hourOf(now, zone), recentUse(usable, now))
        val total = ranked.sumOf { it.second }.takeIf { it > 0 } ?: return emptyList()
        return ranked.take(count).map { AppGuess(it.first, (it.second / total).toFloat()) }
    }

    /** The app the predictions are for: the last app used that the model counts. */
    fun currentApp(sessions: List<UsageSession>): String? =
        sessions.filterNot { isTransient(it.packageName) }.maxByOrNull { it.start }?.appName

    data class Evaluation(
        val result: PredictabilityResult?,
        /** Guesses for the most recent switches, newest first. */
        val recent: List<GuessRecord>,
    )

    /**
     * Replays history in time order: before each switch in the last [testDays] days the model
     * guesses, then learns from what happened. Also keeps the most recent guesses so the app
     * can show what it expected next to what you actually opened.
     */
    fun evaluate(
        sessions: List<UsageSession>,
        now: Long,
        zone: ZoneId,
        testDays: Int = 3,
        keepRecent: Int = 12,
    ): Evaluation {
        val usable = sessions.filterNot { isTransient(it.packageName) }.filter { it.start < now }.sortedBy { it.start }
        val all = switches(usable, zone)
        if (all.isEmpty()) return Evaluation(null, emptyList())
        val testStart = TimeUtil.startOfDay(TimeUtil.dateOf(now, zone).minusDays((testDays - 1).toLong()), zone)
        val learner = Learner(all.first().time)
        var trained = 0
        var tested = 0
        var hits = 0
        var hits3 = 0
        var baseline = 0
        val recent = ArrayDeque<GuessRecord>()
        for (s in all) {
            if (s.time >= testStart && trained >= PredictabilityEvaluator.MIN_TEST_TRANSITIONS) {
                val ranked = learner.rank(s.before, s.from, s.hour, recentUse(usable, s.time)).map { it.first }
                tested++
                if (ranked.firstOrNull() == s.to) hits++
                if (s.to in ranked.take(3)) hits3++
                val mostUsed = learner.globalCounts.entries.filter { it.key != s.from }.maxByOrNull { it.value }?.key
                if (mostUsed == s.to) baseline++
                recent.addLast(GuessRecord(s.time, s.from, ranked.take(3), s.to))
                if (recent.size > keepRecent) recent.removeFirst()
            }
            learner.observe(s)
            trained++
        }
        val vocab = (all.map { it.to } + all.map { it.from }).distinct().size.coerceAtLeast(2)
        val result =
            if (tested >= PredictabilityEvaluator.MIN_TEST_TRANSITIONS) {
                PredictabilityResult(
                    hitRate = hits.toFloat() / tested,
                    top3HitRate = hits3.toFloat() / tested,
                    mostUsedBaseline = baseline.toFloat() / tested,
                    randomBaseline = 1f / (vocab - 1),
                    testedTransitions = tested,
                    testDays = testDays,
                )
            } else {
                null
            }
        return Evaluation(result, recent.reversed())
    }
}
