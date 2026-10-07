package com.habitminer.collection

/**
 * Event types stored in the device_events table.
 *
 * [UNLOCK] and [NOTIFICATION] come from our own receivers and only exist while HabitMiner is
 * running. The others are copied from Android's usage-event log by [UsageIngestor], which
 * keeps them even for times the app wasn't running (API 28+ for the lock-screen events).
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
