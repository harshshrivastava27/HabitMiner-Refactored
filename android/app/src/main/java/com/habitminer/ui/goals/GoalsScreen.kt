@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.habitminer.ui.goals

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.AppSummary
import com.habitminer.analytics.Format
import com.habitminer.goals.Goals
import com.habitminer.ui.design.AppIcon
import com.habitminer.ui.design.HeroContainer
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.ScreenTitle
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.rememberConfirmHaptic
import com.habitminer.ui.theme.LocalDataColors
import java.time.LocalDate

@Composable
fun GoalsScreen(
    goals: Goals,
    apps: List<AppSummary>,
    dailyTotals: List<Pair<LocalDate, Long>>,
    todayMs: Long,
    onChange: ((Goals) -> Goals) -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    var picking by rememberSaveable { mutableStateOf<String?>(null) } // "less" or "more"
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val haptic = rememberConfirmHaptic()

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "title") { ScreenTitle("Goals", subtitle = "Soft targets. Nothing is blocked.") }

        item(key = "intention") {
            Spacer(Modifier.height(8.dp))
            IntentionPanel(goals.intentionFor(today)) { text ->
                haptic()
                onChange { it.copy(intention = text?.trim()?.takeIf { t -> t.isNotEmpty() }, intentionDate = today) }
            }
        }

        item(key = "target") {
            val hues = LocalDataColors.current
            SectionHeader(
                "Daily phone time",
                info = "A target for your whole day. The week below shows the days you stayed under it.",
                icon = Icons.Rounded.Timer,
                iconColor = hues.screenHue.start,
            )
            val options = listOf<Int?>(null, 120, 180, 240, 300)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                options.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = goals.dailyTargetMinutes == m,
                        onClick = { onChange { it.copy(dailyTargetMinutes = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        colors = com.habitminer.ui.design.hueSegmentedColors(hues.screenHue),
                    ) { Text(m?.let { "${it / 60}h" } ?: "None") }
                }
            }
            goals.dailyTargetMinutes?.let { target -> TargetProgress(target, todayMs, dailyTotals) }
        }

        item(key = "less") {
            SectionHeader("Use less", actionLabel = "Choose", onAction = { picking = "less" }, icon = Icons.Rounded.TrendingDown, iconColor = LocalDataColors.current.screenHue.start)
            if (goals.useLess.isEmpty()) {
                Note("Pick apps you'd like to spend less time in. You'll see how this week compares with last week.")
            } else {
                AppGoalRows(goals.useLess, byPackage, less = true, onOpenApp = onOpenApp)
            }
        }

        item(key = "reminder") {
            val remindHue = LocalDataColors.current.stepsHue
            SectionHeader(
                "Remind me",
                icon = Icons.Rounded.NotificationsActive,
                iconColor = remindHue.start,
                info = "A quiet notification after this long straight in a use-less app. It counts towards the three-a-day limit, so it never piles up.",
            )
            val options = listOf(0, 15, 30, 45)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                options.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = goals.reminderMinutes == m,
                        onClick = { onChange { it.copy(reminderMinutes = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = goals.useLess.isNotEmpty() || m == 0,
                        colors = com.habitminer.ui.design.hueSegmentedColors(remindHue),
                    ) { Text(if (m == 0) "Off" else "$m min") }
                }
            }
        }

        item(key = "more") {
            SectionHeader("Use more", actionLabel = "Choose", onAction = { picking = "more" }, icon = Icons.Rounded.TrendingUp, iconColor = LocalDataColors.current.pickupsHue.start)
            if (goals.useMore.isEmpty()) {
                Note("Pick apps you'd like to use more, like a language course or a reading app.")
            } else {
                AppGoalRows(goals.useMore, byPackage, less = false, onOpenApp = onOpenApp)
            }
        }
    }

    picking?.let { which ->
        val selected = if (which == "less") goals.useLess else goals.useMore
        AppPicker(
            title = if (which == "less") "Apps to use less" else "Apps to use more",
            apps = apps,
            selected = selected.toSet(),
            onDone = { chosen ->
                onChange { g -> if (which == "less") g.copy(useLess = chosen.toList(), useMore = g.useMore - chosen) else g.copy(useMore = chosen.toList(), useLess = g.useLess - chosen) }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun IntentionPanel(
    intention: String?,
    onSave: (String?) -> Unit,
) {
    var editing by rememberSaveable(intention) { mutableStateOf(intention == null) }
    var text by rememberSaveable(intention) { mutableStateOf(intention.orEmpty()) }
    val hue = LocalDataColors.current.pickupsHue
    HeroContainer(accent = hue.start) {
        com.habitminer.ui.design.Kicker("Today's intention", hue, Icons.Rounded.Flag)
        Spacer(Modifier.height(8.dp))
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                placeholder = { Text("Finish the lab report") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = {
                        onSave(text)
                        editing = false
                    }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            com.habitminer.ui.design.HueButton("Set for today", hue, {
                onSave(text)
                editing = false
            }, enabled = text.isNotBlank())
        } else {
            Text(intention.orEmpty(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true }) { Text("Change", color = hue.text) }
                TextButton(onClick = { onSave(null) }) { Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun TargetProgress(
    targetMinutes: Int,
    todayMs: Long,
    daily: List<Pair<LocalDate, Long>>,
) {
    val colors = LocalDataColors.current
    val targetMs = targetMinutes * 60_000L
    val week = daily.takeLast(7)
    val past = week.dropLast(1)
    val under = past.count { it.second in 1..targetMs }
    val left = targetMs - todayMs
    Row(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        com.habitminer.ui.design.ActivityRings(
            listOf(com.habitminer.ui.design.RingSpec(todayMs.toFloat() / targetMs, colors.screenHue)),
            size = 76.dp,
            stroke = 13.dp,
            description = "${Format.duration(todayMs)} of ${Format.duration(targetMs)} today",
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (left >= 0) "${Format.duration(left)} left today" else "${Format.duration(-left)} over today",
                style = MaterialTheme.typography.titleMedium,
                color = if (left >= 0) colors.screenHue.text else colors.alert,
            )
            if (past.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // One dot a day: green when under the target, pink when over, grey without data.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    past.forEach { (day, ms) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val c =
                                when {
                                    ms <= 0L -> colors.track
                                    ms <= targetMs -> colors.goodHue.start
                                    else -> colors.screenHue.start
                                }
                            Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Under target on $under of the last ${past.size} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppGoalRows(
    packages: List<String>,
    byPackage: Map<String, AppSummary>,
    less: Boolean,
    onOpenApp: (String) -> Unit,
) {
    RowGroup {
        packages.forEach { pkg ->
            val a = byPackage[pkg]
            row {
                val week = a?.weekMs ?: 0L
                val previous = a?.let { prevWeekMs(it) } ?: 0L
                val change = week - previous
                val verdict =
                    when {
                        a == null -> "Not used in the last five weeks"
                        previous == 0L -> "${Format.duration(week / 7)} a day this week"
                        kotlin.math.abs(change) < 10 * 60_000L -> "About the same as last week"
                        (change < 0) == less -> "${Format.duration(kotlin.math.abs(change))} ${if (change < 0) "less" else "more"} than last week, on track"
                        else -> "${Format.duration(kotlin.math.abs(change))} ${if (change < 0) "less" else "more"} than last week"
                    }
                ListRow(
                    a?.appName ?: pkg.substringAfterLast('.'),
                    supporting = verdict,
                    leading = { AppIcon(pkg, a?.appName ?: pkg, size = 36.dp) },
                    trailingText = Format.duration(week),
                    onClick = { onOpenApp(pkg) },
                )
            }
        }
    }
}

/** Minutes in the 7 days before this week, from the 14-day series. */
private fun prevWeekMs(a: AppSummary): Long = (a.last14.take(7).sum() * 60_000f).toLong()

@Composable
private fun AppPicker(
    title: String,
    apps: List<AppSummary>,
    selected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDone(chosen) }) { Text("Done") }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().height(480.dp)) {
                items(apps.take(60), key = { it.packageName }) { a ->
                    ListRow(
                        a.appName,
                        supporting = "${Format.duration(a.weekMs)} this week",
                        leading = { AppIcon(a.packageName, a.appName, size = 36.dp) },
                        trailing = {
                            Checkbox(checked = a.packageName in chosen, onCheckedChange = { on -> chosen = if (on) chosen + a.packageName else chosen - a.packageName })
                        },
                        onClick = { chosen = if (a.packageName in chosen) chosen - a.packageName else chosen + a.packageName },
                    )
                }
            }
        }
    }
}
