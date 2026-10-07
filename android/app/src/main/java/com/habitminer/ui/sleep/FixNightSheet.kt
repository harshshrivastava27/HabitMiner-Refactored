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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.habitminer.analytics.Format
import com.habitminer.analytics.SleepSource
import com.habitminer.analytics.TimeUtil
import com.habitminer.ui.design.TimeDialog
import com.habitminer.ui.theme.NumberStyles
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The night being fixed: its wake date and the times shown now (or a starting guess). */
data class NightToFix(
    val wakeDate: LocalDate,
    val start: Long,
    val end: Long,
    /** Null when the night wasn't detected and is being added. */
    val source: SleepSource?,
)

/**
 * Set the times you actually fell asleep and woke up. Saved nights use your times, and after a
 * few of them HabitMiner shifts the other estimates the same way.
 */
@Composable
fun FixNightSheet(
    night: NightToFix,
    today: LocalDate,
    zone: ZoneId,
    onSave: (start: Long, end: Long) -> Unit,
    onNotSleep: () -> Unit,
    onUseEstimate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var startMinute by remember(night) { mutableStateOf(TimeUtil.minuteOfDay(night.start, zone)) }
    var endMinute by remember(night) { mutableStateOf(TimeUtil.minuteOfDay(night.end, zone)) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = fell asleep, false = woke up

    // Bedtimes from 3 pm on belong to the evening before the wake date.
    val start = toTime(night.wakeDate, startMinute, evening = startMinute >= 15 * 60, zone)
    val end = toTime(night.wakeDate, endMinute, evening = false, zone)
    val valid = end - start in TimeUtil.HOUR..(16 * TimeUtil.HOUR)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(title(night.wakeDate, today), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                when (night.source) {
                    null -> "It wasn't detected. Set when you fell asleep and woke up."
                    SleepSource.YOU -> "You set these times."
                    else -> "Set when you actually fell asleep and woke up. After a few fixes, other nights adjust too."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TimeRow("Fell asleep", Format.clock(start, zone)) { picking = true }
            Spacer(Modifier.height(10.dp))
            TimeRow("Woke up", Format.clock(end, zone)) { picking = false }
            Spacer(Modifier.height(16.dp))
            Text(
                if (valid) "${Format.duration(end - start)} in bed asleep" else "Waking up has to be 1 to 16 hours after falling asleep.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(start, end) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Save times") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (night.source != null) TextButton(onClick = onNotSleep) { Text("That wasn't sleep") }
                if (night.source == SleepSource.YOU) TextButton(onClick = onUseEstimate) { Text("Use the estimate") }
            }
        }
    }

    picking?.let { asleep ->
        val initial = if (asleep) startMinute else endMinute
        TimeDialog(
            title = if (asleep) "Fell asleep" else "Woke up",
            initialMinute = initial,
            onConfirm = { m ->
                if (asleep) startMinute = m else endMinute = m
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun TimeRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onClick) { Text(value, style = NumberStyles.small) }
    }
}

private fun toTime(
    wakeDate: LocalDate,
    minute: Int,
    evening: Boolean,
    zone: ZoneId,
): Long = TimeUtil.at(if (evening) wakeDate.minusDays(1) else wakeDate, minute / 60, minute % 60, zone)

private fun title(
    wakeDate: LocalDate,
    today: LocalDate,
): String =
    when (wakeDate) {
        today -> "Last night"
        today.minusDays(1) -> "The night before last"
        else -> "Night before ${wakeDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${wakeDate.dayOfMonth}"
    }
