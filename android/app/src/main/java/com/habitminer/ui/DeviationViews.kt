@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.DayDeviation
import com.habitminer.analytics.DeviationFinder
import com.habitminer.analytics.DeviationKind
import com.habitminer.analytics.Format
import com.habitminer.analytics.GuessRecord
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.PeriodOption
import com.habitminer.analytics.RoutineShift
import com.habitminer.data.UserLabelEntity
import com.habitminer.ui.components.BodyText
import com.habitminer.ui.components.CardHeader
import com.habitminer.ui.components.Hint
import com.habitminer.ui.components.Pill
import com.habitminer.ui.components.SurfaceCard
import com.habitminer.ui.theme.StatusError
import com.habitminer.ui.theme.StatusSuccess
import com.habitminer.ui.theme.StatusWarning
import java.time.LocalDate
import java.time.ZoneId

private val sleepTint = Color(0xFF7986CB)

/** "Today", "Yesterday" or "Sun 4 Oct". */
fun dayLabel(
    date: LocalDate,
    today: LocalDate = LocalDate.now(),
): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DeviationFinder.shortDate(date)
    }

private val DeviationKind.isMore: Boolean
    get() =
        this in
            setOf(
                DeviationKind.MORE_USE, DeviationKind.LATE_NIGHT, DeviationKind.APP_SPIKE, DeviationKind.APP_NEW,
                DeviationKind.MORE_UNLOCKS, DeviationKind.LONG_SLEEP, DeviationKind.LATE_BEDTIME, DeviationKind.LATE_WAKE,
            )

/** One difference from your usual days, with Expected / Unusual buttons. */
@Composable
fun DeviationCard(
    dev: DayDeviation,
    feedback: String?,
    showDay: Boolean = true,
    onFeedback: (String) -> Unit,
) {
    SurfaceCard {
        CardHeader(
            dev.title,
            if (dev.kind.isMore) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
            tint = StatusWarning,
            trailing = if (showDay) dayLabel(dev.date) else null,
        )
        Spacer(modifier = Modifier.height(8.dp))
        BodyText(dev.detail)
        dev.explainedBy?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Pill("During: $it", color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(modifier = Modifier.height(10.dp))
        when (feedback) {
            UserLabelEntity.FEEDBACK_EXPECTED -> Hint("You marked this as expected. Thanks, this helps tune what counts as unusual.")
            UserLabelEntity.FEEDBACK_UNUSUAL -> Hint("You marked this as unusual. Thanks for confirming.")
            else -> {
                Hint("Was this expected?")
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onFeedback(UserLabelEntity.FEEDBACK_EXPECTED) }) { Text("Expected") }
                    OutlinedButton(onClick = { onFeedback(UserLabelEntity.FEEDBACK_UNUSUAL) }) { Text("Unusual") }
                }
            }
        }
    }
}

/** Several days in a row clearly above or below usual, and the "what's going on?" question. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineShiftCard(
    shift: RoutineShift,
    answer: String?,
    onLabel: (String) -> Unit,
) {
    val option = answer?.let { PeriodOption.fromKey(it) }
    val name = shift.label ?: option?.takeIf { it.setsAside }?.label
    SurfaceCard {
        CardHeader(
            (name ?: "Routine change") + " since ${DeviationFinder.shortDate(shift.since)}",
            Icons.Default.Timeline,
            tint = MaterialTheme.colorScheme.secondary,
            trailing = "${shift.days} day${if (shift.days == 1) "" else "s"}",
        )
        Spacer(modifier = Modifier.height(8.dp))
        val pct = kotlin.math.abs(shift.change * 100).toInt()
        BodyText(
            "About $pct% ${if (shift.less) "less" else "more"} phone time a day: ${Format.duration(shift.avgPerDayMs)} " +
                "instead of about ${Format.duration(shift.usualPerDayMs)}.",
        )
        if (shift.appChanges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            shift.appChanges.forEach { c ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BodyText(c.app)
                    Hint("${Format.duration(c.usualPerDayMs)} → ${Format.duration(c.nowPerDayMs)} a day")
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        when {
            option == null && shift.label == null -> {
                Hint("What's going on? Days you label are kept out of your usual pattern, so they don't change what counts as normal.")
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PeriodOption.entries.forEach { o ->
                        OutlinedButton(onClick = { onLabel(o.key) }) { Text("${o.emoji} ${o.label}") }
                    }
                }
            }
            name != null -> Hint("You said: $name. These days are kept out of your usual pattern and won't trigger evening alerts.")
            else -> Hint("You said nothing special is going on, so these days still count as usual.")
        }
    }
}

/** "Were you asleep from 14:08 to 17:01?" */
@Composable
fun NapQuestionCard(
    nap: NapCandidate,
    onAnswer: (Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    SurfaceCard {
        CardHeader("Were you asleep?", Icons.Default.Bedtime, tint = sleepTint, trailing = Format.duration(nap.durationMs))
        Spacer(modifier = Modifier.height(8.dp))
        BodyText("From ${Format.clock(nap.start, zone)} to ${Format.clock(nap.end, zone)} your phone was untouched.")
        if (nap.evidence.isNotEmpty()) Hint("Also: ${nap.evidence.joinToString(", ")}.")
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAnswer(true) }) { Text("😴 Yes, I napped") }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("No") }
        }
    }
}

/** What the model guessed before your latest app switches, next to what you opened. */
@Composable
fun RecentGuessesList(
    guesses: List<GuessRecord>,
    max: Int = 8,
) {
    val zone = ZoneId.systemDefault()
    guesses.take(max).forEach { g ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${Format.clock(g.time, zone)} · after ${g.afterApp}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    "Guessed ${g.guesses.joinToString(", ").ifEmpty { "nothing" }} → opened ${g.actual}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (g.hit) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.padding(start = 8.dp))
            when {
                g.hit -> Pill("✓ 1st", color = StatusSuccess)
                g.inTop3 -> Pill("✓ top 3", color = StatusWarning)
                else -> Pill("✗", color = StatusError)
            }
        }
    }
}
