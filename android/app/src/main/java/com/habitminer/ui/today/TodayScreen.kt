@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.habitminer.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.CheckInOption
import com.habitminer.analytics.Confidence
import com.habitminer.analytics.ContextInsight
import com.habitminer.analytics.DayDeviation
import com.habitminer.analytics.DayRibbonBuilder
import com.habitminer.analytics.Format
import com.habitminer.analytics.Light
import com.habitminer.analytics.Motion
import com.habitminer.analytics.TimeUtil
import com.habitminer.data.UserLabelEntity
import com.habitminer.engine.AnalyticsMappers
import com.habitminer.engine.HabitActions
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.TypicalUsageCalculator
import com.habitminer.ui.Labels
import com.habitminer.ui.NapQuestionCard
import com.habitminer.ui.RoutineShiftCard
import com.habitminer.ui.design.AppIcon
import com.habitminer.ui.design.BarItem
import com.habitminer.ui.design.BarList
import com.habitminer.ui.design.DayRibbonChart
import com.habitminer.ui.design.HeroContainer
import com.habitminer.ui.design.HeroDuration
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.SleepLane
import com.habitminer.ui.design.StatColumn
import com.habitminer.ui.design.StatRow
import com.habitminer.ui.design.grouped
import com.habitminer.ui.design.rememberConfirmHaptic
import com.habitminer.ui.theme.LocalDataColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where Today can send you. */
interface TodayNavigation {
    fun openSettings()

    fun openSleep()

    fun openApps()

    fun openChanges()

    fun openApp(packageName: String)
}

