package com.habitminer.analytics

/** Answers offered by the "what are you doing?" check-in. */
enum class CheckInOption(val key: String, val label: String, val emoji: String) {
    STUDYING("studying", "Studying or working", "📚"),
    RELAXING("relaxing", "Relaxing or entertainment", "🎮"),
    SOCIALISING("socialising", "With people", "🗣️"),
    COMMUTING("commuting", "Commuting or walking", "🚶"),
    EATING("eating", "Eating", "🍽️"),
    RESTING("resting", "In bed or resting", "🛏️"),
    ;

    companion object {
        fun fromKey(key: String): CheckInOption? = entries.firstOrNull { it.key == key }
    }
}

/** Answers to "your routine has changed — what's going on?". */
enum class PeriodOption(val key: String, val label: String, val emoji: String) {
    EXAMS("exams", "Exams", "📝"),
    TRAVEL("travel", "Travelling", "🧳"),
    UNWELL("unwell", "Unwell", "🤒"),
    HOLIDAY("holiday", "Holiday or break", "🏖️"),
    BUSY("busy", "Busy with work or projects", "💼"),
    OTHER("other", "Something else", "✨"),
    NOTHING("none", "Nothing special", "🤷"),
    ;

    /** "Nothing special" is recorded (so we don't ask again) but doesn't set the days aside. */
    val setsAside: Boolean get() = this != NOTHING

    companion object {
        fun fromKey(key: String): PeriodOption? = entries.firstOrNull { it.key == key }
    }
}
