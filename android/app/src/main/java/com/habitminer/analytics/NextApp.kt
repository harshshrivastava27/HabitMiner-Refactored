package com.habitminer.analytics

import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One guess for the next app, with its share of the model's belief (0–1). */
data class AppGuess(
    val appName: String,
    val share: Float,
    /** For the app's icon and detail page; null when not known. */
    val packageName: String? = null,
)

/** What the model guessed before a past app switch, and what actually happened. */
data class GuessRecord(
    val time: Long,
    val afterApp: String,
    val guesses: List<String>,
    val actual: String,
    /** Position of the actual app in the full ranking (1 = first guess), or null if not ranked. */
    val rank: Int? = null,
    /** The opened app had posted a notification just before. */
    val afterNotification: Boolean = false,
) {
    val hit: Boolean get() = guesses.firstOrNull() == actual
    val inTop3: Boolean get() = actual in guesses.take(3)
}

/** A notification posted by an app, for the "it just notified you" signal. */
data class NotificationEvent(
    val time: Long,
    val packageName: String,
)

/**
 * Predicts the next app from the apps just used, the time of day, notifications and recent
 * habits.
 *
 * Every candidate app gets a score that is a weighted sum of normalised signals (what
 * usually follows the current app, what follows the last two apps, what's usual at this hour
 * and on this kind of day, recent use, whether it just posted a notification, …). Signals are
 * kept with a long memory (half-life [HALF_LIFE_MS]) and some also with a short one
 * ([SHORT_HALF_LIFE_MS]), so a change of routine such as exam week shows up within a day or two.
 *
 * The weights are the hand-tuned blend from v1.3. Extended also has an online-learned ranker
 * ([Config.learn]: softmax or passive-aggressive updates after every switch) and extra signals
 * (short memory, kind of day, used earlier today). On two weeks of real data none of them beat
 * the tuned blend on held-out days (learned 31–34% right first time vs 34.5%), so they are off
 * by default and kept for the offline study. The notification signal is on: it couldn't be
 * tested offline, and switches into an app that just notified you are the easiest to predict.
 */
object NextAppModel {
    /** Screens that open on the way to something else rather than apps chosen on purpose. */
    val TRANSIENT_PACKAGES =
        setOf(
            "com.habitminer",
            "com.habitminer.extended",
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
    const val SHORT_HALF_LIFE_MS = 36 * TimeUtil.HOUR
    private const val RECENT_WINDOW_MS = 60 * TimeUtil.MINUTE
    private const val RECENT_DECAY_MS = 15.0 * TimeUtil.MINUTE
    const val NOTIFIED_WINDOW_MS = 10 * TimeUtil.MINUTE
    private const val NOTIFIED_DECAY_MS = 3.0 * TimeUtil.MINUTE
    private const val CANDIDATES_PER_SIGNAL = 12

    /** Signal names, in feature order (also used in exports and explanations). */
    val FEATURES =
        listOf(
            "follows current app",
            "follows current app (recent days)",
            "follows last two apps",
            "usual at this hour",
            "usual at this hour (recent days)",
            "usual at this hour on this kind of day",
            "used in the last hour",
            "used a lot overall",
            "going back to the previous app",
            "just sent a notification",
            "used earlier today",
        )
    private val NF = FEATURES.size

    /** Starting weights: the hand-tuned blend from v1.3, with the new signals small or off. */
    private val INITIAL_WEIGHTS = doubleArrayOf(1.0, 0.0, 0.5, 1.0, 0.0, 0.0, 2.0, 0.5, 1.0, 1.0, 0.0)

    data class Config(
        val learn: Boolean = false,
        val useShortMemory: Boolean = true,
        val useNotifications: Boolean = true,
        val learningRate: Double = 0.6,
        val temperature: Double = 4.0,
        val l2: Double = 0.0005,
        /** Passive-aggressive pairwise updates (fix the first guess) instead of softmax steps. */
        val pairwise: Boolean = true,
        /** Largest step a single switch can make (PA-I aggressiveness). */
        val aggressiveness: Double = 0.3,
        val margin: Double = 0.05,
        /** Starting weights in [FEATURES] order (null = the defaults). */
        val weights: List<Double>? = null,
    )

    private data class Switch(
        val before: String?,
        val from: String,
        val to: String,
        val time: Long,
        val hour: Int,
        val weekend: Boolean,
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
            // Staying in the same app isn't a prediction anyone needs.
            if (a.appName == b.appName) continue
            val z = TimeUtil.zoned(b.start, zone)
            out.add(Switch(before, a.appName, b.appName, b.start, z.hour, TimeUtil.isWeekend(z.toLocalDate())))
            before = a.appName
        }
        return out
    }

