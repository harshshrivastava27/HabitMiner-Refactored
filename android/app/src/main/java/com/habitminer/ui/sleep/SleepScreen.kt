@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.Confidence
import com.habitminer.analytics.Format
import com.habitminer.analytics.SleepCorrections
import com.habitminer.analytics.SleepEstimate
import com.habitminer.analytics.SleepSource
import com.habitminer.analytics.TimeUtil
import com.habitminer.engine.HabitActions
import com.habitminer.engine.HabitUiState
import com.habitminer.ui.NapQuestionCard
import com.habitminer.ui.design.EmptyState
import com.habitminer.ui.design.HeroDuration
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.ScreenTitle
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.Skeleton
import com.habitminer.ui.design.SleepLane
import com.habitminer.ui.design.SleepWeekColumns
import com.habitminer.ui.design.hourAxisLabels
import com.habitminer.ui.theme.LocalDataColors
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private const val HOW_IT_WORKS =
    "HabitMiner estimates sleep from when your phone goes untouched at night, the step counter, " +
        "darkness and charging. A quick check or an alarm doesn't end the night unless the step counter sees you get up.\n\n" +
        "Each day counts the night that ended that morning plus naps you confirmed. Times are estimates, so they're shown " +
        "as \"about\"; regularity matters more than any single night.\n\n" +
        "If a night is wrong, tap it to fix the times. After three fixes HabitMiner learns how long you usually take to fall " +
        "asleep and adjusts the other nights."

