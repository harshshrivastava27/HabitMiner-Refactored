@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.habitminer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.habitminer.ui.design.HeroContainer
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.rememberConfirmHaptic
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.NumberStyles
import java.time.LocalDate
import java.time.ZoneId

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

/** A rounded panel on the low tonal surface, for questions and list blocks that aren't the hero. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

/** One difference from your usual days, with Expected / Unusual answers. */
@Composable
fun DeviationCard(
    dev: DayDeviation,
    feedback: String?,
    showDay: Boolean = true,
    onFeedback: (String) -> Unit,
) {
    val colors = LocalDataColors.current
    val haptic = rememberConfirmHaptic()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (dev.kind.isMore) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                contentDescription = if (dev.kind.isMore) "More than usual" else "Less than usual",
                tint = colors.caution,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(dev.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (showDay) Text(dayLabel(dev.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(dev.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        dev.explainedBy?.let {
            Spacer(Modifier.height(4.dp))
            Text("During $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(10.dp))
        when (feedback) {
            UserLabelEntity.FEEDBACK_EXPECTED -> Text("You marked this as expected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UserLabelEntity.FEEDBACK_UNUSUAL -> Text("You marked this as unusual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_EXPECTED)
                    }) { Text("Expected") }
                    OutlinedButton(onClick = {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_UNUSUAL)
                    }) { Text("Unusual") }
                }
        }
    }
}

/** Several days in a row clearly above or below usual, and the "what's going on?" question. */
@Composable
fun RoutineShiftCard(
    shift: RoutineShift,
    answer: String?,
    compact: Boolean = false,
    onLabel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(!compact) }
    val haptic = rememberConfirmHaptic()
    val option = answer?.let { PeriodOption.fromKey(it) }
    val name = shift.label ?: option?.takeIf { it.setsAside }?.label
    Panel {
        Text(
            (name ?: "Your routine changed") + " since ${DeviationFinder.shortDate(shift.since)}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        val pct = kotlin.math.abs(shift.change * 100).toInt()
        Text(
            "About $pct% ${if (shift.less) "less" else "more"} phone time a day for ${shift.days} day${if (shift.days == 1) "" else "s"}: " +
                "${Format.duration(shift.avgPerDayMs)} instead of about ${Format.duration(shift.usualPerDayMs)}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (shift.appChanges.isNotEmpty() && expanded) {
            Spacer(Modifier.height(8.dp))
            shift.appChanges.forEach { c ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(c.app, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        "${Format.duration(c.usualPerDayMs)} → ${Format.duration(c.nowPerDayMs)}",
                        style = NumberStyles.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            option == null && shift.label == null -> {
                Text("What's going on?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                val options = if (expanded) PeriodOption.entries else listOf(PeriodOption.EXAMS, PeriodOption.TRAVEL, PeriodOption.NOTHING)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { o ->
                        OutlinedButton(onClick = {
                            haptic()
                            onLabel(o.key)
                        }) { Text(o.label) }
                    }
                    if (!expanded) TextButton(onClick = { expanded = true }) { Text("More") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Days you label are kept out of your usual pattern, so they don't change what counts as normal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            name != null ->
                Text(
                    "You said: $name. These days are kept out of your usual pattern and won't trigger evening alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                Text(
                    "You said nothing special is going on, so these days still count as usual.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    val haptic = rememberConfirmHaptic()
    HeroContainer {
        Text("Were you asleep?", style = MaterialTheme.typography.labelLarge, color = LocalDataColors.current.sleep)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your phone was untouched from ${Format.clock(nap.start, zone)} to ${Format.clock(nap.end, zone)}, ${Format.duration(nap.durationMs)}.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (nap.evidence.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                nap.evidence.joinToString(", ").replaceFirstChar { it.uppercase() } + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                haptic()
                onAnswer(true)
            }) { Text("Yes, I napped") }
            OutlinedButton(onClick = {
                haptic()
                onAnswer(false)
            }) { Text("No") }
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
    val colors = LocalDataColors.current
    Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        guesses.take(max).forEach { g ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (g.inTop3) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = if (g.hit) "First guess right" else if (g.inTop3) "In the top 3" else "Missed",
                    tint = if (g.hit) colors.good else if (g.inTop3) colors.caution else colors.alert,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Opened ${g.actual} after ${g.afterApp}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${Format.clock(g.time, zone)}, guessed ${g.guesses.joinToString(", ").ifEmpty { "nothing" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
