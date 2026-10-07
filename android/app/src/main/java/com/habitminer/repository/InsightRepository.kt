package com.habitminer.repository

import android.content.Context
import androidx.compose.runtime.Immutable
import com.habitminer.analytics.Insight
import com.habitminer.analytics.InsightFamily
import com.habitminer.analytics.InsightFrequency
import com.habitminer.analytics.InsightHistory
import com.habitminer.analytics.InsightPicker
import com.habitminer.analytics.QuietHours
import com.habitminer.data.InsightLogDao
import com.habitminer.data.InsightLogEntity
import com.habitminer.data.PrefsKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** How insights reach you, from Settings. */
@Immutable
data class InsightSettings(
    val frequency: InsightFrequency = InsightFrequency.STANDOUT,
    val disabled: Set<InsightFamily> = emptySet(),
    /** Notifications only between these hours. */
    val windowStartHour: Int = 9,
    val windowEndHour: Int = 21,
    /** No questions, insights or summaries in these hours. */
    val quiet: QuietHours = QuietHours(),
    val explainerSeen: Boolean = false,
) {
    val enabled: Set<InsightFamily> get() = InsightFamily.entries.toSet() - disabled
}

/** Today's insight as shown on Today and in the notification. */
@Immutable
data class TodayInsight(
    val id: Long,
    val key: String,
    val family: InsightFamily,
    val title: String,
    val body: String,
    val why: String,
    val effect: Float,
    val open: String,
    val notifiedAt: Long?,
    val feedback: String?,
)

/** Picks, stores and remembers feedback on the insight of the day; holds the insight settings. */
@Singleton
class InsightRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: InsightLogDao,
    ) {
        private val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        private val mutex = Mutex()
        private val _settings = MutableStateFlow(read())
        val settings: StateFlow<InsightSettings> = _settings.asStateFlow()

        private fun read(): InsightSettings =
            InsightSettings(
                frequency = InsightFrequency.of(prefs.getString(PrefsKeys.INSIGHT_FREQUENCY, null)),
                disabled = prefs.getStringSet(PrefsKeys.INSIGHT_FAMILIES_OFF, emptySet()).orEmpty().mapNotNull(InsightFamily::of).toSet(),
                windowStartHour = prefs.getInt(PrefsKeys.INSIGHT_WINDOW_START, 9),
                windowEndHour = prefs.getInt(PrefsKeys.INSIGHT_WINDOW_END, 21),
                quiet = QuietHours(prefs.getInt(PrefsKeys.QUIET_START, 22 * 60), prefs.getInt(PrefsKeys.QUIET_END, 8 * 60)),
                explainerSeen = prefs.getBoolean(PrefsKeys.INSIGHT_EXPLAINER_SEEN, false),
            )

        fun update(transform: (InsightSettings) -> InsightSettings) {
            val next = transform(_settings.value)
            prefs.edit()
                .putString(PrefsKeys.INSIGHT_FREQUENCY, next.frequency.name)
                .putStringSet(PrefsKeys.INSIGHT_FAMILIES_OFF, next.disabled.map { it.name }.toSet())
                .putInt(PrefsKeys.INSIGHT_WINDOW_START, next.windowStartHour)
                .putInt(PrefsKeys.INSIGHT_WINDOW_END, next.windowEndHour)
                .putInt(PrefsKeys.QUIET_START, next.quiet.startMinute)
                .putInt(PrefsKeys.QUIET_END, next.quiet.endMinute)
                .putBoolean(PrefsKeys.INSIGHT_EXPLAINER_SEEN, next.explainerSeen)
                .apply()
            _settings.value = next
        }

        /**
         * Today's insight: the one already picked today, or a new pick from [candidates] (logged
         * so it stays the same all day). Null when insights are off or nothing stands out.
         */
        suspend fun todaysInsight(
            candidates: List<Insight>,
            today: LocalDate,
            now: Long,
        ): TodayInsight? =
            mutex.withLock {
                val s = _settings.value
                if (s.frequency == InsightFrequency.OFF) return@withLock null
                dao.forDate(today.toString())?.let { row ->
                    val family = InsightFamily.of(row.family) ?: return@withLock null
                    return@withLock if (family in s.enabled) row.toToday(family) else null
                }
                val history =
                    dao.since(today.minusDays(60).toString()).mapNotNull { r ->
                        InsightFamily.of(r.family)?.let { InsightHistory(LocalDate.parse(r.date), it, r.key, r.feedback) }
                    }
                val pick = InsightPicker.pick(candidates, history, s.enabled, today) ?: return@withLock null
                val i = pick.insight
                val row =
                    InsightLogEntity(
                        timestamp = now,
                        date = today.toString(),
                        family = i.family.name,
                        key = i.key,
                        title = i.title,
                        body = i.body,
                        why = i.why,
                        open = i.open,
                        effect = i.effect,
                        score = pick.score.toFloat(),
                        probability = pick.probability.toFloat(),
                    )
                val id = dao.insert(row)
                row.copy(id = id).toToday(i.family)
            }

        suspend fun feedback(
            key: String,
            value: String?,
        ) = dao.setFeedback(key, value, System.currentTimeMillis())

        suspend fun markOpened(key: String) = dao.markOpened(key, System.currentTimeMillis())

        suspend fun markNotified(id: Long) = dao.markNotified(id, System.currentTimeMillis())

        suspend fun all(): List<InsightLogEntity> = dao.all()

        suspend fun clearOldData(cutoffMs: Long) = dao.deleteOlderThan(cutoffMs)

        suspend fun clearAll() = dao.deleteAll()

        private fun InsightLogEntity.toToday(family: InsightFamily) = TodayInsight(id, key, family, title, body, why, effect, open, notifiedAt, feedback)
    }