@Composable
fun TodayScreen(
    state: HabitUiState,
    actions: HabitActions,
    nav: TodayNavigation,
    modifier: Modifier = Modifier,
    /** Today's intention from Goals, shown under the comparison. */
    intention: String? = null,
) {
    val zone = remember { ZoneId.systemDefault() }
    val insights = state.insights
    val now = insights?.computedAt?.coerceAtLeast(state.lastUsageUpdate ?: 0L) ?: System.currentTimeMillis()
    val today = TimeUtil.dateOf(System.currentTimeMillis(), zone)

    val todaySessions = remember(state.todayAppUsage) { state.todayAppUsage.map(AnalyticsMappers::session) }
    val lastNight = insights?.lastNight?.takeIf { it.wakeDate == today }
    val todayNaps =
        remember(insights?.confirmedNaps, today) {
            insights?.confirmedNaps.orEmpty().filter { (s, _) -> TimeUtil.dateOf(s, zone) == today }
        }
    val ribbon =
        remember(todaySessions, insights?.typicalDay, lastNight, todayNaps, state.typicalUsage) {
            DayRibbonBuilder.build(todaySessions, insights?.typicalDay, lastNight, todayNaps, today, System.currentTimeMillis(), zone)
        }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        item(key = "top") { TopLine(today, nav::openSettings) }

        item(key = "hero") {
            Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                HeroDuration(state.todayScreenTimeMs)
                Spacer(Modifier.height(6.dp))
                ComparisonLine(state.todayScreenTimeMs, state.typicalUsage, today)
                intention?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Today: $it", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item(key = "ribbon") {
            Spacer(Modifier.height(18.dp))
            DayRibbonChart(ribbon)
        }

        if (state.pendingCheckInPromptedAt != null) {
            item(key = "checkin") {
                Spacer(Modifier.height(22.dp))
                CheckInPanel(onAnswer = actions::answerCheckIn, onDismiss = actions::dismissCheckIn)
            }
        }

        // One raised container: the most useful thing to read today.
        val nap =
            insights?.let { ins ->
                ins.naps.lastOrNull { it.confidence != Confidence.LOW && it.key !in ins.answers && ins.computedAt - it.end <= 12 * TimeUtil.HOUR }
            }
        val deviation =
            insights?.deviations?.days
                ?.filter { it.date == today && it.explainedBy == null && state.deviationFeedback[it.key] != UserLabelEntity.FEEDBACK_EXPECTED }
                ?.maxByOrNull { it.score }
        val contextInsight = insights?.contextInsights?.firstOrNull()
        when {
            nap != null ->
                item(key = "nap") {
                    Spacer(Modifier.height(22.dp))
                    NapQuestionCard(nap) { asleep -> actions.answerNap(nap.key, asleep) }
                }
            deviation != null ->
                item(key = "deviation-${deviation.key}") {
                    Spacer(Modifier.height(22.dp))
                    DeviationPanel(deviation, state.deviationFeedback[deviation.key], nav::openChanges) { v ->
                        actions.giveDeviationFeedback(deviation.key, v)
                    }
                }
            contextInsight != null ->
                item(key = "insight") {
                    Spacer(Modifier.height(22.dp))
                    ContextInsightPanel(contextInsight)
                }
        }

        item(key = "stats") {
            Spacer(Modifier.height(24.dp))
            StatRow {
                StatColumn(
                    "Pickups",
                    "${state.todayUnlocks}",
                    modifier = Modifier.weight(1f),
                    caption = insights?.pickupsToday?.takeIf { it.total > 0 }?.let { "${Format.percent(it.notificationShare)} after a notification" },
                )
                StatColumn(
                    "Phone-free",
                    state.phoneFree?.let { Format.duration(it.durationMs) } ?: "–",
                    modifier = Modifier.weight(1f),
                    caption = state.phoneFree?.let { "${Format.clock(it.start, zone)} to ${Format.clock(it.end, zone)}" } ?: "longest stretch today",
                )
                StatColumn(
                    "Steps",
                    if (state.stepsToday >= 0) grouped(state.stepsToday) else "–",
                    modifier = Modifier.weight(1f),
                    caption = if (state.stepsToday < 0) (if (state.stepPermission) "from your next steps" else "needs permission") else "today",
                )
            }
        }

        insights?.let { ins -> ins.deviations.shift?.takeIf { it.label == null && it.key !in ins.answers } }?.let { shift ->
            item(key = "shift") {
                Spacer(Modifier.height(16.dp))
                RoutineShiftCard(shift, null, compact = true) { value -> actions.labelPeriod(shift.key, shift.since, value) }
            }
        }

        if (lastNight != null) {
            item(key = "sleep-header") { SectionHeader("Last night", actionLabel = "Sleep", onAction = nav::openSleep) }
            item(key = "sleep") {
                SleepLane(lastNight, naps = todayNaps, zone = zone)
                Spacer(Modifier.height(8.dp))
                val day = insights?.sleepToday
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                            append("${Format.duration(lastNight.durationMs)} asleep")
                        }
                        append(", ${Format.clock(lastNight.sleepStart, zone)} to ${Format.clock(lastNight.wakeTime, zone)}")
                        if (lastNight.briefWakes.isNotEmpty()) {
                            append(if (lastNight.briefWakes.size == 1) ", woke briefly once" else ", woke briefly ${lastNight.briefWakes.size} times")
                        }
                        if (day != null && day.naps.isNotEmpty()) append(". With today's nap: ${Format.duration(day.totalMs)}")
                        if (lastNight.confidence == Confidence.LOW) append(" (a rough guess)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }

        if (state.predictions.isNotEmpty()) {
            item(key = "next-header") {
                SectionHeader(
                    "Likely next",
                    info =
                        "After ${state.predictionsAfter ?: "your last app"}, these are the apps you tend to open, from your recent app switches " +
                            "(recent days count more).\n\nTrends → Routines shows how often these guesses are right.",
                )
            }
            item(key = "next") {
                FlowRow(
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.predictions.take(3).forEach { p ->
                        AssistChip(
                            onClick = { p.packageName?.let(nav::openApp) },
                            label = { Text("${p.appName}  ${Format.percent(p.share)}") },
                            leadingIcon = { AppIcon(p.packageName, p.appName, size = AssistChipDefaults.IconSize) },
                        )
                    }
                }
            }
        }

        if (state.todayUsageByApp.isNotEmpty()) {
            item(key = "apps-header") { SectionHeader("Apps today", actionLabel = "All apps", onAction = nav::openApps) }
            item(key = "apps") {
                val packages = remember(state.todayAppUsage) { state.todayAppUsage.associate { it.appName to it.packageName } }
                val colors = LocalDataColors.current
                val top = remember(state.todayUsageByApp) { state.todayUsageByApp.entries.sortedByDescending { it.value }.take(5) }
                BarList(
                    top.map { (app, ms) ->
                        BarItem(app, ms, Format.duration(ms), colors.screen, leading = { AppIcon(packages[app], app, size = 28.dp) })
                    },
                )
            }
        }

        item(key = "around") { AroundYou(state) }
    }
}

@Composable
private fun TopLine(
    today: java.time.LocalDate,
    onSettings: () -> Unit,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(today.format(fmt), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

/** "26 min less than a usual Wednesday by now", or why there's no comparison yet. */
@Composable
private fun ComparisonLine(
    screenMs: Long,
    typical: TypicalUsageCalculator.TypicalUsage?,
    today: java.time.LocalDate,
) {
    val text =
        if (typical == null) {
            buildAnnotatedString { append("A comparison with your usual day appears after one full day.") }
        } else {
            val diff = screenMs - typical.expectedByNowMs
            val tolerance = maxOf(typical.expectedByNowMs / 10, 10 * 60_000L)
            val dayName =
                if (typical.basis == TypicalUsageCalculator.Basis.SAME_DAY_TYPE) {
                    if (TimeUtil.isWeekend(today)) "weekend day" else "weekday"
                } else {
                    "day"
                }
            buildAnnotatedString {
                when {
                    diff > tolerance -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append("${Format.duration(diff)} more") }
                        append(" than a usual $dayName by now")
                    }
                    diff < -tolerance -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append("${Format.duration(-diff)} less") }
                        append(" than a usual $dayName by now")
                    }
                    else -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append("About usual") }
                        append(" for a $dayName at this time")
                    }
                }
            }
        }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CheckInPanel(
    onAnswer: (CheckInOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = rememberConfirmHaptic()
    HeroContainer {
        Text("What are you doing right now?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your answer checks HabitMiner's guesses. It stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckInOption.entries.forEach { option ->
                OutlinedButton(onClick = {
                    haptic()
                    onAnswer(option)
                }) { Text(option.label) }
            }
            TextButton(onClick = onDismiss) { Text("Skip") }
        }
    }
}

