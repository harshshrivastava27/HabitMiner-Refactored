package com.habitminer.repository

import android.content.Context
import com.habitminer.analytics.PromptKind
import com.habitminer.analytics.SentPrompt
import com.habitminer.data.LabelDao
import com.habitminer.data.PlaceDao
import com.habitminer.data.PlaceEntity
import com.habitminer.data.PrefsKeys
import com.habitminer.data.UserLabelEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** User-provided labels (check-ins, deviation feedback), Wi-Fi places and prompt history. */
@Singleton
class FeedbackRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val labelDao: LabelDao,
        private val placeDao: PlaceDao,
    ) {
        private val prefs get() = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // ---- Labels -----------------------------------------------------------------------

        fun getAllLabels(): Flow<List<UserLabelEntity>> = labelDao.getAll()

        fun getDeviationFeedback(): Flow<List<UserLabelEntity>> = labelDao.getByKind(UserLabelEntity.KIND_DEVIATION_FEEDBACK)

        fun getCheckIns(): Flow<List<UserLabelEntity>> = labelDao.getByKind(UserLabelEntity.KIND_CHECK_IN)

        /** Nap and period answers, read once (for background prompts). */
        suspend fun napAndPeriodLabels(): List<UserLabelEntity> =
            labelDao.getByKindOnce(UserLabelEntity.KIND_NAP) + labelDao.getByKindOnce(UserLabelEntity.KIND_PERIOD)

        suspend fun saveCheckIn(
            value: String,
            promptedAt: Long?,
            contextJson: String?,
        ) {
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_CHECK_IN,
                    value = value,
                    promptedAt = promptedAt,
                    contextJson = contextJson,
                ),
            )
        }

        /** Mood and energy (1–5 each) right after a check-in. */
        suspend fun saveMood(
            mood: Int?,
            energy: Int?,
        ) {
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_MOOD,
                    value = listOfNotNull(mood?.let { "mood=$it" }, energy?.let { "energy=$it" }).joinToString(";"),
                ),
            )
        }

        /** One answer per deviation: a new answer replaces the previous one. */
        suspend fun saveDeviationFeedback(
            fingerprint: String,
            value: String,
            contextJson: String?,
        ) {
            labelDao.deleteByRef(UserLabelEntity.KIND_DEVIATION_FEEDBACK, fingerprint)
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_DEVIATION_FEEDBACK,
                    value = value,
                    refKey = fingerprint,
                    contextJson = contextJson,
                ),
            )
        }

        /**
         * Your own times for the night that ended on [wakeDate] ([start] and [end] null means
         * "that wasn't sleep"). Replaces an earlier fix of the same night.
         */
        suspend fun saveSleepFix(
            wakeDate: java.time.LocalDate,
            start: Long?,
            end: Long?,
            contextJson: String?,
        ) {
            val key = com.habitminer.engine.LabelMappers.sleepFixKey(wakeDate)
            labelDao.deleteByRef(UserLabelEntity.KIND_SLEEP_FIX, key)
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_SLEEP_FIX,
                    value = if (start == null || end == null) UserLabelEntity.SLEEP_NONE else "$start|$end",
                    refKey = key,
                    contextJson = contextJson,
                ),
            )
        }

        /** Replaces the copied Health Connect sleep with a fresh read. */
        suspend fun replaceHealthSleep(result: com.habitminer.analytics.ExternalSleepMapper.Result) {
            val now = System.currentTimeMillis()
            fun json(
                nap: Boolean,
                awake: List<Pair<Long, Long>>,
            ) = """{"nap":$nap,"awake":[${awake.joinToString(",") { "[${it.first},${it.second}]" }}]}"""
            val rows =
                result.nights.map { n ->
                    UserLabelEntity(timestamp = now, kind = UserLabelEntity.KIND_SLEEP_HEALTH, value = "${n.start}|${n.end}", refKey = "hc|${n.start}", contextJson = json(false, n.awake.orEmpty()))
                } +
                    result.naps.map { (a, b) ->
                        UserLabelEntity(timestamp = now, kind = UserLabelEntity.KIND_SLEEP_HEALTH, value = "$a|$b", refKey = "hc|$a", contextJson = json(true, emptyList()))
                    }
            labelDao.deleteByKind(UserLabelEntity.KIND_SLEEP_HEALTH)
            if (rows.isNotEmpty()) labelDao.insertAll(rows)
        }

        /** Removes the copied Health Connect sleep (when you turn it off). */
        suspend fun clearHealthSleep() = labelDao.deleteByKind(UserLabelEntity.KIND_SLEEP_HEALTH)

        /** Goes back to the estimate for that night. */
        suspend fun clearSleepFix(wakeDate: java.time.LocalDate) =
            labelDao.deleteByRef(UserLabelEntity.KIND_SLEEP_FIX, com.habitminer.engine.LabelMappers.sleepFixKey(wakeDate))

        /** "Were you asleep?" — one answer per nap; a new answer replaces the old one. */
        suspend fun saveNapAnswer(
            napKey: String,
            asleep: Boolean,
            contextJson: String?,
        ) {
            labelDao.deleteByRef(UserLabelEntity.KIND_NAP, napKey)
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_NAP,
                    value = if (asleep) UserLabelEntity.NAP_ASLEEP else UserLabelEntity.NAP_AWAKE,
                    refKey = napKey,
                    contextJson = contextJson,
                ),
            )
        }

        /** What a stretch of unusual days was; [from] is the first day of the change. */
        suspend fun savePeriodLabel(
            periodKey: String,
            value: String,
            from: java.time.LocalDate,
            until: java.time.LocalDate,
        ) {
            labelDao.deleteByRef(UserLabelEntity.KIND_PERIOD, periodKey)
            labelDao.insert(
                UserLabelEntity(
                    timestamp = System.currentTimeMillis(),
                    kind = UserLabelEntity.KIND_PERIOD,
                    value = value,
                    refKey = periodKey,
                    contextJson = periodJson(from, until),
                ),
            )
        }

        /** Moves a labelled period's last day forward while the change continues. */
        suspend fun extendPeriod(
            periodKey: String,
            from: java.time.LocalDate,
            until: java.time.LocalDate,
        ) = labelDao.updateContext(UserLabelEntity.KIND_PERIOD, periodKey, periodJson(from, until))

        private fun periodJson(
            from: java.time.LocalDate,
            until: java.time.LocalDate,
        ): String = "{\"from\":\"$from\",\"until\":\"$until\"}"

        // ---- Places -----------------------------------------------------------------------

        fun getPlaces(): Flow<List<PlaceEntity>> = placeDao.getAll()

        /** When each place was last written, so a place is touched at most hourly. */
        private val placeTouchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        suspend fun recordPlaceSeen(
            placeHash: String,
            time: Long,
        ) {
            // Every write to the places table re-ran the screens' place queries; "last seen"
            // only needs hour precision.
            val last = placeTouchedAt[placeHash]
            if (last != null && time - last < 60 * 60 * 1000L) return
            placeTouchedAt[placeHash] = time
            placeDao.insertIgnore(PlaceEntity(placeHash = placeHash, firstSeen = time, lastSeen = time))
            placeDao.touch(placeHash, time)
        }

        suspend fun renamePlace(
            placeHash: String,
            label: String?,
        ) = placeDao.rename(placeHash, label?.trim()?.takeIf { it.isNotEmpty() })

        // ---- Prompt history (kept in preferences, last 3 days) ----------------------------

        @Synchronized
        fun sentPrompts(): List<SentPrompt> =
            prefs.getString(PrefsKeys.SENT_PROMPTS, "").orEmpty()
                .split(';')
                .mapNotNull { entry ->
                    val parts = entry.split(':')
                    if (parts.size != 2) return@mapNotNull null
                    val kind = runCatching { PromptKind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                    val time = parts[1].toLongOrNull() ?: return@mapNotNull null
                    SentPrompt(kind, time)
                }

        @Synchronized
        fun recordPromptSent(
            kind: PromptKind,
            time: Long,
        ) {
            val cutoff = time - 3L * 24 * 60 * 60 * 1000
            val kept = sentPrompts().filter { it.time >= cutoff } + SentPrompt(kind, time)
            prefs.edit().putString(PrefsKeys.SENT_PROMPTS, kept.joinToString(";") { "${it.kind}:${it.time}" }).apply()
            if (kind == PromptKind.DIGEST) prefs.edit().putLong(PrefsKeys.LAST_DIGEST_AT, time).apply()
        }

        fun lastDigestAt(): Long? = prefs.getLong(PrefsKeys.LAST_DIGEST_AT, -1L).takeIf { it > 0 }

        // ---- Housekeeping ------------------------------------------------------------------

        suspend fun clearOldData(cutoffMs: Long) = labelDao.deleteOlderThan(cutoffMs)

        suspend fun clearAll() {
            labelDao.deleteAll()
            placeDao.deleteAll()
            prefs.edit().remove(PrefsKeys.SENT_PROMPTS).remove(PrefsKeys.LAST_DIGEST_AT).apply()
        }
    }
