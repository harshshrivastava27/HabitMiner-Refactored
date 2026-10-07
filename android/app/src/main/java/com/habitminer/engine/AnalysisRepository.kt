package com.habitminer.engine

import com.habitminer.analytics.TimeUtil
import com.habitminer.repository.ContextRepository
import com.habitminer.repository.FeedbackRepository
import com.habitminer.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One analysis of the last five weeks, shared by every screen and the background prompts.
 *
 * Before Extended the Today screen, the Insights screen and the prompt engine each ran the
 * full analysis (sleep over 30 nights, deviations, naps, the next-app model) on their own,
 * each after loading every stored session, and the screens did it again every 5 minutes
 * even with nothing new. Now it is computed once and reused until the data, the labels or the
 * date change, or it gets older than the caller allows.
 */
@Singleton
class AnalysisRepository
    @Inject
    constructor(
        private val contextRepository: ContextRepository,
        private val habitRepository: HabitRepository,
        private val feedbackRepository: FeedbackRepository,
        private val insightsComputer: InsightsComputer,
    ) {
        private val mutex = Mutex()
        private val _bundle = MutableStateFlow<InsightsBundle?>(null)

        /** The latest analysis, or null before the first one finishes. */
        val bundle: StateFlow<InsightsBundle?> = _bundle.asStateFlow()

        @Volatile private var key: String? = null

        @Volatile private var computedAt = 0L

        private var storedDeviationsHash: Int? = null

        /**
         * The current analysis, recomputed only if the inputs changed or it is older than
         * [maxAgeMs]. Time-dependent parts ("so far today") drift slowly, so a few minutes of
         * staleness is fine for screens; prompts allow ten.
         */
        suspend fun get(
            maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
            force: Boolean = false,
        ): InsightsBundle? =
            mutex.withLock {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val today = TimeUtil.dateOf(now, zone)
                val windowStart = TimeUtil.startOfDay(today.minusDays(InsightsComputer.LOOKBACK_DAYS), zone)

                val labels = feedbackRepository.getAllLabels().first()
                val places = feedbackRepository.getPlaces().first()
                val habits = habitRepository.getAllHabits().first()
                val newKey =
                    listOf(
                        today.toString(),
                        contextRepository.dataRevisionSince(windowStart),
                        labels.size.toString(),
                        labels.sumOf { it.timestamp xor it.value.hashCode().toLong() }.toString(),
                        places.joinToString { "${it.placeHash}=${it.label}" }.hashCode().toString(),
                        habits.size.toString(),
                    ).joinToString("|")

                val current = _bundle.value
                if (!force && current != null && newKey == key && now - computedAt < maxAgeMs) return@withLock current

                val usage = contextRepository.getUsageSince(windowStart)
                val snapshots = contextRepository.getSnapshotsSince(windowStart)
                val result =
                    withContext(Dispatchers.Default) {
                        ensureActive()
                        runCatching { insightsComputer.compute(usage, snapshots, habits, places, labels, now, zone) }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                            .onFailure { android.util.Log.e("HabitMiner", "Analysis failed", it) }
                            .getOrNull()
                    } ?: return@withLock current

                key = newKey
                computedAt = now
                _bundle.value = result
                storeDeviationsIfChanged(result, zone)
                result
            }

        /** Answers change what should be asked or shown next. */
        fun invalidate() {
            key = null
        }

        /** Keeps the deviations table in step with what the app shows (it's in the export). */
        private suspend fun storeDeviationsIfChanged(
            bundle: InsightsBundle,
            zone: ZoneId,
        ) {
            val rows =
                bundle.deviations.days.map { d ->
                    com.habitminer.data.DeviationEntity(
                        timestamp = d.occurredAt,
                        timeBin = "DAY",
                        deviationType = d.kind.name,
                        description = "${d.title}. ${d.detail}" + (d.explainedBy?.let { " ($it)" } ?: ""),
                        zScore = d.score,
                        normalizedScore = (d.score / 6f).coerceIn(0f, 1f),
                        affectedCategory = d.subject,
                    )
                }
            val hash = rows.map { Triple(it.timestamp, it.deviationType, it.description) }.hashCode()
            if (hash == storedDeviationsHash) return
            runCatching {
                val since = TimeUtil.startOfDay(TimeUtil.dateOf(bundle.computedAt, zone).minusDays(7), zone)
                habitRepository.replaceDeviationsSince(since, rows)
                storedDeviationsHash = hash
            }.onFailure { android.util.Log.w("HabitMiner", "Could not store deviations", it) }
        }

        companion object {
            const val DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L
        }
    }