    /**
     * Decayed counts kept incrementally. Weights grow as exp(λ·(t − t0)) instead of shrinking
     * with age; every signal is normalised before use, so the common factor cancels out.
     */
    private class Memory(
        private val t0: Long,
        halfLifeMs: Long,
    ) {
        private val lambda = ln(2.0) / halfLifeMs
        val from = HashMap<String, HashMap<String, Double>>()
        val pair = HashMap<String, HashMap<String, Double>>()
        val hour = Array(24) { HashMap<String, Double>() }
        val dayTypeHour = Array(48) { HashMap<String, Double>() }
        val global = HashMap<String, Double>()
        var backHits = 0.0
        var backTotal = 0.0

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
            dayTypeHour[s.hour + if (s.weekend) 24 else 0].merge(s.to, w, Double::plus)
        }

        fun hourMix(
            h: Int,
            weekend: Boolean? = null,
        ): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (d in -1..1) {
                val weight = if (d == 0) 1.0 else 0.5
                val hh = Math.floorMod(h + d, 24)
                val m = if (weekend == null) hour[hh] else dayTypeHour[hh + if (weekend) 24 else 0]
                m.forEach { (app, v) -> out.merge(app, v * weight, Double::plus) }
            }
            return out
        }
    }

    private class Scored(
        val candidates: List<String>,
        val features: Array<DoubleArray>,
        val scores: DoubleArray,
    )

    private class Ranker(
        t0: Long,
        private val config: Config,
    ) {
        val long = Memory(t0, HALF_LIFE_MS)
        val short = Memory(t0, SHORT_HALF_LIFE_MS)
        val weights = config.weights?.toDoubleArray() ?: INITIAL_WEIGHTS.copyOf()
        private var updates = 0

        /** When each app was last in use, for "used earlier today". */
        private val lastSeen = HashMap<String, Long>()

        fun observe(s: Switch) {
            long.observe(s)
            short.observe(s)
            lastSeen[s.to] = s.time
            lastSeen[s.from] = maxOf(lastSeen[s.from] ?: 0L, s.time)
        }

        fun score(
            before: String?,
            current: String,
            hourOfDay: Int,
            weekend: Boolean,
            time: Long,
            recent: Map<String, Double>,
            notified: Map<String, Double>,
        ): Scored {
            val shortOn = config.useShortMemory
            val signals: Array<Map<String, Double>> =
                arrayOf(
                    normalise(long.from[current]),
                    if (shortOn) normalise(short.from[current]) else emptyMap(),
                    normalise(before?.let { long.pair["$it\u0000$current"] }),
                    normalise(long.hourMix(hourOfDay)),
                    if (shortOn) normalise(short.hourMix(hourOfDay)) else emptyMap(),
                    normalise(long.hourMix(hourOfDay, weekend)),
                    normalise(recent),
                    normalise(long.global),
                    emptyMap(),
                    if (config.useNotifications) notified else emptyMap(),
                    emptyMap(),
                )
            val back = if (long.backTotal > 0) long.backHits / long.backTotal else 0.0
            val candidates =
                (
                    signals.flatMap { m -> m.entries.sortedByDescending { it.value }.take(CANDIDATES_PER_SIGNAL).map { it.key } } +
                        listOfNotNull(before)
                ).distinct().filter { it != current }
            val features =
                Array(candidates.size) { ci ->
                    val app = candidates[ci]
                    DoubleArray(NF) { k ->
                        when (k) {
                            8 -> if (app == before) back else 0.0
                            10 -> lastSeen[app]?.let { exp(-(time - it) / (6.0 * TimeUtil.HOUR)) } ?: 0.0
                            else -> signals[k][app] ?: 0.0
                        }
                    }
                }
            val scores = DoubleArray(candidates.size) { ci -> dot(weights, features[ci]) }
            return Scored(candidates, features, scores)
        }

        /** Learns from one switch: the opened app should have scored above the others. */
        fun learn(
            scored: Scored,
            actual: String,
        ) {
            if (!config.learn) return
            val idx = scored.candidates.indexOf(actual)
            if (idx < 0 || scored.candidates.size < 2) return
            if (config.pairwise) {
                learnPairwise(scored, idx)
                return
            }
            val z = DoubleArray(scored.scores.size) { scored.scores[it] * config.temperature }
            val max = z.maxOrNull() ?: 0.0
            val e = DoubleArray(z.size) { exp(z[it] - max) }
            val sum = e.sum()
            val rate = config.learningRate / sqrt(1.0 + updates / 300.0)
            for (k in 0 until NF) {
                var expected = 0.0
                for (ci in scored.candidates.indices) expected += (e[ci] / sum) * scored.features[ci][k]
                val grad = scored.features[idx][k] - expected
                weights[k] = (weights[k] + rate * grad - rate * config.l2 * weights[k]).coerceIn(0.0, 6.0)
            }
            updates++
        }

        /**
         * Passive-aggressive (PA-I): if the best wrong app scored within [Config.margin] of the
         * opened one, move the weights just enough to fix it, at most [Config.aggressiveness].
         * Correct guesses by a clear margin change nothing, so good weights stay put.
         */
        private fun learnPairwise(
            scored: Scored,
            idx: Int,
        ) {
            var wrong = -1
            for (ci in scored.candidates.indices) {
                if (ci == idx) continue
                if (wrong < 0 || scored.scores[ci] > scored.scores[wrong]) wrong = ci
            }
            if (wrong < 0) return
            val loss = config.margin - (scored.scores[idx] - scored.scores[wrong])
            if (loss <= 0.0) return
            val diff = DoubleArray(NF) { k -> scored.features[idx][k] - scored.features[wrong][k] }
            val norm = diff.sumOf { it * it }
            if (norm <= 1e-12) return
            val tau = minOf(config.aggressiveness, loss / norm)
            for (k in 0 until NF) weights[k] = (weights[k] + tau * diff[k]).coerceIn(0.0, 6.0)
            updates++
        }

        private fun dot(
            w: DoubleArray,
            f: DoubleArray,
        ): Double {
            var s = 0.0
            for (k in w.indices) s += w[k] * f[k]
            return s
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
        startIndex: Int,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (i in startIndex until sorted.size) {
            val s = sorted[i]
            if (s.start >= time) break
            val age = time - s.end
            if (age < 0 || age > RECENT_WINDOW_MS) continue
            val weight = exp(-age / RECENT_DECAY_MS) * minOf(1.0, s.durationMs / 60_000.0 + 0.2)
            out.merge(s.appName, weight, Double::plus)
        }
        return out
    }

    /** Apps that posted a notification shortly before [time], fresher ones higher (0–1 each). */
    private fun notifiedApps(
        notes: List<NotificationEvent>,
        time: Long,
        startIndex: Int,
        appOfPackage: Map<String, String>,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (i in startIndex until notes.size) {
            val n = notes[i]
            if (n.time >= time) break
            val age = time - n.time
            if (age > NOTIFIED_WINDOW_MS) continue
            val app = appOfPackage[n.packageName] ?: continue
            out[app] = maxOf(out[app] ?: 0.0, exp(-age / NOTIFIED_DECAY_MS))
        }
        return out
    }

    /** A moving start index into a time-sorted list, so lookups stay linear overall. */
    private class Cursor(private val windowMs: Long) {
        private var index = 0

        fun <T> advance(
            items: List<T>,
            time: Long,
            timeOf: (T) -> Long,
        ): Int {
            while (index < items.size && timeOf(items[index]) < time - windowMs) index++
            return index
        }
    }

    /** Sessions can be long, so the recent-use cursor trails by a few hours more than the window. */
    private const val RECENT_CURSOR_MS = RECENT_WINDOW_MS + 4 * TimeUtil.HOUR

    /**
     * Likely next apps right now, after the most recent app. Empty when there is too little
     * history to say anything.
     */
    fun predict(
        sessions: List<UsageSession>,
        now: Long,
        zone: ZoneId,
        count: Int = 3,
        notifications: List<NotificationEvent> = emptyList(),
        config: Config = Config(),
    ): List<AppGuess> {
        val usable = sessions.filterNot { isTransient(it.packageName) }.filter { it.start <= now }.sortedBy { it.start }
        val all = switches(usable, zone)
        if (all.size < 20) return emptyList()
        val ranker = Ranker(all.first().time, config)
        val appOf = usable.associate { it.packageName to it.appName }
        val notes = notifications.sortedBy { it.time }
        val recentCursor = Cursor(RECENT_CURSOR_MS)
        val noteCursor = Cursor(NOTIFIED_WINDOW_MS)
        // Replay history so the weights are learned from everything seen so far.
        for (s in all) {
            if (config.learn) {
                val scored =
                    ranker.score(
                        s.before, s.from, s.hour, s.weekend, s.time,
                        recentUse(usable, s.time, recentCursor.advance(usable, s.time) { it.start }),
                        notifiedApps(notes, s.time, noteCursor.advance(notes, s.time) { it.time }, appOf),
                    )
                ranker.learn(scored, s.to)
            }
            ranker.observe(s)
        }
        val current = usable.lastOrNull() ?: return emptyList()
        val previous = usable.lastOrNull { it.end <= current.start && it.appName != current.appName }
        val before = previous?.takeIf { current.start - it.end <= MAX_GAP_MS }?.appName
        val z = TimeUtil.zoned(now, zone)
        val scored =
            ranker.score(
                before, current.appName, z.hour, TimeUtil.isWeekend(z.toLocalDate()), now,
                recentUse(usable, now, recentCursor.advance(usable, now) { it.start }),
                notifiedApps(notes, now, 0, appOf),
            )
        val ranked = scored.candidates.indices.sortedByDescending { scored.scores[it] }.filter { scored.scores[it] > 0.0 }
        val total = ranked.sumOf { scored.scores[it] }.takeIf { it > 0 } ?: return emptyList()
        val packageOf = usable.associate { it.appName to it.packageName }
        return ranked.take(count).map { i -> AppGuess(scored.candidates[i], (scored.scores[i] / total).toFloat(), packageOf[scored.candidates[i]]) }
    }

    /** The app the predictions are for: the last app used that the model counts. */
    fun currentApp(sessions: List<UsageSession>): String? = sessions.filterNot { isTransient(it.packageName) }.maxByOrNull { it.start }?.appName

    data class Evaluation(
        val result: PredictabilityResult?,
        /** Guesses for the most recent switches, newest first. */
        val recent: List<GuessRecord>,
        /** Every tested switch in the test window, oldest first (for export). */
        val tested: List<GuessRecord> = emptyList(),
        /** The learned weight of each signal after the replay, in [FEATURES] order. */
        val weights: List<Double> = emptyList(),
        /** When the model's accuracy changed sharply (a sign of a routine change), if it did. */
        val driftAt: Long? = null,
    )

    /**
     * Replays history in time order: before each switch in the last [testDays] days the model
     * guesses, then learns from what happened (test-then-train), and simple baselines guess
     * too, so the numbers are comparable.
     */
    fun evaluate(
        sessions: List<UsageSession>,
        now: Long,
        zone: ZoneId,
        testDays: Int = 3,
        keepRecent: Int = 12,
        notifications: List<NotificationEvent> = emptyList(),
        config: Config = Config(),
    ): Evaluation {
        val usable = sessions.filterNot { isTransient(it.packageName) }.filter { it.start < now }.sortedBy { it.start }
        val all = switches(usable, zone)
        if (all.isEmpty()) return Evaluation(null, emptyList())
        val testStart = TimeUtil.startOfDay(TimeUtil.dateOf(now, zone).minusDays((testDays - 1).toLong()), zone)
        val ranker = Ranker(all.first().time, config)
        val appOf = usable.associate { it.packageName to it.appName }
        val notes = notifications.sortedBy { it.time }
        val recentCursor = Cursor(RECENT_CURSOR_MS)
        val noteCursor = Cursor(NOTIFIED_WINDOW_MS)
        // Baselines use plain counts without decay.
        val markov = HashMap<String, HashMap<String, Int>>()
        val counts = HashMap<String, Int>()
        val drift = Adwin()
        var driftAt: Long? = null

        var trained = 0
        var tested = 0
        var hits = 0
        var hits3 = 0
        var hits5 = 0
        var reciprocal = 0.0
        var baseMostUsed = 0
        var baseRecent = 0
        var baseMarkov = 0
        var notifTested = 0
        var notifHits = 0
        val recent = ArrayDeque<GuessRecord>()
        val testedRecords = mutableListOf<GuessRecord>()
        for (s in all) {
            val notified = notifiedApps(notes, s.time, noteCursor.advance(notes, s.time) { it.time }, appOf)
            val scored =
                ranker.score(
                    s.before, s.from, s.hour, s.weekend, s.time,
                    recentUse(usable, s.time, recentCursor.advance(usable, s.time) { it.start }),
                    notified,
                )
            if (s.time >= testStart && trained >= PredictabilityEvaluator.MIN_TEST_TRANSITIONS) {
                val ranked = scored.candidates.indices.sortedByDescending { scored.scores[it] }.map { scored.candidates[it] }
                val rank = ranked.indexOf(s.to).takeIf { it >= 0 }?.plus(1)
                tested++
                if (rank == 1) hits++
                if (rank != null && rank <= 3) hits3++
                if (rank != null && rank <= 5) hits5++
                if (rank != null) reciprocal += 1.0 / rank
                val afterNote = (notified[s.to] ?: 0.0) > 0.0
                if (afterNote) {
                    notifTested++
                    if (rank == 1) notifHits++
                }
                if (counts.entries.filter { it.key != s.from }.maxByOrNull { it.value }?.key == s.to) baseMostUsed++
                // Most recent: going back to the app used just before the current one.
                if (s.before != null && s.before == s.to) baseRecent++
                if (markov[s.from]?.entries?.maxByOrNull { it.value }?.key == s.to) baseMarkov++
                val record = GuessRecord(s.time, s.from, ranked.take(3), s.to, rank, afterNote)
                recent.addLast(record)
                testedRecords += record
                if (recent.size > keepRecent) recent.removeFirst()
                if (drift.add(if (rank == 1) 1.0 else 0.0) && driftAt == null) driftAt = s.time
            }
            ranker.learn(scored, s.to)
            ranker.observe(s)
            markov.getOrPut(s.from) { HashMap() }.merge(s.to, 1, Int::plus)
            counts.merge(s.to, 1, Int::plus)
            trained++
        }
        val vocab = (all.map { it.to } + all.map { it.from }).distinct().size.coerceAtLeast(2)
        val result =
            if (tested >= PredictabilityEvaluator.MIN_TEST_TRANSITIONS) {
                PredictabilityResult(
                    hitRate = hits.toFloat() / tested,
                    top3HitRate = hits3.toFloat() / tested,
                    mostUsedBaseline = baseMostUsed.toFloat() / tested,
                    randomBaseline = 1f / (vocab - 1),
                    testedTransitions = tested,
                    testDays = testDays,
                    top5HitRate = hits5.toFloat() / tested,
                    meanReciprocalRank = (reciprocal / tested).toFloat(),
                    markovBaseline = baseMarkov.toFloat() / tested,
                    recentBaseline = baseRecent.toFloat() / tested,
                    notificationSwitches = notifTested,
                    notificationHitRate = if (notifTested > 0) notifHits.toFloat() / notifTested else null,
                    selfStartedHitRate = if (tested - notifTested > 0) (hits - notifHits).toFloat() / (tested - notifTested) else null,
                )
            } else {
                null
            }
        return Evaluation(result, recent.reversed(), testedRecords, ranker.weights.toList(), driftAt)
    }
}