@Composable
fun SleepScreen(
    state: HabitUiState,
    actions: HabitActions,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val insights = state.insights
    var fixing by remember { mutableStateOf<NightToFix?>(null) }
    val today = insights?.let { TimeUtil.dateOf(it.computedAt, zone) } ?: java.time.LocalDate.now(zone)
    fixing?.let { night ->
        FixNightSheet(
            night = night,
            today = today,
            zone = zone,
            onSave = { start, end ->
                actions.fixNight(night.wakeDate, start, end)
                fixing = null
            },
            onNotSleep = {
                actions.markNotSleep(night.wakeDate)
                fixing = null
            },
            onUseEstimate = {
                actions.clearNightFix(night.wakeDate)
                fixing = null
            },
            onDismiss = { fixing = null },
        )
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "title") { ScreenTitle("Sleep") }
        if (insights == null) {
            item(key = "loading") { Skeleton() }
            return@LazyColumn
        }
        val nights = insights.sleepNights
        // Only last night counts as "last night"; an older one is shown in the week instead.
        val last = insights.lastNight?.takeIf { it.wakeDate == today }
        if (insights.lastNight == null && insights.sleepDays.all { it.totalMs == 0L }) {
            item(key = "empty") {
                EmptyState(
                    "No nights yet",
                    "Sleep appears after your first night with HabitMiner running. Keep the phone nearby as usual.",
                )
            }
            return@LazyColumn
        }
        if (last == null) {
            item(key = "missing") {
                Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text("No sleep found for last night", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "The phone was in use too often overnight to tell, or it's still early. You can add it yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = {
                        val bed = insights.sleepWeek?.avgBedtimeMinutes ?: (23 * 60)
                        val wake = insights.sleepSummary?.avgWakeMinutes ?: (7 * 60)
                        val bedMinute = ((bed % (24 * 60)) + 24 * 60) % (24 * 60)
                        val start = TimeUtil.at(if (bedMinute >= 15 * 60) today.minusDays(1) else today, bedMinute / 60, bedMinute % 60, zone)
                        fixing = NightToFix(today, start, TimeUtil.at(today, wake / 60, wake % 60, zone), source = null)
                    }) { Text("Add last night") }
                }
            }
        }
        last?.let { night ->
            item(key = "hero") {
                Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    HeroDuration(night.durationMs, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        when (night.source) {
                            SleepSource.YOU -> "asleep last night, ${Format.clock(night.sleepStart, zone)} to ${Format.clock(night.wakeTime, zone)}, as you set it"
                            else ->
                                "asleep last night, about ${Format.clock(night.sleepStart, zone)} to ${Format.clock(night.wakeTime, zone)}" +
                                    if (night.confidence == Confidence.LOW) " (a rough guess)" else ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { fixing = NightToFix(night.wakeDate, night.sleepStart, night.wakeTime, night.source) },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text(if (night.source == SleepSource.YOU) "Change times" else "Not right? Fix the times") }
                }
            }
            item(key = "lane") {
                Spacer(Modifier.height(10.dp))
                val todayNaps = insights.sleepToday?.naps.orEmpty()
                SleepLane(night, naps = todayNaps, zone = zone)
            }
            item(key = "night-details") {
                Spacer(Modifier.height(8.dp))
                RowGroup {
                    if (night.source != SleepSource.YOU && night.evidence.isNotEmpty()) {
                        row { ListRow("Why it looks like sleep", supporting = night.evidence.joinToString(", ").replaceFirstChar { it.uppercase() }) }
                    }
                    if (night.briefWakes.isNotEmpty()) {
                        row {
                            ListRow(
                                "Woke briefly",
                                supporting = night.briefWakes.joinToString(", ") { w -> Format.clock(w.start, zone) + if (w.alarm) " (alarm)" else "" },
                                trailingText = "${night.briefWakes.size}",
                            )
                        }
                    }
                    if (night.glances > 0) {
                        row {
                            ListRow(
                                "Looked at the lock screen",
                                supporting = "Without unlocking, e.g. to check the time",
                                trailingText = "${night.glances}",
                            )
                        }
                    }
                    if (night.preSleepUseMs > 0) {
                        row {
                            ListRow(
                                "Phone in the hour before sleep",
                                supporting = night.preSleepDarkShare?.let { "${Format.percent(it)} of it in the dark" },
                                trailingText = Format.duration(night.preSleepUseMs),
                            )
                        }
                    }
                    if (night.lastAppBeforeSleep != null || night.firstAppAfterWake != null) {
                        row {
                            ListRow(
                                "Last and first app",
                                supporting = listOfNotNull(night.lastAppBeforeSleep?.let { "$it before sleep" }, night.firstAppAfterWake?.let { "$it after waking" }).joinToString(", "),
                            )
                        }
                    }
                }
            }
        }

        // Naps waiting for an answer.
        insights.naps.filter { it.confidence != Confidence.LOW && it.key !in insights.answers }.forEach { nap ->
            item(key = "nap-${nap.key}") {
                Spacer(Modifier.height(16.dp))
                NapQuestionCard(nap) { asleep -> actions.answerNap(nap.key, asleep) }
            }
        }

        if (insights.sleepDays.isNotEmpty()) {
            item(key = "week-header") {
                SectionHeader("This week", info = HOW_IT_WORKS)
            }
            item(key = "week") {
                SleepWeekColumns(insights.sleepDays, usualMs = insights.sleepWeek?.avgTotalMs)
            }
            insights.sleepWeek?.let { w ->
                item(key = "week-stats") {
                    Spacer(Modifier.height(12.dp))
                    RowGroup {
                        row { ListRow("Average a day", supporting = "Night plus confirmed naps", trailingText = Format.duration(w.avgTotalMs)) }
                        row { ListRow("Average night", trailingText = Format.duration(w.avgNightMs)) }
                        row {
                            ListRow(
                                "Naps",
                                supporting = if (w.napCount == 0) "None confirmed this week" else "${Format.duration(w.napTotalMs)} in total",
                                trailingText = "${w.napCount}",
                            )
                        }
                    }
                }
            }
        }

        if (nights.size >= 2) {
            item(key = "regularity-header") {
                SectionHeader(
                    "Regularity",
                    info = "Each line is one night, from falling asleep to waking up. Lines that start and end at similar times mean a regular rhythm.",
                )
            }
            item(key = "regularity") {
                NightsChart(nights.takeLast(7), zone) { n -> fixing = NightToFix(n.wakeDate, n.sleepStart, n.wakeTime, n.source) }
            }
            insights.sleepShift?.let { shift ->
                item(key = "shift-note") { Note(shiftNote(shift)) }
            }
            insights.sleepWeek?.avgBedtimeMinutes?.let { bed ->
                item(key = "regularity-note") {
                    val spread = insights.sleepWeek?.bedtimeSpreadMinutes
                    Note(
                        "Usually asleep around ${Format.clockFromMinutes(bed)}" +
                            (spread?.let { ", give or take ${Format.duration(it * 60_000L)}" } ?: "") +
                            (insights.sleepSummary?.let { ", up around ${Format.clockFromMinutes(it.avgWakeMinutes)}" } ?: "") + ".",
                    )
                }
            }
        }
    }
}

