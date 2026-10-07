package com.habitminer.proactive

import android.content.Context
import com.habitminer.analytics.CheckInOption
import com.habitminer.data.PrefsKeys

/** Matches a typed check-in ("lab report", "bus home") to the nearest activity. */
object CheckInMatcher {
    private val words =
        mapOf(
            CheckInOption.STUDYING to listOf("study", "studying", "work", "working", "class", "lecture", "lab", "homework", "assignment", "exam", "revis", "reading", "report", "coding", "meeting"),
            CheckInOption.RELAXING to listOf("relax", "chill", "game", "gaming", "youtube", "netflix", "movie", "series", "show", "music", "scroll", "reels", "insta", "tv"),
            CheckInOption.SOCIALISING to listOf("friend", "family", "party", "date", "hanging", "talking", "call", "chatting", "people", "mom", "dad"),
            CheckInOption.COMMUTING to listOf("bus", "train", "metro", "walk", "walking", "drive", "driving", "commut", "travel", "cab", "uber", "auto", "home"),
            CheckInOption.EATING to listOf("eat", "eating", "lunch", "dinner", "breakfast", "snack", "food", "cooking", "cook"),
            CheckInOption.RESTING to listOf("bed", "rest", "resting", "nap", "sleep", "lying", "tired"),
        )

    fun match(text: String): CheckInOption? {
        val t = text.lowercase()
        return words.entries.maxByOrNull { (_, list) -> list.count { t.contains(it) } }?.takeIf { (_, list) -> list.any { t.contains(it) } }?.key
    }

    /** Adds the typed words to the context JSON as "note". */
    fun withNote(
        json: String,
        note: String,
    ): String {
        val escaped = note.take(200).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val body = json.trim().removePrefix("{").removeSuffix("}").trim()
        return if (body.isEmpty()) "{\"note\":\"$escaped\"}" else "{$body,\"note\":\"$escaped\"}"
    }
}

/** Snoozes for app-time reminders, by app (or "daily" for the daily target). */
object GoalSnooze {
    private fun key(id: String) = PrefsKeys.GOAL_SNOOZE_PREFIX + id

    fun snooze(
        context: Context,
        id: String,
        minutes: Int,
    ) {
        context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(key(id), System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    fun snoozedUntil(
        context: Context,
        id: String,
    ): Long = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE).getLong(key(id), 0L)
}
