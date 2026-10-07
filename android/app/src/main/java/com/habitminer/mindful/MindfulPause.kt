package com.habitminer.mindful

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.habitminer.data.PrefsKeys

/** Settings and state for the mindful pause and focus sessions. Everything is off by default. */
object MindfulPause {
    /** After "Open anyway", the same app isn't paused again for this long. */
    const val GRACE_MS = 10 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(PrefsKeys.MINDFUL_ENABLED, false)

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit().putBoolean(PrefsKeys.MINDFUL_ENABLED, on).apply()

    /** How long the pause lasts before "Open" is available, in seconds (5 or 8). */
    fun seconds(context: Context): Int = prefs(context).getInt(PrefsKeys.MINDFUL_SECONDS, 5)

    fun setSeconds(
        context: Context,
        s: Int,
    ) = prefs(context).edit().putInt(PrefsKeys.MINDFUL_SECONDS, s).apply()

    /** End of the current focus session, or null. */
    fun focusUntil(context: Context): Long? = prefs(context).getLong(PrefsKeys.FOCUS_UNTIL, -1L).takeIf { it > System.currentTimeMillis() }

    fun startFocus(
        context: Context,
        minutes: Int,
    ) = prefs(context).edit().putLong(PrefsKeys.FOCUS_UNTIL, System.currentTimeMillis() + minutes * 60_000L).apply()

    fun endFocus(context: Context) = prefs(context).edit().putLong(PrefsKeys.FOCUS_UNTIL, -1L).apply()

    /** Whether the accessibility service is switched on in system settings. */
    fun serviceOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, MindfulPauseService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
