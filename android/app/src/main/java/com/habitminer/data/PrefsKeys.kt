package com.habitminer.data

object PrefsKeys {
    const val PREFS_NAME = "habitminer_model"
    const val COLLECTION_ENABLED = "collection_enabled"
    const val RETENTION_DAYS = "retention_days"
    const val HAS_ALL_PERMISSIONS = "has_all_permissions"
    const val DAYS_OF_DATA = "days_of_data"

    // v1.2 feature switches (check-ins, nudges and digest default on; places default off)
    const val CHECKINS_ENABLED = "checkins_enabled"
    const val NUDGES_ENABLED = "nudges_enabled"
    const val DIGEST_ENABLED = "digest_enabled"
    const val DEVIATION_ALERTS_ENABLED = "deviation_alerts_enabled"
    const val PLACES_ENABLED = "places_enabled"

    // Bookkeeping for prompts and places
    const val SENT_PROMPTS = "sent_prompts"
    const val LAST_DIGEST_AT = "last_digest_at"
    const val PLACE_SALT = "place_salt"
    const val SENSING_MODE = "sensing_mode"

    // Extended
    /** Monitoring paused from the notification or Settings; survives the service restarting. */
    const val MONITORING_PAUSED = "monitoring_paused"
    const val LAST_PRUNE_DAY = "last_prune_day"

    /** Salt for hashed device IDs (headphones), separate from the places salt. */
    const val ID_SALT = "id_salt"

    /** The headphones and speakers connected when last checked, as stored details. */
    const val AUDIO_DEVICES = "audio_devices"

    /** Prefix for the last stored detail of a state event type (DND, next alarm, time zone). */
    const val LAST_EVENT_PREFIX = "last_event_"
}
