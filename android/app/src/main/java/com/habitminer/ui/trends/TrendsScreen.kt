@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.habitminer.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.AppSummary
import com.habitminer.analytics.DayTimelineBuilder
import com.habitminer.analytics.DayTypes
import com.habitminer.analytics.Format
import com.habitminer.analytics.PredictabilityResult
import com.habitminer.analytics.SessionGrouper
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.WeekComparison
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.engine.HabitActions
import com.habitminer.engine.HabitUiState
import com.habitminer.ui.DeviationCard
import com.habitminer.ui.Labels
import com.habitminer.ui.RecentGuessesList
import com.habitminer.ui.RoutineShiftCard
import com.habitminer.ui.components.DayTimelineStrip
import com.habitminer.ui.components.TypicalDayChart
import com.habitminer.ui.dayLabel
import com.habitminer.ui.design.AppIcon
import com.habitminer.ui.design.EmptyState
import com.habitminer.ui.design.HeroDuration
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScoreLine
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.ScreenTitle
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.Skeleton
import com.habitminer.ui.design.Sparkline
import com.habitminer.ui.design.UseHeatmap
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.NumberStyles
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class TrendsTab(val label: String) {
    OVERVIEW("Overview"),
    APPS("Apps"),
    ROUTINES("Routines"),
    CHANGES("Changes"),
    HISTORY("History"),
}

