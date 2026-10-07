package com.habitminer.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.habitminer.repository.FeedbackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Saves answers given straight from a notification: check-ins (buttons or a typed reply), naps,
 * routine changes, deviations, insight feedback and reminder snoozes.
 */
@AndroidEntryPoint
class CheckInReceiver : BroadcastReceiver() {
    @Inject
    lateinit var feedbackRepository: FeedbackRepository

    @Inject
    lateinit var labelContextCapture: LabelContextCapture

    @Inject
    lateinit var insightRepository: com.habitminer.repository.InsightRepository

    @Inject
    lateinit var analysisRepository: com.habitminer.engine.AnalysisRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val value = intent.getStringExtra(EXTRA_VALUE) ?: return
        val key = intent.getStringExtra(EXTRA_KEY)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_ANSWER -> {
                        val promptedAt = intent.getLongExtra(Notifier.EXTRA_PROMPTED_AT, -1L).takeIf { it > 0 }
                        val typed = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                        if (value == "reply") {
                            if (!typed.isNullOrEmpty()) {
                                // Typed answers are matched to an activity; the words are kept with it.
                                val option = CheckInMatcher.match(typed)
                                val json = labelContextCapture.captureJson()?.let { CheckInMatcher.withNote(it, typed) } ?: CheckInMatcher.withNote("{}", typed)
                                feedbackRepository.saveCheckIn(option?.key ?: "other", promptedAt, json)
                            }
                        } else {
                            feedbackRepository.saveCheckIn(value, promptedAt, labelContextCapture.captureJson())
                        }
                        Notifier.cancelCheckIn(context)
                    }
                    ACTION_INSIGHT -> {
                        if (key != null) insightRepository.feedback(key, value)
                        analysisRepository.invalidate()
                        Notifier.cancel(context, Notifier.ID_INSIGHT)
                    }
                    ACTION_GOAL_SNOOZE -> {
                        val minutes = value.toIntOrNull() ?: 0
                        if (key != null) GoalSnooze.snooze(context, key, if (minutes > 0) minutes else 60)
                        Notifier.cancel(context, Notifier.ID_GOAL)
                    }
                    ACTION_NAP -> {
                        if (key != null) feedbackRepository.saveNapAnswer(key, value == "asleep", labelContextCapture.captureJson())
                        Notifier.cancel(context, Notifier.ID_NAP)
                    }
                    ACTION_PERIOD -> {
                        val from = key?.removePrefix("period|")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        if (key != null && from != null) feedbackRepository.savePeriodLabel(key, value, from, LocalDate.now())
                        Notifier.cancel(context, Notifier.ID_PERIOD)
                    }
                    ACTION_DEVIATION -> {
                        val json = labelContextCapture.captureJson()
                        intent.getStringArrayExtra(EXTRA_KEYS)?.forEach { feedbackRepository.saveDeviationFeedback(it, value, json) }
                        Notifier.cancel(context, Notifier.ID_DEVIATIONS)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.habitminer.action.CHECKIN_ANSWER"
        const val ACTION_NAP = "com.habitminer.action.NAP_ANSWER"
        const val ACTION_PERIOD = "com.habitminer.action.PERIOD_ANSWER"
        const val ACTION_DEVIATION = "com.habitminer.action.DEVIATION_ANSWER"
        const val ACTION_INSIGHT = "com.habitminer.action.INSIGHT_FEEDBACK"
        const val ACTION_GOAL_SNOOZE = "com.habitminer.action.GOAL_SNOOZE"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KEY = "key"
        const val EXTRA_KEYS = "keys"
    }
}
