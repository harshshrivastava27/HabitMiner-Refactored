@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.habitminer.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.Confidence
import com.habitminer.analytics.DailySleep
import com.habitminer.analytics.Format
import com.habitminer.analytics.NapCandidate
import com.habitminer.analytics.RecordedNap
import com.habitminer.analytics.SleepSource
import com.habitminer.analytics.TimeUtil
import com.habitminer.ui.design.HueButton
import com.habitminer.ui.design.HueTonalButton
import com.habitminer.ui.design.TimeDialog
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.LocalIsDark
import com.habitminer.ui.theme.NumberStyles
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * One day's sleep: the night that ended that morning, each nap with its times, the total, and
 * the ways to fix the night or add, change and remove naps.
 */
@Composable
fun DaySleepSheet(
    day: DailySleep,
    /** That day's naps on record, with the answers behind them. */
    naps: List<RecordedNap>,
    /** Detected naps that day you haven't answered yet. */
    questions: List<NapCandidate>,
    today: LocalDate,
    zone: ZoneId,
    onFixNight: () -> Unit,
    onAddNap: () -> Unit,
    onEditNap: (RecordedNap) -> Unit,
    onRemoveNap: (RecordedNap) -> Unit,
    onAnswer: (NapCandidate, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        DaySleepContent(day, naps, questions, today, zone, onFixNight, onAddNap, onEditNap, onRemoveNap, onAnswer)
    }
}