@Composable
fun TrendsScreen(
    state: HabitUiState,
    actions: HabitActions,
    initialTab: TrendsTab = TrendsTab.OVERVIEW,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle("Trends")
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(TrendsTab.entries) { t ->
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(tab) { listState.scrollToItem(0) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.insights == null && tab != TrendsTab.APPS && tab != TrendsTab.HISTORY) {
                item(key = "loading") { Skeleton() }
            } else {
                when (tab) {
                    TrendsTab.OVERVIEW -> overview(state)
                    TrendsTab.APPS -> apps(state, onOpenApp)
                    TrendsTab.ROUTINES -> routines(state)
                    TrendsTab.CHANGES -> changes(state, actions)
                    TrendsTab.HISTORY -> history(state, actions)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------------------

private fun LazyListScope.overview(state: HabitUiState) {
    val insights = state.insights ?: return
    insights.week?.let { week ->
        item(key = "week-hero") {
            Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                HeroDuration(week.currentPerDayMs)
                Text(weekLine(week), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.dailyTotals.size >= 2) {
        item(key = "fortnight") {
            Sparkline(
                state.dailyTotals.map { it.second / 60_000f },
                LocalDataColors.current.screen,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
                height = 64.dp,
            )
            val fmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                Text(state.dailyTotals.first().first.format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    insights.week?.biggestMovers?.takeIf { it.isNotEmpty() }?.let { movers ->
        item(key = "movers-header") { SectionHeader("Biggest changes", info = "Average time a day in each app this week compared with the week before.") }
        item(key = "movers") {
            RowGroup {
                movers.forEach { c ->
                    row {
                        ListRow(
                            c.name,
                            supporting = "${Format.duration(c.currentPerDayMs)} a day now",
                            trailingText = (if (c.deltaMs > 0) "+" else "−") + Format.duration(kotlin.math.abs(c.deltaMs)),
                        )
                    }
                }
            }
        }
    }
    insights.typicalDay?.let { curve ->
        item(key = "curve-header") {
            SectionHeader(
                "Today against a usual day",
                info = "The line is your phone time adding up through today. The dashed line is a usual day (${curve.selection.description}) and the shaded band the usual range.",
            )
        }
        item(key = "curve") { Box(Modifier.padding(horizontal = ScreenPadding)) { TypicalDayChart(curve) } }
    }
    insights.heatmap?.takeIf { it.maxMinutes > 0 }?.let { map ->
        item(key = "heat-header") { SectionHeader("When you use your phone") }
        item(key = "heat") { UseHeatmap(map) }
    }
    item(key = "types") { DayTypesSection(insights.dayTypes) }
    if (insights.placeUsage.isNotEmpty()) {
        item(key = "places-header") { SectionHeader("Where you use your phone", info = "From the Wi-Fi network you're connected to. Rename places in Settings.") }
        item(key = "places") {
            RowGroup {
                insights.placeUsage.take(5).forEach { p ->
                    row { ListRow(p.place, supporting = p.topApp?.let { "Mostly $it" }, trailingText = "${Format.duration(p.perDayMs)}/day") }
                }
            }
        }
    }
    if (insights.contextInsights.isNotEmpty()) {
        item(key = "context-header") { SectionHeader("Patterns in your surroundings") }
        item(key = "context") {
            RowGroup { insights.contextInsights.forEach { c -> row { ListRow(c.headline, supporting = c.detail) } } }
        }
    }
}

private fun weekLine(week: WeekComparison): String {
    val delta = week.deltaMs
    val change =
        when {
            kotlin.math.abs(delta) < 5 * 60_000L -> "about the same as the week before"
            delta > 0 -> "${Format.duration(delta)} more than the week before"
            else -> "${Format.duration(-delta)} less than the week before"
        }
    return "a day over the last 7 days, $change"
}

@Composable
private fun DayTypesSection(dayTypes: DayTypes?) {
    SectionHeader(
        "Your kinds of days",
        info = "Days are grouped by when and how much you used your phone (k-means clustering). The small bars show each group's use in 4-hour blocks from midnight.",
    )
    if (dayTypes == null) {
        Note("Needs at least 6 full days of data to group your days.")
        return
    }
    val colors = LocalDataColors.current.series
    val maxBlock = dayTypes.types.maxOf { t -> t.blocks.maxOrNull() ?: 0.0 }.coerceAtLeast(1.0)
    RowGroup {
        dayTypes.types.forEachIndexed { i, type ->
            row {
                ListRow(
                    type.name,
                    supporting = "${type.description}, ${type.days.size} days",
                    leading = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colors[i % colors.size])) },
                    trailing = {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(28.dp)) {
                            type.blocks.forEach { m ->
                                Box(
                                    Modifier.width(6.dp).height((28 * (m / maxBlock)).coerceAtLeast(2.0).dp)
                                        .clip(RoundedCornerShape(2.dp)).background(colors[i % colors.size]),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    val recent = dayTypes.dayToType.keys.sorted().takeLast(14)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        recent.forEach { date ->
            val idx = dayTypes.dayToType[date] ?: 0
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(colors[idx % colors.size]))
                Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------------------

private enum class AppRange(val label: String) { TODAY("Today"), WEEK("7 days"), MONTH("5 weeks") }

private fun LazyListScope.apps(
    state: HabitUiState,
    onOpenApp: (String) -> Unit,
) {
    item(key = "apps") { AppsList(state.appSummaries, onOpenApp) }
}

@Composable
private fun AppsList(
    apps: List<AppSummary>,
    onOpenApp: (String) -> Unit,
) {
    var range by rememberSaveable { mutableIntStateOf(1) }
    val r = AppRange.entries[range]
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        AppRange.entries.forEachIndexed { i, opt ->
            SegmentedButton(selected = range == i, onClick = { range = i }, shape = SegmentedButtonDefaults.itemShape(i, AppRange.entries.size)) { Text(opt.label) }
        }
    }
    val rows =
        remember(apps, r) {
            apps.map { a ->
                val (ms, opens) =
                    when (r) {
                        AppRange.TODAY -> a.todayMs to a.todayOpens
                        AppRange.WEEK -> a.weekMs to a.weekOpens
                        AppRange.MONTH -> a.windowMs to a.windowOpens
                    }
                Triple(a, ms, opens)
            }.filter { it.second > 0 }.sortedByDescending { it.second }
        }
    if (rows.isEmpty()) {
        EmptyState("No apps yet", "Apps you use appear here with their time and how often you open them.")
        return
    }
    val max = rows.first().second.coerceAtLeast(1L)
    val colors = LocalDataColors.current
    RowGroup {
        rows.take(40).forEach { (a, ms, opens) ->
            row {
                Column {
                    ListRow(
                        a.appName,
                        supporting = "$opens open${if (opens == 1) "" else "s"}",
                        leading = { AppIcon(a.packageName, a.appName, size = 36.dp) },
                        trailingText = Format.duration(ms),
                        onClick = { onOpenApp(a.packageName) },
                    )
                    Box(
                        Modifier.padding(start = 66.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(colors.track),
                    ) {
                        Box(Modifier.fillMaxWidth((ms.toFloat() / max).coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.screen))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Routines
// ---------------------------------------------------------------------------------------

private fun LazyListScope.routines(state: HabitUiState) {
    val insights = state.insights ?: return
    item(key = "predict-header") {
        SectionHeader(
            "How predictable is your next app?",
            info =
                "Tested on your recent app switches: before each switch HabitMiner guesses, then learns from what you opened. " +
                    "It weighs what usually follows your current app, your last two apps, the hour of day, apps used in the last hour " +
                    "and apps that just notified you, and favours recent days.",
        )
    }
    item(key = "predict") { PredictabilitySection(insights.predictability, insights.predictionDriftAt) }
    if (insights.recentGuesses.isNotEmpty()) {
        item(key = "guesses-header") {
            val hits = insights.recentGuesses.count { it.hit }
            SectionHeader("Its latest guesses")
            Note("$hits of ${insights.recentGuesses.size} right first time. Each guess was made before the switch, from what happened earlier.")
        }
        item(key = "guesses") { RecentGuessesList(insights.recentGuesses, max = 10) }
    }
    item(key = "routines-header") {
        SectionHeader("Your routines", info = "App sequences you repeat at the same time of day, most reliable first. A routine needs the same sequence on at least 5 days.")
    }
    val groups = insights.patternGroups
    if (groups.isEmpty()) {
        item(key = "routines-empty") { Note("No strong routines yet. They appear once the same app sequence shows up on at least 5 days.") }
    } else {
        item(key = "routines") {
            RowGroup {
                groups.forEach { g -> row { ListRow(g.sequence, supporting = "${g.whenText}. ${g.evidenceText}, last seen ${Labels.shortDate(g.lastSeen)}") } }
            }
        }
    }
}

@Composable
private fun PredictabilitySection(
    result: PredictabilityResult?,
    driftAt: Long?,
) {
    if (result == null) {
        Note("Needs a few more days of app switches to measure.")
        return
    }
    val colors = LocalDataColors.current
    Row(modifier = Modifier.padding(horizontal = ScreenPadding), verticalAlignment = Alignment.Bottom) {
        Text(Format.percent(result.hitRate), style = NumberStyles.hero.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text("right first time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    ScoreLine("Right within its top 3", result.top3HitRate, colors.screen.copy(alpha = 0.75f))
    if (result.top5HitRate > 0f) ScoreLine("Right within its top 5", result.top5HitRate, colors.screen.copy(alpha = 0.5f))
    val notified = result.notificationHitRate
    val self = result.selfStartedHitRate
    if (notified != null && self != null && result.notificationSwitches >= 5) {
        Spacer(Modifier.height(8.dp))
        SubHeading("Right first time, by how you got there")
        ScoreLine("The app had just notified you (${result.notificationSwitches})", notified, colors.pickups)
        ScoreLine("You opened it yourself (${result.testedTransitions - result.notificationSwitches})", self, colors.screen.copy(alpha = 0.75f))
    }
    Spacer(Modifier.height(8.dp))
    SubHeading("Simpler ways to guess, for comparison")
    if (result.markovBaseline > 0f) ScoreLine("What most often follows this app", result.markovBaseline, colors.usual.copy(alpha = 0.6f))
    if (result.recentBaseline > 0f) ScoreLine("Going back to the app before", result.recentBaseline, colors.usual.copy(alpha = 0.5f))
    ScoreLine("Always your most-used app", result.mostUsedBaseline, colors.usual.copy(alpha = 0.4f))
    ScoreLine("Random guess", result.randomBaseline, colors.usual.copy(alpha = 0.3f))
    val best = maxOf(result.mostUsedBaseline, result.markovBaseline, result.recentBaseline)
    val lift = result.hitRate - best
    Note(
        when {
            lift >= 0.08f -> "Your app switching follows clear routines. "
            lift > 0.02f -> "Your app switching is somewhat routine. "
            else -> "Your app switching varies a lot, which is normal. "
        } + "Tested on ${result.testedTransitions} switches from the last ${result.testDays} days.",
    )
    if (driftAt != null) {
        Note(
            "Its accuracy shifted around ${Labels.shortDate(driftAt)}, which usually means a routine changed. " +
                "It gives recent days more weight, so it catches up within a day or two.",
        )
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 2.dp),
    )
}

// ---------------------------------------------------------------------------------------
// Changes
// ---------------------------------------------------------------------------------------

private fun LazyListScope.changes(
    state: HabitUiState,
    actions: HabitActions,
) {
    val insights = state.insights ?: return
    item(key = "changes-note") {
        Note(
            "Each day, and today so far, compared with your usual days (same weekday or weekend type, last four weeks). " +
                "Shown when the difference is both unusual for you and large.",
        )
    }
    insights.deviations.shift?.let { shift ->
        item(key = shift.key) {
            Spacer(Modifier.height(8.dp))
            RoutineShiftCard(shift, insights.answers[shift.key]) { value -> actions.labelPeriod(shift.key, shift.since, value) }
        }
    }
    val days = insights.deviations.days
    if (days.isEmpty()) {
        item(key = "changes-empty") { EmptyState("Nothing unusual", "No clear differences from your usual days this week.") }
        return
    }
    val today = TimeUtil.dateOf(insights.computedAt, ZoneId.systemDefault())
    days.groupBy { it.date }.forEach { (date, list) ->
        item(key = "day-$date") {
            SectionHeader(dayLabel(date, today) + (list.firstNotNullOfOrNull { it.explainedBy }?.let { ", $it" } ?: ""))
        }
        list.forEach { dev ->
            item(key = dev.key) {
                DeviationCard(dev, state.deviationFeedback[dev.key], showDay = false) { value -> actions.giveDeviationFeedback(dev.key, value) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------

private fun LazyListScope.history(
    state: HabitUiState,
    actions: HabitActions,
) {
    item(key = "history") { HistoryDay(state, actions) }
}

@Composable
private fun HistoryDay(
    state: HabitUiState,
    actions: HabitActions,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { java.time.LocalDate.now(zone) }
    val days = remember(today) { (0..13).map { today.minusDays(it.toLong()) } }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val selected = TimeUtil.dateOf(state.selectedHistoryDate, zone)
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true,
    ) {
        items(days) { d ->
            FilterChip(
                selected = d == selected,
                onClick = { actions.selectHistoryDate(TimeUtil.startOfDay(d, zone)) },
                label = { Text(if (d == today) "Today" else d.format(fmt)) },
            )
        }
    }
    val sessions = remember(state.historicalAppUsage) { state.historicalAppUsage.map(AnalyticsMappers::session) }
    val screenSessions =
        remember(sessions) { SessionGrouper.group(sessions).filterNot { it.isOnlySystemNoise && it.totalMs < 2 * TimeUtil.MINUTE } }
    val timeline =
        remember(sessions, state.historicalSnapshots, state.insights?.sleepNights, state.insights?.confirmedNaps, selected) {
            DayTimelineBuilder.build(
                sessions,
                state.historicalSnapshots.map(AnalyticsMappers::sample),
                state.insights?.sleepNights.orEmpty(),
                selected,
                zone,
                naps = state.insights?.confirmedNaps.orEmpty(),
            )
        }
    Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 16.dp)) {
        HeroDuration(timeline.totalMs, countUp = false)
        Text(
            "${screenSessions.size} session${if (screenSessions.size == 1) "" else "s"} (back-to-back app use counts as one)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        DayTimelineStrip(timeline)
    }
    if (screenSessions.isEmpty()) {
        EmptyState("Nothing recorded", "There's no phone use recorded on this day.")
        return
    }
    SectionHeader("Sessions")
    RowGroup {
        screenSessions.sortedByDescending { it.start }.take(60).forEach { session ->
            row {
                val apps = session.appTotals
                val main = apps.take(3).joinToString(", ") { (app, ms) -> "$app ${Format.duration(ms)}" }
                val more = if (apps.size > 3) ", and ${apps.size - 3} more" else ""
                ListRow(
                    main + more,
                    supporting = "${Format.clock(session.start, zone)} to ${Format.clock(session.end, zone)}",
                    trailingText = Format.duration(session.totalMs),
                )
            }
        }
    }
}
