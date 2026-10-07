@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.habitminer.ui.today

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.habitminer.repository.TodayInsight
import com.habitminer.ui.design.HeroContainer
import com.habitminer.ui.design.rememberConfirmHaptic

/**
 * The insight of the day: what stood out, why it's shown, and whether you'd like more like it.
 * The first time, a line explains that most days have none.
 */
@Composable
fun InsightPanel(
    insight: TodayInsight,
    showExplainer: Boolean,
    onFeedback: (String?) -> Unit,
    onDismissExplainer: () -> Unit,
    onOpen: () -> Unit,
) {
    val haptic = rememberConfirmHaptic()
    HeroContainer(modifier = Modifier.clickable(onClickLabel = "See details", onClick = onOpen)) {
        Text("Insight of the day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(insight.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Why this: ${insight.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when (insight.feedback) {
            null ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        haptic()
                        onFeedback("useful")
                    }) { Text("Useful") }
                    OutlinedButton(onClick = {
                        haptic()
                        onFeedback("fewer")
                    }) { Text("Fewer like this") }
                }
            else ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (insight.feedback == "useful") "Noted: more like this." else "Noted: fewer about ${insight.family.label.lowercase()}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onFeedback(null) }) { Text("Undo") }
                }
        }
        AnimatedVisibility(showExplainer) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Insights compare a day with your usual days. On ordinary days nothing stands out, and then there's no insight: " +
                        "that's on purpose, not a fault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismissExplainer, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Got it") }
            }
        }
    }
}

/** After a check-in: how do you feel? Two optional 1–5 scales. */
@Composable
fun MoodPanel(onDone: (mood: Int?, energy: Int?) -> Unit) {
    var mood by remember { mutableStateOf<Int?>(null) }
    var energy by remember { mutableStateOf<Int?>(null) }
    val haptic = rememberConfirmHaptic()
    HeroContainer {
        Text("Thanks. How do you feel?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Optional. Over time this shows how your mood moves with your phone use, as patterns, not causes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ScaleRow("Mood", "Low", "Great", mood) {
            haptic()
            mood = it
        }
        Spacer(Modifier.height(10.dp))
        ScaleRow("Energy", "Drained", "Full", energy) {
            haptic()
            energy = it
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDone(mood, energy) }, enabled = mood != null || energy != null) { Text("Save") }
            TextButton(onClick = { onDone(null, null) }) { Text("Skip") }
        }
    }
}

@Composable
private fun ScaleRow(
    label: String,
    low: String,
    high: String,
    value: Int?,
    onPick: (Int) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                (1..5).forEach { n ->
                    val on = value == n
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onPick(n) }
                            .semantics {
                                contentDescription = "$label $n of 5"
                                selected = on
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(start = 8.dp))
        }
    }
}

/**
 * Asked in context after the first full day: notifications are off by default on Android 13+,
 * and the reason to allow them is clearer once there's something to tell.
 */
@Composable
fun NotificationAskPanel(
    onGranted: () -> Unit,
    onNotNow: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onGranted() }
    HeroContainer {
        Text("Want a heads-up when something stands out?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "At most three a day, only at good moments: a quick question, an unusual day, or a reminder you set. You can tune each kind later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onGranted()
            }) { Text("Allow notifications") }
            TextButton(onClick = onNotNow) { Text("Not now") }
        }
    }
}

/** After a break: how it went, gently. */
@Composable
fun WelcomeBackPanel(
    summary: com.habitminer.analytics.BreakSummary,
    onDismiss: () -> Unit,
) {
    HeroContainer {
        Text("Welcome back", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(summary.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Over ${summary.days} days: ${com.habitminer.analytics.Format.duration(summary.avgPerDayMs)} a day and about ${summary.pickupsPerDay} pickups a day" +
                (summary.usualPickupsPerDay?.let { " (usually $it)" } ?: "") + ". Those days stay out of your usual pattern.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onDismiss) { Text("Thanks") }
    }
}

/** A quiet line while on a break. */
@Composable
fun BreakBanner(
    until: java.time.LocalDate,
    modifier: Modifier = Modifier,
) {
    Text(
        "On a break until ${until.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))}: " +
            "no notifications, and these days don't count as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
