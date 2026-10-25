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
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Lightbulb
import com.habitminer.ui.design.MetricTile
import com.habitminer.ui.design.TileRow
import com.habitminer.ui.design.ActivityRings
import com.habitminer.ui.design.RingSpec
import com.habitminer.ui.design.RingLegend
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.habitminer.ui.isMore
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

    /** Opens the system file picker for an export ZIP. */
    fun pickImportFile() {}
}

@Composable
fun TodayScreen(
    state: HabitUiState,
    actions: HabitActions,
    nav: TodayNavigation,
    modifier: Modifier = Modifier,
    /** Today's intention from Goals, shown under the comparison. */
    intention: String? = null,
    /** The daily phone-time target from Goals, which the screen-time ring closes at. */
    dailyTargetMinutes: Int? = null,
    now: Long = System.currentTimeMillis(),
) {
    val zone = remember { ZoneId.systemDefault() }
    val insights = state.insights
    val today = TimeUtil.dateOf(now, zone)
    // The insight you just answered: thanked for a few seconds, then put away for the day.
    var answeredInsight by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(answeredInsight) {
        val key = answeredInsight ?: return@LaunchedEffect
        kotlinx.coroutines.delay(6_000L)
        actions.hideInsight(key)
        answeredInsight = null
    }
    // A nap whose times you're setting ("Yes, but at other times").
    var napEditing by remember { androidx.compose.runtime.mutableStateOf<com.habitminer.ui.sleep.NapToEdit?>(null) }
    napEditing?.let { nap ->
        com.habitminer.ui.sleep.NapSheet(
            nap = nap,
            now = maxOf(now, System.currentTimeMillis()),
            zone = zone,
            onSave = { start, end ->
                actions.saveNap(start, end, nap.replacing)
                napEditing = null
            },
            onRemove = {
                actions.removeNap(nap.replacing)
                napEditing = null
            },
            onDismiss = { napEditing = null },
        )
    }

    val todaySessions = remember(state.todayAppUsage) { state.todayAppUsage.map(AnalyticsMappers::session) }
    val lastNight = insights?.lastNight?.takeIf { it.wakeDate == today }
    val todayNaps =
        remember(insights?.confirmedNaps, today) {
            insights?.confirmedNaps.orEmpty().filter { (s, _) -> TimeUtil.dateOf(s, zone) == today }
        }
    val ribbon =
        remember(todaySessions, insights?.typicalDay, lastNight, todayNaps, state.typicalUsage) {
            DayRibbonBuilder.build(todaySessions, insights?.typicalDay, lastNight, todayNaps, today, now, zone)
        }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        item(key = "top") { TopLine(today, nav::openSettings) }

        item(key = "hero") {
            TodayRings(state, dailyTargetMinutes, zone)
            Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                Spacer(Modifier.height(14.dp))
                ComparisonLine(state.todayScreenTimeMs, state.typicalUsage, today)
                intention?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Flag, contentDescription = null, tint = LocalDataColors.current.pickupsHue.text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        item(key = "ribbon") {
            Spacer(Modifier.height(18.dp))
            DayRibbonChart(ribbon)
        }

        state.recording.breakUntil?.takeIf { until -> state.recording.breakFrom?.let { !today.isBefore(it) } == true && !today.isAfter(until) }?.let { until ->
            item(key = "break") { BreakBanner(until, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) }
        }
        state.update?.let { update ->
            item(key = "update") {
                Spacer(Modifier.height(22.dp))
                UpdatePanel(update, state.installState, actions::downloadUpdate, actions::installUpdate, actions::skipUpdate)
            }
        }
        if (state.importStatus != null || state.showImportCard) {
            item(key = "import") {
                Spacer(Modifier.height(22.dp))
                ImportPanel(state.importStatus, state.oldAppInstalled, nav::pickImportFile, actions::dismissImport)
            }
        }
        state.welcomeBack?.let { summary ->
            item(key = "welcome-back") {
                Spacer(Modifier.height(22.dp))
                WelcomeBackPanel(summary, onDismiss = actions::dismissWelcomeBack)
            }
        }

        if (state.pendingCheckInPromptedAt != null) {
            item(key = "checkin") {
                Spacer(Modifier.height(22.dp))
                CheckInPanel(onAnswer = actions::answerCheckIn, onDismiss = actions::dismissCheckIn)
            }
        } else if (state.askMood) {
            item(key = "mood") {
                Spacer(Modifier.height(22.dp))
                MoodPanel(onDone = actions::answerMood)
            }
        }
        val askNotifications = !state.canPostNotifications && !state.notificationAskDismissed && state.daysOfData >= 2
        // One raised card at a time: a question you're answering comes first.
        val busySlot =
            state.pendingCheckInPromptedAt != null || state.askMood || askNotifications || state.welcomeBack != null || state.importStatus != null
        if (askNotifications && state.pendingCheckInPromptedAt == null && !state.askMood) {
            item(key = "notify-ask") {
                Spacer(Modifier.height(22.dp))
                NotificationAskPanel(onGranted = actions::checkPermissions, onNotNow = actions::dismissNotificationAsk)
            }
        }

        // One raised container: the most useful thing to read today.
        val nap =
            insights?.let { ins ->
                ins.naps.lastOrNull { com.habitminer.analytics.NapRecords.worthAsking(it) && it.key !in ins.answers && ins.computedAt - it.end <= 12 * TimeUtil.HOUR }
            }
        val deviation =
            insights?.deviations?.days
                ?.filter { it.date == today && it.explainedBy == null && state.deviationFeedback[it.key] != UserLabelEntity.FEEDBACK_EXPECTED }
                ?.maxByOrNull { it.score }
        val contextInsight = insights?.contextInsights?.firstOrNull()
        val rawInsight = insights?.insight
        val justAnswered = rawInsight != null && answeredInsight == rawInsight.key
        // Answered or put away earlier today: out of the way until tomorrow (and so is today's
        // difference, which it usually repeats).
        val insightDone = rawInsight != null && !justAnswered && (rawInsight.hidden || rawInsight.feedback != null)
        val insight = rawInsight?.takeUnless { insightDone }
        when {
            busySlot -> Unit
            nap != null ->
                item(key = "nap") {
                    Spacer(Modifier.height(22.dp))
                    NapQuestionCard(
                        nap,
                        onAdjust = { napEditing = com.habitminer.ui.sleep.NapToEdit(TimeUtil.dateOf(nap.start, zone), nap.start, nap.end, listOf(nap.key), fromQuestion = true) },
                    ) { asleep -> actions.answerNap(nap.key, asleep) }
                }
            // The daily mood check, at its time each day.
            state.moodDue ->
                item(key = "mood-daily") {
                    Spacer(Modifier.height(22.dp))
                    MoodPanel(daily = true, onDone = actions::answerMood)
                }
            insight != null ->
                item(key = "insight-${insight.key}") {
                    Spacer(Modifier.height(22.dp))
                    InsightPanel(
                        insight = insight,
                        showExplainer = !state.insightSettings.explainerSeen,
                        justAnswered = justAnswered,
                        onFeedback = { v ->
                            actions.insightFeedback(insight.key, v)
                            answeredInsight = if (v == null) null else insight.key
                        },
                        onHide = { actions.hideInsight(insight.key) },
                        onDismissExplainer = actions::dismissInsightExplainer,
                        onOpen = {
                            when {
                                insight.open == "sleep" -> nav.openSleep()
                                insight.open.startsWith("app:") -> nav.openApp(insight.open.removePrefix("app:"))
                                else -> nav.openChanges()
                            }
                        },
                    )
                }
            deviation != null && !insightDone ->
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
            val colors = LocalDataColors.current
            Spacer(Modifier.height(16.dp))
            TileRow {
                MetricTile(
                    icon = Icons.Rounded.DirectionsWalk,
                    label = "Steps",
                    value = if (state.stepsToday >= 0) grouped(state.stepsToday) else "–",
                    hue = colors.stepsHue,
                    caption = if (state.stepsToday < 0) (if (state.stepPermission) "from your next steps" else "needs permission") else "today",
                    modifier = Modifier.weight(1f),
                )
                val pickups = insights?.pickupsToday?.takeIf { it.total > 0 }
                MetricTile(
                    icon = Icons.Rounded.NotificationsActive,
                    label = "Notified",
                    value = pickups?.let { Format.percent(it.notificationShare) } ?: "–",
                    hue = colors.notificationsHue,
                    caption = pickups?.let { "of pickups came right after a notification" } ?: "pickups after a notification",
                    modifier = Modifier.weight(1f),
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
            item(key = "sleep-header") {
                SectionHeader("Last night", actionLabel = "Sleep", onAction = nav::openSleep, icon = Icons.Rounded.Bedtime, iconColor = LocalDataColors.current.sleepHue.start)
            }
            item(key = "sleep") {
                SleepLane(lastNight, naps = todayNaps, zone = zone)
                Spacer(Modifier.height(8.dp))
                val day = insights?.sleepToday
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = LocalDataColors.current.sleepHue.text)) {
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
                    icon = Icons.Rounded.AutoAwesome,
                    iconColor = LocalDataColors.current.freeHue.start,
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
            item(key = "apps-header") {
                SectionHeader("Apps today", actionLabel = "All apps", onAction = nav::openApps, icon = Icons.Rounded.Smartphone, iconColor = LocalDataColors.current.screenHue.start)
            }
            item(key = "apps") {
                val packages = remember(state.todayAppUsage) { state.todayAppUsage.associate { it.appName to it.packageName } }
                val categories = remember(todaySessions) { todaySessions.associate { it.appName to it.category } }
                val top = remember(state.todayUsageByApp) { state.todayUsageByApp.entries.sortedByDescending { it.value }.take(5) }
                BarList(
                    top.map { (app, ms) ->
                        BarItem(
                            app,
                            ms,
                            Format.duration(ms),
                            com.habitminer.ui.components.categoryColor(categories[app] ?: com.habitminer.analytics.AppCategory.OTHER),
                            leading = { AppIcon(packages[app], app, size = 32.dp) },
                        )
                    },
                )
            }
        }

        item(key = "around") { AroundYou(state) }
    }
}

/**
 * Three rings, outermost first: screen time against your target (or a usual day), pickups
 * against a usual day, and your longest phone-free stretch against two hours. The figures sit
 * beside them in the same colours.
 */
@Composable
private fun TodayRings(
    state: HabitUiState,
    dailyTargetMinutes: Int?,
    zone: ZoneId,
) {
    val colors = LocalDataColors.current
    val screenMs = state.todayScreenTimeMs
    val targetMs = dailyTargetMinutes?.let { it * 60_000L } ?: state.typicalUsage?.expectedFullDayMs?.takeIf { it > 0 }
    val usualPickups = state.insights?.usualPickups?.takeIf { it > 0 }
    val freeMs = state.phoneFree?.durationMs ?: 0L
    val freeGoalMs = 2 * TimeUtil.HOUR
    val rings =
        listOf(
            RingSpec(targetMs?.let { screenMs.toFloat() / it } ?: 0f, colors.screenHue),
            RingSpec(usualPickups?.let { state.todayUnlocks.toFloat() / it } ?: 0f, colors.pickupsHue),
            RingSpec(freeMs.toFloat() / freeGoalMs, colors.freeHue),
        )
    val screenCaption =
        when {
            dailyTargetMinutes != null && targetMs != null -> "of your ${Format.duration(targetMs)} target"
            targetMs != null -> "of a usual ${Format.duration(targetMs)} day"
            else -> "so far today"
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityRings(
            rings,
            size = 150.dp,
            stroke = 18.dp,
            description =
                "Screen time ${Format.duration(screenMs)}, ${state.todayUnlocks} pickups, longest phone-free stretch " +
                    (state.phoneFree?.let { Format.duration(it.durationMs) } ?: "none yet"),
        )
        Spacer(Modifier.width(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RingLegend("Screen time", Format.duration(screenMs), screenCaption, colors.screenHue)
            RingLegend("Pickups", "${state.todayUnlocks}", usualPickups?.let { "usually $it a day" } ?: "today", colors.pickupsHue)
            RingLegend(
                "Phone-free",
                state.phoneFree?.let { Format.duration(it.durationMs) } ?: "–",
                state.phoneFree?.let { "${Format.clock(it.start, zone)} to ${Format.clock(it.end, zone)}" } ?: "2h closes the ring",
                colors.freeHue,
            )
        }
    }
}

@Composable
private fun TopLine(
    today: java.time.LocalDate,
    onSettings: () -> Unit,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(today.format(fmt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        }
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
    val hue = LocalDataColors.current.screenHue
    HeroContainer(accent = hue.start) {
        com.habitminer.ui.design.Kicker("Quick check-in", hue, Icons.Rounded.TouchApp)
        Spacer(Modifier.height(6.dp))
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
                com.habitminer.ui.design.HueTonalButton(option.label, hue, {
                    haptic()
                    onAnswer(option)
                })
            }
            TextButton(onClick = onDismiss) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val hue = if (dev.kind.isMore) LocalDataColors.current.screenHue else LocalDataColors.current.freeHue
    HeroContainer(accent = hue.start) {
        com.habitminer.ui.design.Kicker("Different today", hue, if (dev.kind.isMore) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown)
        Spacer(Modifier.height(6.dp))
        Text(dev.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(dev.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        when (feedback) {
            UserLabelEntity.FEEDBACK_UNUSUAL -> Text("You marked this as unusual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.habitminer.ui.design.HueButton("Expected", hue, {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_EXPECTED)
                    })
                    com.habitminer.ui.design.HueTonalButton("Unusual", hue, {
                        haptic()
                        onFeedback(UserLabelEntity.FEEDBACK_UNUSUAL)
                    })
                    TextButton(onClick = onSeeAll) { Text("All changes", color = hue.text) }
                }
        }
    }
}

@Composable
private fun ContextInsightPanel(insight: ContextInsight) {
    val hue = LocalDataColors.current.nowHue
    HeroContainer(accent = hue.start) {
        com.habitminer.ui.design.Kicker("Worth knowing", hue, Icons.Rounded.Lightbulb)
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
        icon = Icons.Rounded.Explore,
        iconColor = LocalDataColors.current.stepsHue.start,
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
                val c = LocalDataColors.current
                when (light) {
                    Light.DARK -> AroundItem(Icons.Rounded.DarkMode, "Dark", c.sleepHue.text)
                    Light.DIM -> AroundItem(Icons.Rounded.WbTwilight, "Dim", c.stepsHue.text)
                    Light.BRIGHT -> AroundItem(Icons.Rounded.LightMode, "Bright", c.nowHue.text)
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
                    LocalDataColors.current.stepsHue.text,
                )
            }
        }
        latest?.let { l ->
            if (l.batteryLevel in 0..100) {
                AroundItem(
                    if (l.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                    "${l.batteryLevel}%" + if (l.isCharging) ", charging" else "",
                    LocalDataColors.current.goodHue.text,
                )
            }
            l.wifiPlace?.let { hash -> state.insights?.placeNames?.get(hash)?.let { AroundItem(Icons.Rounded.Place, it, LocalDataColors.current.freeHue.text) } }
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
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