/**
 * Small multiples of the last nights on a shared 8 pm to noon axis: one line per night, so
 * irregular bedtimes stand out without a score.
 */
private fun shiftNote(shift: SleepCorrections.Shift): String {
    fun part(
        ms: Long,
        later: String,
        earlier: String,
    ): String? =
        when {
            ms > 0 -> "${Format.duration(ms)} $later"
            ms < 0 -> "${Format.duration(-ms)} $earlier"
            else -> null
        }
    val parts =
        listOfNotNull(
            part(shift.startMs, "after putting the phone down", "before putting the phone down")?.let { "fall asleep $it" },
            part(shift.endMs, "after first picking it up", "before picking it up")?.let { "wake up $it" },
        )
    return "Your ${shift.nights} fixed nights show you usually ${parts.joinToString(" and ")}, so other nights are adjusted to match. Tap a night to fix it."
}

@Composable
private fun NightsChart(
    nights: List<SleepEstimate>,
    zone: ZoneId,
    onNight: (SleepEstimate) -> Unit,
) {
    val colors = LocalDataColors.current
    // Minutes from 20:00 of the evening before the wake date.
    fun offset(
        t: Long,
        wakeDate: java.time.LocalDate,
    ): Float = ((t - TimeUtil.at(wakeDate.minusDays(1), 20, 0, zone)) / 60_000f).coerceIn(0f, 16 * 60f)
    val desc = nights.joinToString("; ") { "${it.wakeDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${Format.clock(it.sleepStart, zone)} to ${Format.clock(it.wakeTime, zone)}" }
    Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        nights.reversed().forEach { n ->
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.clickable(onClickLabel = "Fix this night") { onNight(n) }.padding(vertical = 3.dp),
            ) {
                Text(
                    n.wakeDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Canvas(modifier = Modifier.weight(1f).height(14.dp).semantics { contentDescription = desc }) {
                    val w = size.width
                    val span = 16 * 60f
                    drawRoundRect(colors.track, Offset.Zero, size, CornerRadius(7.dp.toPx()))
                    for (h in listOf(4, 8, 12)) {
                        val x = w * (h * 60f) / span
                        drawLine(colors.usual.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
                    }
                    val s = w * offset(n.sleepStart, n.wakeDate) / span
                    val e = w * offset(n.wakeTime, n.wakeDate) / span
                    drawRoundRect(colors.sleep.copy(alpha = if (n.confidence == Confidence.LOW) 0.5f else 1f), Offset(s, 0f), Size((e - s).coerceAtLeast(2f), size.height), CornerRadius(7.dp.toPx()))
                }
                Text(
                    Format.duration(n.durationMs),
                    style = com.habitminer.ui.theme.NumberStyles.small,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(64.dp).padding(start = 10.dp),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 64.dp)) {
            val labels = hourAxisLabels(listOf(20, 0, 4, 8, 12))
            labels.forEachIndexed { i, l ->
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < labels.lastIndex) Spacer(Modifier.weight(1f))
            }
        }
    }
}