/** What [DaySleepSheet] shows. */
@Composable
internal fun DaySleepContent(
    day: DailySleep,
    naps: List<RecordedNap>,
    questions: List<NapCandidate>,
    today: LocalDate,
    zone: ZoneId,
    onFixNight: () -> Unit,
    onAddNap: () -> Unit,
    onEditNap: (RecordedNap) -> Unit,
    onRemoveNap: (RecordedNap) -> Unit,
    onAnswer: (NapCandidate, Boolean) -> Unit,
) {
    val colors = LocalDataColors.current
    val hue = colors.sleepHue
    val napText = if (LocalIsDark.current) colors.nap else hue.text
    val napMs = naps.sumOf { it.durationMs }
    val night = day.night
    run {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
        ) {
            Text(dayTitle(day.date, today), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            val total = day.nightMs + napMs
            Text(
                if (total > 0) "${Format.duration(total)} asleep in all" else "No sleep recorded",
                style = NumberStyles.small.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                color = hue.text,
            )
            if (night != null && naps.isNotEmpty()) {
                Text(
                    "Night ${Format.duration(day.nightMs)}, ${napCount(naps.size)} ${Format.duration(napMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The night that ended this morning.
            SheetSection("Night")
            if (night == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("None found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onFixNight) { Text("Add it", color = hue.text) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            (if (night.source == SleepSource.YOU) "" else "About ") +
                                "${Format.clock(night.sleepStart, zone)} to ${Format.clock(night.wakeTime, zone)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val detail =
                            listOfNotNull(
                                when (night.source) {
                                    SleepSource.YOU -> "as you set it"
                                    SleepSource.HEALTH_CONNECT -> "from Health Connect"
                                    else -> if (night.confidence == Confidence.LOW) "a rough guess" else null
                                },
                                night.briefWakes.takeIf { it.isNotEmpty() }?.let { w ->
                                    "woke briefly at " + w.joinToString(", ") { Format.clock(it.start, zone) + if (it.alarm) " (alarm)" else "" }
                                },
                            ).joinToString("; ")
                        if (detail.isNotEmpty()) {
                            Text(detail.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(Format.duration(night.durationMs), style = NumberStyles.small, color = MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = onFixNight, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Text(if (night.source == SleepSource.YOU) "Change the times" else "Fix the times", color = hue.text)
                }
            }

            // Naps, each with its own times.
            SheetSection(if (naps.isEmpty()) "Naps" else "${napCount(naps.size).replaceFirstChar { it.uppercase() }}, ${Format.duration(napMs)}")
            if (naps.isEmpty() && questions.isEmpty()) {
                Text("None recorded", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            naps.forEach { nap ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${Format.clock(nap.start, zone)} to ${Format.clock(nap.end, zone)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        if (nap.fromHealthConnect) Text("From Health Connect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Format.duration(nap.durationMs), style = NumberStyles.small, color = napText)
                    if (!nap.fromHealthConnect) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { onEditNap(nap) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Change the times of the nap at ${Format.clock(nap.start, zone)}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRemoveNap(nap) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove the nap at ${Format.clock(nap.start, zone)}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            questions.forEach { q ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Phone untouched ${Format.clock(q.start, zone)} to ${Format.clock(q.end, zone)}, ${Format.duration(q.durationMs)}. Were you asleep?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HueButton("Yes", hue, { onAnswer(q, true) })
                    HueTonalButton("No", hue, { onAnswer(q, false) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddNap, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a nap")
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

private fun napCount(n: Int): String = if (n == 1) "1 nap" else "$n naps"

/** The nap being added or changed: its day and times, and the answers it replaces. */
data class NapToEdit(
    val date: LocalDate,
    val start: Long,
    val end: Long,
    /** The answers this replaces: a nap's [RecordedNap.keys], or the detected nap being corrected; empty when adding one. */
    val replacing: List<String> = emptyList(),
    /** Correcting a detected nap's times ("Yes, but at other times"). */
    val fromQuestion: Boolean = false,
) {
    val isNew: Boolean get() = replacing.isEmpty()
}

/** Set a nap's times. Several naps a day are fine; overlapping ones are counted once. */
@Composable
fun NapSheet(
    nap: NapToEdit,
    now: Long,
    zone: ZoneId,
    onSave: (start: Long, end: Long) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hue = LocalDataColors.current.sleepHue
    var startMinute by remember(nap) { mutableStateOf(TimeUtil.minuteOfDay(nap.start, zone)) }
    var endMinute by remember(nap) { mutableStateOf(TimeUtil.minuteOfDay(nap.end, zone)) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = fell asleep, false = woke up

    val start = TimeUtil.at(nap.date, startMinute / 60, startMinute % 60, zone)
    // Waking up "before" falling asleep means after midnight.
    val end = TimeUtil.at(if (endMinute > startMinute) nap.date else nap.date.plusDays(1), endMinute / 60, endMinute % 60, zone)
    val problem =
        when {
            end - start < 10 * TimeUtil.MINUTE -> "A nap has to be at least 10 minutes."
            end - start > 8 * TimeUtil.HOUR -> "That's over 8 hours: fix the night instead."
            end > now + TimeUtil.MINUTE -> "That's later than now."
            else -> null
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(if (nap.isNew) "Add a nap" else "Nap times", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(dayTitle(nap.date, TimeUtil.dateOf(now, zone)), style = MaterialTheme.typography.titleSmall, color = LocalDataColors.current.sleepHue.text)
            Spacer(Modifier.height(4.dp))
            Text(
                if (nap.isNew) "Set when you fell asleep and woke up. Add as many naps as you had." else "Set when you actually fell asleep and woke up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            NapTimeRow("Fell asleep", Format.clock(start, zone)) { picking = true }
            Spacer(Modifier.height(10.dp))
            NapTimeRow("Woke up", Format.clock(end, zone)) { picking = false }
            Spacer(Modifier.height(16.dp))
            Text(
                problem ?: "${Format.duration(end - start)} asleep",
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(20.dp))
            HueButton(if (nap.isNew) "Add nap" else "Save times", hue, { onSave(start, end) }, modifier = Modifier.fillMaxWidth(), enabled = problem == null)
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("That wasn't a nap") }
            }
        }
    }

    picking?.let { asleep ->
        TimeDialog(
            title = if (asleep) "Fell asleep" else "Woke up",
            initialMinute = if (asleep) startMinute else endMinute,
            onConfirm = { m ->
                if (asleep) startMinute = m else endMinute = m
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun NapTimeRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        androidx.compose.material3.FilledTonalButton(onClick = onClick) { Text(value, style = NumberStyles.small) }
    }
}

/** "Today", "Yesterday" or "Wednesday 21 October". */
internal fun dayTitle(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
    }

/** A sensible starting point for a nap you're adding on [date]: an hour ending now, or after lunch. */
internal fun napGuess(
    date: LocalDate,
    now: Long,
    zone: ZoneId,
): NapToEdit {
    val today = TimeUtil.dateOf(now, zone)
    val end =
        if (date == today && TimeUtil.minuteOfDay(now, zone) >= 60) {
            now - now % (5 * TimeUtil.MINUTE)
        } else {
            TimeUtil.at(date, 15, 0, zone)
        }
    return NapToEdit(date, end - TimeUtil.HOUR, end)
}
