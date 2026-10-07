@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitminer.analytics.AppDetail
import com.habitminer.analytics.Format
import com.habitminer.ui.design.AppIcon
import com.habitminer.ui.design.EmptyState
import com.habitminer.ui.design.HeroDuration
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.Skeleton
import com.habitminer.ui.design.Sparkline
import com.habitminer.ui.design.StatColumn
import com.habitminer.ui.design.StatRow
import com.habitminer.ui.design.hourAxisLabels
import com.habitminer.ui.theme.LocalDataColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun AppDetailRoute(onBack: () -> Unit) {
    val vm: AppDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    AppDetailContent(vm.packageName, state, onBack)
}

@Composable
fun AppDetailContent(
    packageName: String,
    state: AppDetailState,
    onBack: () -> Unit,
) {
    val d = state.detail
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "bar") {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(4.dp))
                AppIcon(packageName, d?.appName ?: packageName, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(d?.appName ?: "", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        when {
            state.loading -> item(key = "loading") { Skeleton() }
            d == null ->
                item(key = "empty") {
                    EmptyState("No recent use", "This app hasn't been used in the last five weeks, so there's nothing to show yet.")
                }
            else -> detail(d, state.routines)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.detail(
    d: AppDetail,
    routines: List<String>,
) {
    item(key = "hero") {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
            HeroDuration(d.perDayMs)
            Text(
                "a day on average over the last ${d.days} day${if (d.days == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "stats") {
        val change = d.weekMs - d.previousWeekMs
        StatRow {
            StatColumn(
                "This week",
                Format.duration(d.weekMs),
                modifier = Modifier.weight(1f),
                caption =
                    when {
                        d.previousWeekMs == 0L -> "new this week"
                        kotlin.math.abs(change) < 10 * 60_000L -> "about the same"
                        change > 0 -> "${Format.duration(change)} more"
                        else -> "${Format.duration(-change)} less"
                    },
            )
            StatColumn("Opens", "${d.opens / d.days.coerceAtLeast(1)}", modifier = Modifier.weight(1f), caption = "a day")
            StatColumn("Typical visit", Format.duration(d.medianSessionMs), modifier = Modifier.weight(1f), caption = "median")
        }
    }
    item(key = "trend-header") { SectionHeader("Last 14 days") }
    item(key = "trend") {
        Sparkline(d.last14, LocalDataColors.current.screen, modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), height = 56.dp)
    }
    item(key = "grid-header") {
        SectionHeader(
            "When you use it",
            info = "Average minutes in each hour of each weekday, over the weeks HabitMiner has seen. Darker means more.",
        )
    }
    item(key = "grid") { WeekdayHourGrid(d.weekdayHour) }
    d.busiestHour?.let { h -> item(key = "busiest") { Note("Most often around ${Format.hourRange(h)}.") } }
    item(key = "why-header") { SectionHeader("What brings you here") }
    item(key = "why") {
        RowGroup {
            row {
                ListRow(
                    "Opened right after its notification",
                    supporting = "Within a minute of a notification from ${d.appName}",
                    trailingText = if (d.opens > 0) Format.percent(d.opensAfterNotification.toFloat() / d.opens) else "–",
                )
            }
            row {
                ListRow(
                    "Notifications",
                    supporting = if (d.notifications == 0) "None recorded (needs notification access)" else "In the last five weeks",
                    trailingText = "${d.notifications}",
                )
            }
        }
    }
    if (routines.isNotEmpty()) {
        item(key = "routines-header") { SectionHeader("Part of your routines") }
        item(key = "routines") {
            RowGroup { routines.forEach { r -> row { ListRow(r) } } }
        }
    }
}

/** Weekday rows (Mon to Sun) by 24 hours, shaded by average minutes. */
@Composable
private fun WeekdayHourGrid(grid: Array<IntArray>) {
    val colors = LocalDataColors.current
    val max = grid.maxOf { row -> row.maxOrNull() ?: 0 }.coerceAtLeast(1)
    Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        Row {
            Column(modifier = Modifier.width(34.dp)) {
                DayOfWeek.values().forEach { day ->
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Canvas(modifier = Modifier.weight(1f).height((18 * 7).dp).semantics { contentDescription = "Use by weekday and hour" }) {
                val cw = size.width / 24f
                val ch = size.height / 7f
                val g = 1.5.dp.toPx()
                for (r in 0 until 7) {
                    for (c in 0 until 24) {
                        val m = grid[r][c]
                        val col = if (m == 0) colors.track else colors.screen.copy(alpha = 0.2f + 0.8f * m / max)
                        drawRoundRect(col, Offset(c * cw + g / 2, r * ch + g / 2), Size(cw - g, ch - g), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.width(34.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                hourAxisLabels(listOf(0, 6, 12, 18, 24)).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
