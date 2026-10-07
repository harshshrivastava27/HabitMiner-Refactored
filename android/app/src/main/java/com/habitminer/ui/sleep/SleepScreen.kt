@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.sleep

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.habitminer.analytics.SleepEstimate
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
        "as \"about\" and rounded; regularity matters more than any single night."

@Composable
fun SleepScreen(
    state: HabitUiState,
    actions: HabitActions,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val insights = state.insights
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "title") { ScreenTitle("Sleep") }
        if (insights == null) {
            item(key = "loading") { Skeleton() }
            return@LazyColumn
        }
        val nights = insights.sleepNights
        val last = insights.lastNight
        if (last == null && insights.sleepDays.all { it.totalMs == 0L }) {
            item(key = "empty") {
                EmptyState(
                    "No nights yet",
                    "Sleep appears after your first night with HabitMiner running. Keep the phone nearby as usual.",
                )
            }
            return@LazyColumn
        }
        last?.let { night ->
            item(key = "hero") {
                Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    HeroDuration(night.durationMs, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "asleep last night, about ${Format.clock(night.sleepStart, zone)} to ${Format.clock(night.wakeTime, zone)}" +
                            if (night.confidence == Confidence.LOW) " (a rough guess)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    if (night.briefWakes.isNotEmpty()) {
                        row {
                            ListRow(
                                "Woke briefly",
                                supporting = night.briefWakes.joinToString(", ") { w -> Format.clock(w.start, zone) + if (w.alarm) " (alarm)" else "" },
                                trailingText = "${night.briefWakes.size}",
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
            item(key = "regularity") { NightsChart(nights.takeLast(7), zone) }
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
@Composable
private fun NightsChart(
    nights: List<SleepEstimate>,
    zone: ZoneId,
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
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
