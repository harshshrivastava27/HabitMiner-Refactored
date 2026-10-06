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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.CheckInOption
import com.habitminer.analytics.ContextInsight
import com.habitminer.analytics.Format
import com.habitminer.analytics.InsightKind
import com.habitminer.analytics.PatternGroup
import com.habitminer.analytics.PickupStats
import com.habitminer.analytics.SleepEstimate
import com.habitminer.analytics.TimeUtil
import com.habitminer.analytics.SleepSummary
import com.habitminer.data.UserLabelEntity
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.HabitActions
import com.habitminer.engine.TypicalUsageCalculator
import com.habitminer.ui.components.BodyText
import com.habitminer.ui.components.CardHeader
import com.habitminer.ui.components.Hint
import com.habitminer.ui.components.Pill
import com.habitminer.ui.components.SectionTitle
import com.habitminer.ui.components.StatBlock
import com.habitminer.ui.components.SurfaceCard
import com.habitminer.ui.components.TypicalDayChart
import com.habitminer.ui.components.UsageBars
import com.habitminer.ui.components.categoryColor
import com.habitminer.ui.theme.StatusSuccess
import com.habitminer.ui.theme.StatusWarning

@Composable
fun HomeScreen(
    state: HabitUiState,
    viewModel: HabitActions,
    onOpenInsights: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.hasUsagePermission || !state.hasRuntimePermissions || !state.hasNotificationPermission) {
            PermissionScreen(
                hasUsage = state.hasUsagePermission,
                hasRuntime = state.hasRuntimePermissions,
                hasNotification = state.hasNotificationPermission,
                onRequestUsage = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                onRuntimePermissionsGranted = { viewModel.checkPermissions() },
                onRequestNotification = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )
            return@Column
        }

        if (state.daysOfData == 0 && state.todayScreenTimeMs == 0L && state.discoveredHabits.isEmpty()) {
            FirstRunExperience(state, viewModel)
            return@Column
        }

        LearningStatusHeader(state)

        if (state.pendingCheckInPromptedAt != null) {
            CheckInCard(onAnswer = viewModel::answerCheckIn, onDismiss = viewModel::dismissCheckIn)
        }

        TodayNapQuestion(state, viewModel)

        TodayUsageCard(state)

        val insights = state.insights
        insights?.lastNight?.let { SleepCard(it, insights.sleepSummary, insights.confirmedNaps) }

        PickupsCard(state.todayUnlocks, insights?.pickupsToday)

        TodayRoutineShift(state, viewModel)

        TodayDeviationCard(state, viewModel)

        insights?.contextInsights?.take(2)?.forEach { ContextInsightCard(it) }

        insights?.patternGroups?.takeIf { it.isNotEmpty() }?.let { groups ->
            PatternsPreview(groups, onOpenInsights)
        }

        ContextNowCard(state)

        LikelyNextCard(state)

        if (state.isSyncing) {
            Text(
                text = "Syncing your on-device data…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------------------
// Header and first run
// ---------------------------------------------------------------------------------------

@Composable
fun FirstRunExperience(
    state: HabitUiState,
    viewModel: HabitActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Welcome to HabitMiner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "HabitMiner learns how your phone use changes through the day, using only data that stays on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "Keep using your phone normally. Your first insights appear after a day, and they get better over a week.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        if (state.isSyncing) {
            Text("Syncing data…", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { viewModel.loadHistoricalData() }) { Text("Sync usage now") }
        }
    }
}

@Composable
fun LearningStatusHeader(state: HabitUiState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.isMonitoringServiceActive) "Monitoring" else "Monitoring (background sync)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        val days = state.daysOfData
        Text(
            text =
                when {
                    days >= 7 -> "Today"
                    days > 0 -> "Today · learning your routine (day $days of 7)"
                    else -> "Today"
                },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Check-in
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckInCard(
    onAnswer: (CheckInOption) -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceCard {
        CardHeader("Quick check-in", Icons.Default.QuestionAnswer)
        Spacer(modifier = Modifier.height(8.dp))
        BodyText("What are you doing right now? Your answer helps HabitMiner check its guesses. It stays on this phone.")
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckInOption.entries.forEach { option ->
                AssistChip(onClick = { onAnswer(option) }, label = { Text("${option.emoji}  ${option.label}") })
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Skip") }
    }
}

// ---------------------------------------------------------------------------------------
// Today's usage
// ---------------------------------------------------------------------------------------

@Composable
fun TodayUsageCard(state: HabitUiState) {
    var mode by remember { mutableIntStateOf(0) } // 0 = apps, 1 = categories
    SurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("Screen time", Format.duration(state.todayScreenTimeMs), large = true)
            StatBlock("Unlocks", "${state.todayUnlocks}", large = true, alignEnd = true)
        }
        Spacer(modifier = Modifier.height(14.dp))
        TypicalComparison(screenTimeMs = state.todayScreenTimeMs, typical = state.typicalUsage)

        state.insights?.typicalDay?.let { curve ->
            Spacer(modifier = Modifier.height(14.dp))
            TypicalDayChart(curve)
        }

        Spacer(modifier = Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("Apps", "Categories").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = mode == index,
                    onClick = { mode = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) { Text(label) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (mode == 0) {
            val categoryByApp = remember(state.todayAppUsage) { state.todayAppUsage.associate { it.appName to Labels.category(it) } }
            val items =
                state.todayUsageByApp.entries.sortedByDescending { it.value }.map { (app, ms) ->
                    Triple(app, ms, categoryByApp[app]?.let { categoryColor(it) } ?: MaterialTheme.colorScheme.primary)
                }
            UsageBars(items)
        } else {
            val items = state.insights?.todayByCategory.orEmpty().map { (cat, ms) -> Triple(cat.label, ms, categoryColor(cat)) }
            if (items.isEmpty()) Hint("Categories appear after the first analysis (within a minute).") else UsageBars(items, maxRows = 6)
        }
    }
}

@Composable
private fun TypicalComparison(
    screenTimeMs: Long,
    typical: TypicalUsageCalculator.TypicalUsage?,
) {
    if (typical == null) {
        StatBlock("Usual by now", "Needs one full day of history")
        return
    }
    val expected = typical.expectedByNowMs
    val diffMs = screenTimeMs - expected
    val tolerance = maxOf(expected / 10, 10 * 60_000L)
    val (diffText, diffColor) =
        when {
            diffMs > tolerance -> "${Format.duration(diffMs)} more" to StatusWarning
            diffMs < -tolerance -> "${Format.duration(-diffMs)} less" to StatusSuccess
            else -> "About usual" to MaterialTheme.colorScheme.onSurface
        }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        StatBlock("Usual by now", Format.duration(expected))
        StatBlock("Compared with usual", diffText, valueColor = diffColor, alignEnd = true)
    }
    val dayWord = if (typical.daysUsed == 1) "day" else "days"
    val basis =
        when (typical.basis) {
            TypicalUsageCalculator.Basis.SAME_DAY_TYPE -> "your last ${typical.daysUsed} ${typical.dayType.lowercase()} $dayWord"
            TypicalUsageCalculator.Basis.ALL_DAYS -> "your last ${typical.daysUsed} $dayWord"
        }
    Spacer(modifier = Modifier.height(4.dp))
    Hint("Usually ${Format.duration(typical.expectedFullDayMs)} by the end of the day, based on $basis.")
}

// ---------------------------------------------------------------------------------------
// Sleep, pickups, deviations, insights
// ---------------------------------------------------------------------------------------

@Composable
fun SleepCard(
    night: SleepEstimate,
    summary: SleepSummary?,
    confirmedNaps: List<Pair<Long, Long>> = emptyList(),
) {
    val zone = java.time.ZoneId.systemDefault()
    SurfaceCard {
        CardHeader("Last night", Icons.Default.Bedtime, tint = Color(0xFF7986CB), trailing = "${night.confidence.label} confidence")
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("Asleep (est.)", "${Format.clock(night.sleepStart, zone)} → ${Format.clock(night.wakeTime, zone)}")
            StatBlock("Duration", Format.duration(night.durationMs), alignEnd = true)
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (night.briefWakes.isNotEmpty()) {
            val times =
                night.briefWakes.joinToString(", ") { w -> Format.clock(w.start, zone) + if (w.alarm) " (alarm)" else "" }
            BodyText("Woke briefly ${night.briefWakes.size}× ($times) and went back to sleep.")
        }
        val dayStart = TimeUtil.startOfDay(night.wakeDate, zone)
        confirmedNaps.filter { it.first >= dayStart }.forEach { (start, end) ->
            BodyText("Nap: ${Format.clock(start, zone)}–${Format.clock(end, zone)} (${Format.duration(end - start)}).")
        }
        if (night.preSleepUseMs > 0) {
            val dark = night.preSleepDarkShare?.let { ", ${Format.percent(it)} of it in the dark" } ?: ""
            BodyText("Phone use in the hour before sleep: ${Format.duration(night.preSleepUseMs)}$dark.")
        }
        val apps =
            listOfNotNull(
                night.lastAppBeforeSleep?.let { "Last app: $it" },
                night.firstAppAfterWake?.let { "first app after waking: $it" },
            ).joinToString(" · ")
        if (apps.isNotEmpty()) BodyText(apps.replaceFirstChar { it.uppercase() })
        summary?.takeIf { it.nights >= 2 }?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Hint(
                "${it.nights}-night average: ${Format.duration(it.avgDurationMs)}, usually asleep around " +
                    "${Format.clockFromMinutes(it.avgBedtimeMinutes)} and up around ${Format.clockFromMinutes(it.avgWakeMinutes)}.",
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Hint(
            "Estimated from when your screen was off overnight. A quick check or an alarm doesn't end the night " +
                "unless the step counter sees you get up. Charging and darkness raise the confidence.",
        )
    }
}

@Composable
fun PickupsCard(
    unlocks: Int,
    stats: PickupStats?,
) {
    if (unlocks == 0 && (stats == null || stats.total == 0)) return
    SurfaceCard {
        CardHeader("What makes you pick up your phone", Icons.Default.Notifications, tint = Color(0xFFFFB74D))
        Spacer(modifier = Modifier.height(10.dp))
        if (stats == null || stats.total == 0) {
            BodyText("$unlocks unlocks today. A breakdown appears after the next analysis.")
            return@SurfaceCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("After a notification", Format.percent(stats.notificationShare), caption = "${stats.afterNotification} of ${stats.total}")
            StatBlock("On your own", "${stats.selfInitiated}", alignEnd = true, caption = "${stats.quickChecks} quick checks (<30s)")
        }
        stats.topTriggers.firstOrNull()?.takeIf { it.count >= 2 }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            BodyText("Most pickups after a notification came from ${it.appName} (${it.count}).")
        }
        stats.topFirstApps.firstOrNull()?.takeIf { it.count >= 2 }?.let {
            BodyText("The app you open first most often: ${it.appName}.")
        }
    }
}

/** Today's most notable difference from usual, unless you already said it was expected. */
@Composable
fun TodayDeviationCard(
    state: HabitUiState,
    viewModel: HabitActions,
) {
    val insights = state.insights ?: return
    val today = TimeUtil.dateOf(insights.computedAt, java.time.ZoneId.systemDefault())
    val dev =
        insights.deviations.days
            .filter { it.date == today && it.explainedBy == null && state.deviationFeedback[it.key] != UserLabelEntity.FEEDBACK_EXPECTED }
            .maxByOrNull { it.score } ?: return
    DeviationCard(dev, state.deviationFeedback[dev.key], showDay = false) { value -> viewModel.giveDeviationFeedback(dev.key, value) }
}

/** A likely nap from today or yesterday that you haven't answered yet. */
@Composable
fun TodayNapQuestion(
    state: HabitUiState,
    viewModel: HabitActions,
) {
    val insights = state.insights ?: return
    val now = insights.computedAt
    val nap =
        insights.naps.lastOrNull {
            it.confidence != com.habitminer.analytics.Confidence.LOW &&
                it.key !in insights.answers &&
                now - it.end <= 12 * TimeUtil.HOUR
        } ?: return
    NapQuestionCard(nap) { asleep -> viewModel.answerNap(nap.key, asleep) }
}

/** An unanswered routine change ("since Sun 4 Oct, 33% less…"). */
@Composable
fun TodayRoutineShift(
    state: HabitUiState,
    viewModel: HabitActions,
) {
    val insights = state.insights ?: return
    val shift = insights.deviations.shift ?: return
    if (shift.label != null || shift.key in insights.answers) return
    RoutineShiftCard(shift, null, compact = true) { value -> viewModel.labelPeriod(shift.key, shift.since, value) }
}

@Composable
fun ContextInsightCard(insight: ContextInsight) {
    val tint =
        when (insight.kind) {
            InsightKind.DARK -> Color(0xFF7986CB)
            InsightKind.MOVING -> Color(0xFF66BB6A)
            InsightKind.CHARGING -> Color(0xFF26C6DA)
            InsightKind.LATE_NIGHT -> Color(0xFFBA68C8)
            InsightKind.PLACE -> Color(0xFFFFB74D)
        }
    SurfaceCard {
        CardHeader(insight.headline, Icons.Default.Lightbulb, tint = tint)
        Spacer(modifier = Modifier.height(6.dp))
        Hint(insight.detail)
    }
}

@Composable
fun PatternsPreview(
    groups: List<PatternGroup>,
    onOpenInsights: () -> Unit,
) {
    SurfaceCard {
        CardHeader("Your routines", Icons.Default.Repeat, trailing = "${groups.size} found")
        Spacer(modifier = Modifier.height(8.dp))
        groups.take(3).forEach { g ->
            PatternRowView(g)
            Spacer(modifier = Modifier.height(8.dp))
        }
        TextButton(onClick = onOpenInsights, modifier = Modifier.align(Alignment.End)) { Text("See all routines") }
    }
}

@Composable
fun PatternRowView(g: PatternGroup) {
    Column {
        Text(
            text = g.sequence,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Hint("${g.whenText} · ${g.evidenceText}")
    }
}

// ---------------------------------------------------------------------------------------
// Context now and next app
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextNowCard(state: HabitUiState) {
    val sensors = state.latestSensorContext
    val latest = state.latestContext
    SurfaceCard {
        CardHeader("Around you", Icons.Default.Sensors, trailing = sensors?.let { "sensors ${Labels.age(it.timestamp)}" })
        Spacer(modifier = Modifier.height(10.dp))
        if (sensors == null && latest == null) {
            Hint("No readings yet. Light and motion are read when you unlock your phone or open HabitMiner.")
            return@SurfaceCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sensors?.let { s ->
                if (s.lightLux >= 0f) {
                    val light =
                        when {
                            s.lightLux <= 10f -> "🌑 Dark"
                            s.lightLux <= 100f -> "🌙 Dim"
                            else -> "☀️ Bright"
                        }
                    ContextChip(light)
                }
                Labels.motion(s)?.let { ContextChip(Labels.motionChip(it)) }
                // Proximity isn't shown: readings are taken with the screen on, when the sensor
                // is almost always uncovered, so it never told you anything.
            }
            when {
                state.stepsToday >= 0 -> ContextChip("👣 ${"%,d".format(state.stepsToday)} steps today")
                state.stepSensorAvailable && state.stepPermission -> ContextChip("👣 Counting starts with your next steps")
                else -> Unit
            }
            latest?.let { l ->
                if (l.batteryLevel in 0..100) ContextChip("🔋 ${l.batteryLevel}%" + if (l.isCharging) " · charging" else "")
                l.wifiPlace?.let { hash -> state.insights?.placeNames?.get(hash)?.let { ContextChip("📍 $it") } }
            }
        }
        if (sensors == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Hint("Light and motion are read while the screen is on, so they appear after you next unlock your phone.")
        }
    }
}

/** Display-only tag (not a button, so it doesn't look tappable). */
@Composable
private fun ContextChip(text: String) {
    Box(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun LikelyNextCard(state: HabitUiState) {
    val predictions = state.predictions
    if (predictions.isEmpty()) return
    SurfaceCard {
        CardHeader("Likely next app", Icons.Default.Insights, trailing = state.predictionsAfter?.let { "after $it" })
        Spacer(modifier = Modifier.height(8.dp))
        predictions.take(3).forEach { p ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BodyText(p.appName)
                Pill(Format.percent(p.share))
            }
        }
        state.insights?.recentGuesses?.firstOrNull()?.let { g ->
            Spacer(modifier = Modifier.height(8.dp))
            Hint("Its last guess:")
            RecentGuessesList(listOf(g), max = 1)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Hint(
            "Learns from your app switches as you go, weighting recent days more. " +
                "Insights → Routines shows how often it's right.",
        )
    }
}
