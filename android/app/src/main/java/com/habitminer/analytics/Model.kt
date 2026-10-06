package com.habitminer.analytics

/*
 * Plain-Kotlin models used by the analytics layer. Nothing in this package may import
 * Android classes, so every algorithm here can be unit tested on a plain JVM.
 */

/** One foreground app session. */
data class UsageSession(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val start: Long,
    val end: Long,
    val durationMs: Long,
)

/**
 * One context snapshot. Sensor fields are null when the reading was not taken
 * (the collector only samples sensors while the screen is on).
 */
data class ContextSample(
    val timestamp: Long,
    val lightLux: Float?,
    val motionVariance: Float?,
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val proximityNear: Boolean?,
    val batteryLevel: Int?,
    val place: String? = null,
    /** Steps in the two minutes before the reading, or null when the step counter wasn't available. */
    val recentSteps: Int? = null,
    /** Steps since the previous reading (screen on or off), or null when unknown. */
    val stepsSinceLast: Int? = null,
)

/** A notification or unlock event. */
data class TimedEvent(
    val timestamp: Long,
    val packageName: String? = null,
)

/** Friendly app categories shown in the UI. [colorIndex] picks a stable chart colour. */
enum class AppCategory(val label: String, val colorIndex: Int) {
    MESSAGING("Messaging", 0),
    SOCIAL("Social", 1),
    VIDEO("Video", 2),
    GAMES("Games", 3),
    BROWSING("Browsing", 4),
    MUSIC("Music & audio", 5),
    PRODUCTIVITY("Work & study", 6),
    NAVIGATION("Maps & travel", 7),
    SHOPPING("Shopping & food", 8),
    HEALTH("Health & fitness", 9),
    TOOLS("Tools & system", 10),
    OTHER("Other", 11),
    ;

    /** Categories that are mostly passive consumption; used by nudges. */
    val isLeisure: Boolean get() = this == SOCIAL || this == VIDEO || this == GAMES
}
