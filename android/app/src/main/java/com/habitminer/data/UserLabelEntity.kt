package com.habitminer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something the user told us: a check-in answer ("what are you doing?") or feedback on a
 * deviation (expected / unusual). These are ground-truth labels for evaluating the
 * models and are included in the data export.
 */
@Entity(tableName = "user_labels", indices = [Index(value = ["kind", "timestamp"]), Index(value = ["refKey"])])
data class UserLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** [KIND_CHECK_IN], [KIND_DEVIATION_FEEDBACK], [KIND_NAP] or [KIND_PERIOD]. */
    val kind: String,
    val value: String,
    /** Deviation fingerprint, nap key ("nap|start|end") or period key ("period|2026-10-04"). */
    val refKey: String? = null,
    /** When the prompt was shown, for check-ins answered from a notification. */
    val promptedAt: Long? = null,
    /** Context at answer time: current app, light, motion, charging, place. */
    val contextJson: String? = null,
) {
    companion object {
        const val KIND_CHECK_IN = "CHECK_IN"
        const val KIND_DEVIATION_FEEDBACK = "DEVIATION_FEEDBACK"
        const val FEEDBACK_EXPECTED = "EXPECTED"
        const val FEEDBACK_UNUSUAL = "UNUSUAL"

        /** Answer to "were you asleep?": [NAP_ASLEEP] or [NAP_AWAKE]. */
        const val KIND_NAP = "NAP"
        const val NAP_ASLEEP = "asleep"
        const val NAP_AWAKE = "awake"

        /**
         * What a stretch of unusual days was (exams, travel…). contextJson holds
         * {"from": "2026-10-04", "until": "2026-10-09"}; until grows while the change lasts.
         */
        const val KIND_PERIOD = "PERIOD"

        /**
         * Your own times for a night (Extended). refKey "sleep|<wake date>", value
         * "<start ms>|<end ms>" or [SLEEP_NONE] for "that wasn't sleep". contextJson holds what
         * was shown before, so the export can compare estimate and correction.
         */
        const val KIND_SLEEP_FIX = "SLEEP_FIX"
        const val SLEEP_NONE = "none"

        /**
         * A sleep session copied from Health Connect (opt-in, Extended). value "<start>|<end>",
         * refKey "hc|<start>", contextJson {"nap":true|false,"awake":[[a,b],…]}. Replaced on
         * every sync; your own fixes win over them.
         */
        const val KIND_SLEEP_HEALTH = "SLEEP_HEALTH_CONNECT"
    }
}