/**
 * ADWIN-style change detector on a stream of 0/1 outcomes: keeps a window and drops its older
 * part when that part's mean differs from the newer part's by more than chance allows
 * (Hoeffding bound). [add] returns true when it cut the window, i.e. the stream changed.
 */
class Adwin(
    private val delta: Double = 0.002,
    private val maxWindow: Int = 600,
    private val minSide: Int = 30,
) {
    private val window = ArrayDeque<Double>()

    val size: Int get() = window.size

    fun add(x: Double): Boolean {
        window.addLast(x)
        if (window.size > maxWindow) window.removeFirst()
        val n = window.size
        if (n < 2 * minSide) return false
        val total = window.sum()
        var head = 0.0
        var cut = -1
        for (i in 0 until n - minSide) {
            head += window[i]
            val n0 = i + 1
            if (n0 < minSide) continue
            val n1 = n - n0
            val m0 = head / n0
            val m1 = (total - head) / n1
            val m = 1.0 / (1.0 / n0 + 1.0 / n1)
            val eps = sqrt((1.0 / (2 * m)) * ln(4.0 * n / delta))
            if (kotlin.math.abs(m0 - m1) > eps) cut = n0
        }
        if (cut > 0) {
            repeat(cut) { window.removeFirst() }
            return true
        }
        return false
    }
}
