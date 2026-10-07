package com.habitminer.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.habitminer.analytics.PhoneFree
import com.habitminer.analytics.TimeUtil
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.engine.AnalysisRepository
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.repository.ContextRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget shows, saved so it can draw without touching the database. */
data class WidgetData(
    val screenMs: Long,
    val usualByNowMs: Long?,
    val unlocks: Int,
    val phoneFreeMs: Long?,
    val insightTitle: String?,
    val updatedAt: Long,
)

object WidgetStore {
    private const val PREFS = "widget"

    fun save(
        context: Context,
        d: WidgetData,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("screen", d.screenMs)
            .putLong("usual", d.usualByNowMs ?: -1L)
            .putInt("unlocks", d.unlocks)
            .putLong("free", d.phoneFreeMs ?: -1L)
            .putString("insight", d.insightTitle)
            .putLong("at", d.updatedAt)
            .apply()
    }

    fun load(context: Context): WidgetData? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L).takeIf { it > 0 } ?: return null
        // Yesterday's numbers would mislead; show zeros until the first update today.
        val zone = ZoneId.systemDefault()
        if (TimeUtil.dateOf(at, zone) != TimeUtil.dateOf(System.currentTimeMillis(), zone)) return WidgetData(0, null, 0, null, null, at)
        return WidgetData(
            screenMs = p.getLong("screen", 0L),
            usualByNowMs = p.getLong("usual", -1L).takeIf { it >= 0 },
            unlocks = p.getInt("unlocks", 0),
            phoneFreeMs = p.getLong("free", -1L).takeIf { it >= 0 },
            insightTitle = p.getString("insight", null),
            updatedAt = at,
        )
    }
}

/** Recomputes the widget's numbers from the database and redraws it, if one is placed. */
@Singleton
class WidgetUpdater
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val appIdentityResolver: AppIdentityResolver,
        private val analysisRepository: AnalysisRepository,
    ) {
        private val mutex = Mutex()

        @Volatile private var lastUpdate = 0L

        suspend fun refresh(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUpdate < MIN_INTERVAL_MS) return
            mutex.withLock {
                val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java) }.getOrDefault(emptyList())
                if (ids.isEmpty()) return
                lastUpdate = now
                val zone = ZoneId.systemDefault()
                val startOfDay = TimeUtil.startOfDay(TimeUtil.dateOf(now, zone), zone)
                val sessions = contextRepository.getUsageSince(startOfDay).filterNot { appIdentityResolver.isLauncher(it.packageName) }
                val unlocks = contextRepository.unlockTimesSince(startOfDay)
                val bundle = analysisRepository.bundle.value?.takeIf { TimeUtil.dateOf(it.computedAt, zone) == TimeUtil.dateOf(now, zone) }
                // Usual by now: the usual day's curve at this time of day.
                val usual =
                    bundle?.typicalDay?.median?.takeIf { it.size == 25 }?.let { median ->
                        val hours = (now - startOfDay) / 3_600_000.0
                        val h = hours.toInt().coerceIn(0, 23)
                        val f = hours - h
                        ((median[h] + (median[h + 1] - median[h]) * f) * 60_000L).toLong()
                    }
                val wake = bundle?.lastNight?.takeIf { TimeUtil.dateOf(it.wakeTime, zone) == TimeUtil.dateOf(now, zone) }?.wakeTime ?: (startOfDay + 7 * TimeUtil.HOUR)
                val free = PhoneFree.longestToday(sessions.map(AnalyticsMappers::session), unlocks, wake, now)?.durationMs
                WidgetStore.save(
                    context,
                    WidgetData(
                        screenMs = sessions.filter { it.startTime >= startOfDay }.sumOf { it.durationMs },
                        usualByNowMs = usual,
                        unlocks = unlocks.size,
                        phoneFreeMs = free,
                        insightTitle = bundle?.insight?.title,
                        updatedAt = now,
                    ),
                )
                runCatching { TodayWidget().updateAll(context) }
            }
        }

        companion object {
            private const val MIN_INTERVAL_MS = 5 * 60_000L
        }
    }
