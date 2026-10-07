package com.habitminer.collection

import android.content.Context
import android.content.Intent
import com.habitminer.data.DeviceEventEntity
import com.habitminer.data.PrefsKeys
import com.habitminer.repository.ContextRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What HabitMiner records, as set in Settings. */
data class RecordingState(
    val paused: Boolean = false,
    /** Resumes by itself at this time; null = until you resume. */
    val pausedUntil: Long? = null,
    /** On a break until the end of this day: no notifications, and the days don't count as usual. */
    val breakUntil: LocalDate? = null,
    val breakFrom: LocalDate? = null,
    val light: Boolean = true,
    val motion: Boolean = true,
    val steps: Boolean = true,
    val notifications: Boolean = true,
    /** Do Not Disturb, alarm, charger, headphones, activity, time zone, app installs. */
    val phoneState: Boolean = true,
)

/**
 * Pausing, breaks and per-source switches. Pausing stops all recording; when it ends, the
 * usage log for the paused time is skipped rather than read back, so a pause really is a pause.
 */
@Singleton
class RecordingControl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contextRepository: ContextRepository,
        private val usageIngestor: UsageIngestor,
    ) {
        private val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        private val _state = MutableStateFlow(read())
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        private fun read() =
            RecordingState(
                paused = prefs.getBoolean(PrefsKeys.MONITORING_PAUSED, false),
                pausedUntil = prefs.getLong(PrefsKeys.PAUSED_UNTIL, -1L).takeIf { it > 0 },
                breakUntil = prefs.getString(PrefsKeys.BREAK_UNTIL, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                breakFrom = prefs.getString(PrefsKeys.BREAK_FROM, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                light = prefs.getBoolean(PrefsKeys.SOURCE_LIGHT, true),
                motion = prefs.getBoolean(PrefsKeys.SOURCE_MOTION, true),
                steps = prefs.getBoolean(PrefsKeys.SOURCE_STEPS, true),
                notifications = prefs.getBoolean(PrefsKeys.SOURCE_NOTIFICATIONS, true),
                phoneState = prefs.getBoolean(PrefsKeys.SOURCE_PHONE_STATE, true),
            )

        private fun refresh() {
            _state.value = read()
        }

        /** True while paused; a timed pause that has run out resumes here. */
        suspend fun isPaused(now: Long = System.currentTimeMillis()): Boolean {
            val s = _state.value
            if (!s.paused) return false
            if (s.pausedUntil != null && now >= s.pausedUntil) {
                resume()
                return false
            }
            return true
        }

        /** Same as [isPaused] without resuming; for code that can't suspend. */
        fun isPausedNow(now: Long = System.currentTimeMillis()): Boolean {
            val s = _state.value
            return s.paused && (s.pausedUntil == null || now < s.pausedUntil)
        }

        suspend fun pause(untilMs: Long?) {
            if (_state.value.paused) {
                prefs.edit().putLong(PrefsKeys.PAUSED_UNTIL, untilMs ?: -1L).apply()
                refresh()
                return
            }
            prefs.edit().putBoolean(PrefsKeys.MONITORING_PAUSED, true).putLong(PrefsKeys.PAUSED_UNTIL, untilMs ?: -1L).apply()
            refresh()
            contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = DeviceEvents.PAUSED))
            notifyService()
        }

        suspend fun resume() {
            if (!_state.value.paused) return
            prefs.edit().putBoolean(PrefsKeys.MONITORING_PAUSED, false).putLong(PrefsKeys.PAUSED_UNTIL, -1L).apply()
            refresh()
            // Don't read back what Android logged while paused.
            usageIngestor.skipTo(System.currentTimeMillis())
            contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = DeviceEvents.RESUMED))
            notifyService()
        }

        fun startBreak(
            from: LocalDate,
            until: LocalDate,
        ) {
            prefs.edit().putString(PrefsKeys.BREAK_FROM, from.toString()).putString(PrefsKeys.BREAK_UNTIL, until.toString())
                .remove(PrefsKeys.WELCOME_BACK_SHOWN).apply()
            refresh()
        }

        fun endBreak(today: LocalDate) {
            val s = _state.value
            if (s.breakUntil == null) return
            // Ending early: the break is over as of yesterday.
            prefs.edit().putString(PrefsKeys.BREAK_UNTIL, maxOf(s.breakFrom ?: today, today.minusDays(1)).toString()).apply()
            refresh()
        }

        fun onBreak(today: LocalDate): Boolean {
            val s = _state.value
            return s.breakFrom != null && s.breakUntil != null && !today.isBefore(s.breakFrom) && !today.isAfter(s.breakUntil)
        }

        /** The break that just ended, if its welcome-back summary hasn't been shown yet. */
        fun pendingWelcomeBack(today: LocalDate): Pair<LocalDate, LocalDate>? {
            val s = _state.value
            val from = s.breakFrom ?: return null
            val until = s.breakUntil ?: return null
            if (!today.isAfter(until) || today.isAfter(until.plusDays(3))) return null
            if (prefs.getString(PrefsKeys.WELCOME_BACK_SHOWN, null) == until.toString()) return null
            return from to until
        }

        fun welcomeBackShown() {
            _state.value.breakUntil?.let { prefs.edit().putString(PrefsKeys.WELCOME_BACK_SHOWN, it.toString()).apply() }
        }

        fun setSources(change: (RecordingState) -> RecordingState) {
            val next = change(_state.value)
            prefs.edit()
                .putBoolean(PrefsKeys.SOURCE_LIGHT, next.light)
                .putBoolean(PrefsKeys.SOURCE_MOTION, next.motion)
                .putBoolean(PrefsKeys.SOURCE_STEPS, next.steps)
                .putBoolean(PrefsKeys.SOURCE_NOTIFICATIONS, next.notifications)
                .putBoolean(PrefsKeys.SOURCE_PHONE_STATE, next.phoneState)
                .apply()
            refresh()
            notifyService()
        }

        /** Lets the running service update its notification and listeners. */
        private fun notifyService() {
            if (!MonitoringService.isServiceRunning.value) return
            runCatching { context.startService(Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_REFRESH)) }
        }
    }
