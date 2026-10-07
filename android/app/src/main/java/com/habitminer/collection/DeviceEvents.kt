package com.habitminer.collection

/**
 * Event types stored in the device_events table.
 *
 * The types in [RECORDED_BY_APP] come from our own receivers and only exist while HabitMiner
 * is running. The others are copied from Android's usage-event log by [UsageIngestor], which
 * keeps them even for times the app wasn't running (API 28+ for the lock-screen events).
 *
 * Some types carry a detail (DB v10), written as `key=value;key=value`.
 */
object DeviceEvents {
    /** Unlock seen by our USER_PRESENT receiver. */
    const val UNLOCK = "UNLOCK"

    /** Notification posted (package only, never the text). */
    const val NOTIFICATION = "NOTIFICATION"

    /** Lock screen dismissed: the system's own record of an unlock. */
    const val KEYGUARD_HIDDEN = "KEYGUARD_HIDDEN"

    /** Lock screen shown: the phone was locked. */
    const val KEYGUARD_SHOWN = "KEYGUARD_SHOWN"

    /** Screen turned on (also when only glancing at the lock screen). */
    const val SCREEN_ON = "SCREEN_ON"

    /** Screen turned off. */
    const val SCREEN_OFF = "SCREEN_OFF"

    /** Phone shut down or started: marks gaps where nothing could be recorded. */
    const val SHUTDOWN = "SHUTDOWN"
    const val STARTUP = "STARTUP"

    // ---- Extended (DB v10) ----

    /**
     * A notification went away. detail: `reason=opened|dismissed|dismissed_all|app|snoozed|timeout|other;shownMs=…`
     * ("app" means the app removed it, usually because you opened it from the app itself).
     */
    const val NOTIFICATION_REMOVED = "NOTIFICATION_REMOVED"

    /** Do Not Disturb / bedtime mode changed. detail: `mode=off|priority|alarms|silent`. */
    const val DND = "DND"

    /** The next alarm the clock app has set changed. detail: `at=<epoch ms>` or `at=` when none. */
    const val NEXT_ALARM = "NEXT_ALARM"

    /** Charger plugged in or out. detail: `level=<0-100>;plug=ac|usb|wireless|`. */
    const val POWER_CONNECTED = "POWER_CONNECTED"
    const val POWER_DISCONNECTED = "POWER_DISCONNECTED"

    /**
     * Headphones or a speaker connected or disconnected. detail: `kind=bluetooth|wired|usb|hearing_aid;id=<salted hash>`.
     * The id tells devices apart without storing their names.
     */
    const val AUDIO_CONNECTED = "AUDIO_CONNECTED"
    const val AUDIO_DISCONNECTED = "AUDIO_DISCONNECTED"

    /** The phone's time zone changed (travel). detail: `zone=<IANA id>`. */
    const val TIMEZONE = "TIMEZONE"

    /** An app was installed or removed; packageName is that app. */
    const val APP_INSTALLED = "APP_INSTALLED"
    const val APP_REMOVED = "APP_REMOVED"

    /** Recorded by HabitMiner itself rather than copied from Android's usage log. */
    val RECORDED_BY_APP =
        setOf(
            UNLOCK, NOTIFICATION, NOTIFICATION_REMOVED, DND, NEXT_ALARM, POWER_CONNECTED, POWER_DISCONNECTED,
            AUDIO_CONNECTED, AUDIO_DISCONNECTED, TIMEZONE, APP_INSTALLED, APP_REMOVED,
        )

    /** Reads `key` from a `key=value;key=value` detail. */
    fun detailValue(
        detail: String?,
        key: String,
    ): String? =
        detail?.split(';')?.firstNotNullOfOrNull { part ->
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == key) part.substring(i + 1) else null
        }

    // Android's UsageEvents.Event constants for the types above. Written out because some are
    // newer than minSdk; values from AOSP's UsageEvents.java.
    const val TYPE_SCREEN_INTERACTIVE = 15
    const val TYPE_SCREEN_NON_INTERACTIVE = 16
    const val TYPE_KEYGUARD_SHOWN = 17
    const val TYPE_KEYGUARD_HIDDEN = 18
    const val TYPE_DEVICE_SHUTDOWN = 26
    const val TYPE_DEVICE_STARTUP = 27

    /** Maps a usage-event type to the stored event name, or null for types we don't keep. */
    fun nameFor(usageEventType: Int): String? =
        when (usageEventType) {
            TYPE_KEYGUARD_HIDDEN -> KEYGUARD_HIDDEN
            TYPE_KEYGUARD_SHOWN -> KEYGUARD_SHOWN
            TYPE_SCREEN_INTERACTIVE -> SCREEN_ON
            TYPE_SCREEN_NON_INTERACTIVE -> SCREEN_OFF
            TYPE_DEVICE_SHUTDOWN -> SHUTDOWN
            TYPE_DEVICE_STARTUP -> STARTUP
            else -> null
        }
}
