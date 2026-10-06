@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.DayTimelineBuilder
import com.habitminer.analytics.Format
import com.habitminer.analytics.ScreenSession
import com.habitminer.analytics.SessionGrouper
import com.habitminer.analytics.TimeUtil
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.HabitActions
import com.habitminer.ui.components.DayTimelineStrip
import com.habitminer.ui.components.EmptyState
import com.habitminer.ui.components.Hint
import com.habitminer.ui.components.Pill
import com.habitminer.ui.components.StatBlock
import com.habitminer.ui.components.SurfaceCard
import com.habitminer.ui.components.categoryColor
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed class HistoryItem {
    abstract val time: Long
    abstract val key: String

    data class Session(val session: ScreenSession) : HistoryItem() {
        override val time: Long = session.start
        override val key: String = "s${session.start}"
    }

    data class Context(val snapshot: ContextSnapshotEntity) : HistoryItem() {
        override val time: Long = snapshot.timestamp
        override val key: String = "c${snapshot.id}_${snapshot.timestamp}"
    }

    /** Consecutive readings with the screen off, shown as one row instead of one per reading. */
    data class ScreenOff(val readings: List<ContextSnapshotEntity>) : HistoryItem() {
        override val time: Long = readings.first().timestamp
        override val key: String = "o${readings.first().id}_${readings.first().timestamp}"
    }
}

private fun hasSensors(s: ContextSnapshotEntity) = s.lightLux >= 0f || s.accelVariance >= 0f

/** Groups runs of sensor-less (screen-off) readings so they don't flood the list. */
private fun contextItems(snapshots: List<ContextSnapshotEntity>): List<HistoryItem> {
    val out = mutableListOf<HistoryItem>()
    var run = mutableListOf<ContextSnapshotEntity>()
    for (s in snapshots.sortedBy { it.timestamp }) {
        if (hasSensors(s)) {
            if (run.isNotEmpty()) out.add(HistoryItem.ScreenOff(run))
            run = mutableListOf()
            out.add(HistoryItem.Context(s))
        } else {
            run.add(s)
        }
    }
    if (run.isNotEmpty()) out.add(HistoryItem.ScreenOff(run))
    return out
}

@Composable
fun HistoryScreen(
    state: HabitUiState,
    viewModel: HabitActions,
) {
    val filters = listOf("Everything", "Apps", "Surroundings")
    var filter by remember { mutableStateOf(filters[0]) }
    val zone = remember { ZoneId.systemDefault() }

    val todayStart =
        remember {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    val dateList =
        remember {
            val cal = Calendar.getInstance().apply { timeInMillis = todayStart }
            (0..13).map {
                val ms = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, -1)
                ms
            }.reversed()
        }
    val dateFormat = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }

    val sessions = remember(state.historicalAppUsage) { state.historicalAppUsage.map(AnalyticsMappers::session) }
    val screenSessions =
        remember(sessions) {
            SessionGrouper.group(sessions).filterNot { it.isOnlySystemNoise && it.totalMs < 2 * TimeUtil.MINUTE }
        }
    val timeline =
        remember(sessions, state.historicalSnapshots, state.insights?.sleepNights, state.insights?.confirmedNaps) {
            DayTimelineBuilder.build(
                sessions,
                state.historicalSnapshots.map(AnalyticsMappers::sample),
                state.insights?.sleepNights.orEmpty(),
                TimeUtil.dateOf(state.selectedHistoryDate, zone),
                zone,
                naps = state.insights?.confirmedNaps.orEmpty(),
            )
        }
    val historyItems =
        remember(screenSessions, state.historicalSnapshots, filter) {
            val list = mutableListOf<HistoryItem>()
            if (filter != "Surroundings") screenSessions.forEach { list.add(HistoryItem.Session(it)) }
            if (filter != "Apps") list.addAll(contextItems(state.historicalSnapshots))
            list.sortedByDescending { it.time }
        }

    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedHistoryDate) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "History",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = true) {
                items(dateList.reversed()) { dateMs ->
                    FilterChip(
                        selected = state.selectedHistoryDate == dateMs,
                        onClick = { viewModel.selectHistoryDate(dateMs) },
                        label = { Text(if (dateMs == todayStart) "Today" else dateFormat.format(Date(dateMs))) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }
            }
        }

        item { DaySummaryCard(timeline.totalMs, screenSessions, timeline) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                }
            }
        }

        if (historyItems.isEmpty()) {
            item {
                Box(modifier = Modifier.height(240.dp)) {
                    EmptyState(title = "Nothing recorded", message = "There's no data for this filter on this day.")
                }
            }
        } else {
            items(historyItems, key = { it.key }) { item ->
                when (item) {
                    is HistoryItem.Session -> SessionCard(item.session, zone)
                    is HistoryItem.Context -> ContextRow(Labels.time(item.snapshot.timestamp), Labels.contextSummary(item.snapshot))
                    is HistoryItem.ScreenOff -> {
                        val first = item.readings.first()
                        val last = item.readings.last()
                        val range =
                            if (item.readings.size == 1) Labels.time(first.timestamp) else "${Labels.time(first.timestamp)} – ${Labels.time(last.timestamp)}"
                        val battery =
                            if (first.batteryLevel in 0..100 && last.batteryLevel in 0..100) {
                                if (first.batteryLevel == last.batteryLevel) " · ${last.batteryLevel}% battery" else " · battery ${first.batteryLevel}% → ${last.batteryLevel}%"
                            } else {
                                ""
                            }
                        val charging = if (item.readings.any { it.isCharging }) ", charging" else ""
                        val steps = item.readings.sumOf { it.stepsSinceLastSnapshot.coerceAtLeast(0) }
                        val stepText = if (steps > 0) " · $steps steps" else ""
                        ContextRow(range, "Screen off$stepText$battery$charging")
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun DaySummaryCard(
    totalMs: Long,
    sessions: List<ScreenSession>,
    timeline: com.habitminer.analytics.DayTimelineData,
) {
    SurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("Screen time", Format.duration(totalMs))
            StatBlock("Sessions", "${sessions.size}", alignEnd = true, caption = "back-to-back app use counts as one")
        }
        Spacer(modifier = Modifier.height(14.dp))
        DayTimelineStrip(timeline)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    session: ScreenSession,
    zone: ZoneId,
) {
    val apps = session.appTotals
    val main = apps.take(3).joinToString(" · ") { (app, ms) -> "$app ${Format.duration(ms)}" }
    val more = if (apps.size > 3) " · +${apps.size - 3} more" else ""
    SurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Format.clock(session.start, zone)} – ${Format.clock(session.end, zone)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = Format.duration(session.totalMs),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = main + more,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (session.usages.size > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            val order =
                session.usages.map { it.appName }
                    .fold(mutableListOf<String>()) { acc, app -> if (acc.lastOrNull() != app) acc.add(app); acc }
            Hint("In order: " + order.joinToString(" → "))
        }
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            session.categoryTotals.take(3).forEach { (cat, _) -> Pill(cat.label, categoryColor(cat)) }
        }
    }
}

@Composable
private fun ContextRow(
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }
    }
}
