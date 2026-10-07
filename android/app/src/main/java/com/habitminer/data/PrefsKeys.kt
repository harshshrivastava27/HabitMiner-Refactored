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

    /** Sleep sessions from Health Connect (opt-in). */
    const val HEALTH_CONNECT_ENABLED = "health_connect_enabled"
    const val HEALTH_CONNECT_SYNCED_AT = "health_connect_synced_at"

    /** Busy times from the calendar (opt-in). */
    const val CALENDAR_ENABLED = "calendar_enabled"

    /** "Allow notifications?" on Today was dismissed. */
    const val NOTIFICATION_ASK_DISMISSED = "notification_ask_dismissed"

    // Insights and quiet hours
    const val INSIGHT_FREQUENCY = "insight_frequency"
    const val INSIGHT_FAMILIES_OFF = "insight_families_off"
    const val INSIGHT_WINDOW_START = "insight_window_start"
    const val INSIGHT_WINDOW_END = "insight_window_end"
    const val INSIGHT_EXPLAINER_SEEN = "insight_explainer_seen"
    const val QUIET_START = "quiet_start_minute"
    const val QUIET_END = "quiet_end_minute"

    /** Prefix for "reminders sent for the current stretch in this app" ("<day>@<stretch start>|<count>"). */
    const val GOAL_REMINDED_PREFIX = "goal_reminded_"

    /** The day the daily-target reminder last went out. */
    const val GOAL_TARGET_REMINDED = "goal_target_reminded"

    /** Prefix for "reminders for this app snoozed until" (epoch ms). */
    const val GOAL_SNOOZE_PREFIX = "goal_snooze_"

    /** Prefix for the last stored detail of a state event type (DND, next alarm, time zone). */
    const val LAST_EVENT_PREFIX = "last_event_"
}