@Composable
private fun DeviationPanel(
    dev: DayDeviation,
    feedback: String?,
    onSeeAll: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val haptic = rememberConfirmHaptic()
    HeroContainer {
        Text("Different today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(dev.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(dev.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        when (feedback) {
            UserLabelEntity.FEEDBACK_UNUSUAL -> Text("You marked this as unusual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_EXPECTED)
                    }) { Text("Expected") }
                    OutlinedButton(onClick = {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_UNUSUAL)
                    }) { Text("Unusual") }
                    TextButton(onClick = onSeeAll) { Text("All changes") }
                }
        }
    }
}

@Composable
private fun ContextInsightPanel(insight: ContextInsight) {
    HeroContainer {
        Text("Worth knowing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(insight.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(insight.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Light, motion, battery and place from the latest reading, as quiet labelled items. */
@Composable
private fun AroundYou(state: HabitUiState) {
    val sensors = state.latestSensorContext
    val latest = state.latestContext
    if (sensors == null && latest == null) return
    SectionHeader(
        "Around you",
        info =
            "Light and motion are read when you unlock the phone, when you open HabitMiner, and every 5 to 30 minutes while the " +
                "screen is on. Motion also counts as moving when the step counter saw you walking.",
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sensors?.let { s ->
            if (s.lightLux >= 0f) {
                val light = com.habitminer.analytics.ContextLabels.light(s.lightLux)
                when (light) {
                    Light.DARK -> AroundItem(Icons.Rounded.DarkMode, "Dark")
                    Light.DIM -> AroundItem(Icons.Rounded.WbTwilight, "Dim")
                    Light.BRIGHT -> AroundItem(Icons.Rounded.LightMode, "Bright")
                    null -> Unit
                }
            }
            Labels.motion(s)?.let { m ->
                AroundItem(
                    Icons.Rounded.DirectionsWalk,
                    when (m) {
                        Motion.STILL -> "Still"
                        Motion.MOVING -> "Moving"
                        Motion.ACTIVE -> "Very active"
                    },
                )
            }
        }
        latest?.let { l ->
            if (l.batteryLevel in 0..100) {
                AroundItem(if (l.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, "${l.batteryLevel}%" + if (l.isCharging) ", charging" else "")
            }
            l.wifiPlace?.let { hash -> state.insights?.placeNames?.get(hash)?.let { AroundItem(Icons.Rounded.Place, it) } }
        }
    }
    sensors?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            "Read ${Labels.age(it.timestamp)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun AroundItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
