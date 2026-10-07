@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.habitminer.ui.goals

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
            SectionHeader("Daily phone time", info = "A target for your whole day. The week below shows the days you stayed under it.")
            val options = listOf<Int?>(null, 120, 180, 240, 300)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                options.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = goals.dailyTargetMinutes == m,
                        onClick = { onChange { it.copy(dailyTargetMinutes = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(m?.let { "${it / 60}h" } ?: "None") }
                }
            }
            goals.dailyTargetMinutes?.let { target -> TargetProgress(target, todayMs, dailyTotals) }
        }

        item(key = "less") {
            SectionHeader("Use less", actionLabel = "Choose", onAction = { picking = "less" })
            if (goals.useLess.isEmpty()) {
                Note("Pick apps you'd like to spend less time in. You'll see how this week compares with last week.")
            } else {
                AppGoalRows(goals.useLess, byPackage, less = true, onOpenApp = onOpenApp)
            }
        }

        item(key = "reminder") {
            SectionHeader(
                "Remind me",
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
                    ) { Text(if (m == 0) "Off" else "$m min") }
                }
            }
        }

        item(key = "more") {
            SectionHeader("Use more", actionLabel = "Choose", onAction = { picking = "more" })
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
    HeroContainer {
        Text("Today's intention", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
            FilledTonalButton(onClick = {
                onSave(text)
                editing = false
            }, enabled = text.isNotBlank()) { Text("Set for today") }
        } else {
            Text(intention.orEmpty(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true }) { Text("Change") }
                TextButton(onClick = { onSave(null) }) { Text("Clear") }
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
    val under = week.dropLast(1).count { it.second in 1..targetMs }
    Column(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
        val left = targetMs - todayMs
        Text(
            if (left >= 0) "${Format.duration(left)} left today" else "${Format.duration(-left)} over today",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.track)) {
            Box(
                Modifier.fillMaxWidth((todayMs.toFloat() / targetMs).coerceIn(0.01f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                    .background(if (left >= 0) colors.screen else colors.caution),
            )
        }
        if (week.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Under target on $under of the last ${week.size - 1} days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
