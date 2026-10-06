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

/** Saves answers given straight from a notification's buttons (check-ins, naps, routine changes, deviations). */
@AndroidEntryPoint
class CheckInReceiver : BroadcastReceiver() {
    @Inject
    lateinit var feedbackRepository: FeedbackRepository

    @Inject
    lateinit var labelContextCapture: LabelContextCapture

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
                        feedbackRepository.saveCheckIn(value, promptedAt, labelContextCapture.captureJson())
                        Notifier.cancelCheckIn(context)
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
        const val EXTRA_VALUE = "value"
        const val EXTRA_KEY = "key"
        const val EXTRA_KEYS = "keys"
    }
}
