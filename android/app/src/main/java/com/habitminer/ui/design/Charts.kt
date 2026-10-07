@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.design

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.DailySleep
import com.habitminer.analytics.DayRibbon
import com.habitminer.analytics.Format
import com.habitminer.analytics.HeatmapData
import com.habitminer.analytics.SleepEstimate
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.NumberStyles
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Hour labels in the phone's 12/24-hour style. */
@Composable
fun hourAxisLabels(hours: List<Int>): List<String> {
    val context = LocalContext.current
    val is24 = remember { DateFormat.is24HourFormat(context) }
    return hours.map { h ->
        val hh = ((h % 24) + 24) % 24
        if (is24) {
            hh.toString().padStart(2, '0')
        } else {
            when {
                hh == 0 -> "12 am"
                hh < 12 -> "$hh am"
                hh == 12 -> "12 pm"
                else -> "${hh - 12} pm"
            }
        }
    }
}

@Composable
private fun AxisRow(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Today on one strip: a bar per hour of phone use, the usual amount as a dashed step line,
 * sleep as a thin band along the bottom, and a marigold mark at now. The bars grow in once,
 * left to right, the first time the screen opens.
 */
@Composable
fun DayRibbonChart(
    ribbon: DayRibbon,
    modifier: Modifier = Modifier,
    height: Dp = 92.dp,
) {
    val colors = LocalDataColors.current
    val reduced = rememberReducedMotion()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (reduced || revealed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && !revealed) progress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        revealed = true
    }
    val total = ribbon.todayMinutes.sum()
    val usualSoFar = ribbon.usualMinutes?.let { u -> (0 until 24).sumOf { h -> if (h * 60 < ribbon.nowMinute) u[h] else 0 } }
    val description =
        "Phone use by hour today: $total minutes so far" + (usualSoFar?.let { ", usually about $it by now" } ?: "") + "."
    Column(modifier = modifier.padding(horizontal = ScreenPadding)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            val w = size.width
            val sleepH = 4.dp.toPx()
            val chartH = size.height - sleepH - 6.dp.toPx()
            val slot = w / 24f
            val gap = slot * 0.18f
            val max = ribbon.maxMinutes.toFloat()
            fun y(minutes: Float) = chartH - chartH * (minutes / max)

            // Faint guides at 6, 12 and 18.
            for (h in listOf(6, 12, 18)) {
                drawLine(colors.track, Offset(h * slot, 0f), Offset(h * slot, chartH), strokeWidth = 1.dp.toPx())
            }
            drawLine(colors.track, Offset(0f, chartH), Offset(w, chartH), strokeWidth = 1.dp.toPx())

            // Bars, revealed left to right.
            val p = progress.value
            for (h in 0 until 24) {
                val m = ribbon.todayMinutes[h]
                if (m <= 0) continue
                val local = ((p * 24f) - h * 0.5f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val top = y(m * local)
                drawRoundRect(
                    color = colors.screen,
                    topLeft = Offset(h * slot + gap / 2, top),
                    size = Size(slot - gap, chartH - top),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }

            // Usual, as a dashed step line.
            ribbon.usualMinutes?.let { usual ->
                val path = Path()
                for (h in 0 until 24) {
                    val yy = y(usual[h].toFloat())
                    if (h == 0) path.moveTo(0f, yy) else path.lineTo(h * slot, yy)
                    path.lineTo((h + 1) * slot, yy)
                }
                drawPath(
                    path,
                    colors.usual.copy(alpha = 0.75f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            }

            // Sleep band along the bottom.
            val bandTop = size.height - sleepH
            ribbon.sleep.forEach { (s, e) ->
                drawRoundRect(
                    colors.sleep,
                    Offset(w * s / 1440f, bandTop),
                    Size((w * (e - s) / 1440f).coerceAtLeast(2f), sleepH),
                    CornerRadius(sleepH / 2),
                )
            }

            // Now.
            val nx = w * ribbon.nowMinute / 1440f
            drawLine(colors.now, Offset(nx, 4.dp.toPx()), Offset(nx, chartH), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(colors.now, radius = 4.dp.toPx(), center = Offset(nx, 4.dp.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        AxisRow(hourAxisLabels(listOf(0, 6, 12, 18, 24)))
    }
}

/**
 * One night as a lane from evening to late morning: asleep in indigo, brief wake-ups as gaps,
 * the hour before sleep (phone use) as a faint lead-in.
 */
@Composable
fun SleepLane(
    night: SleepEstimate,
    modifier: Modifier = Modifier,
    naps: List<Pair<Long, Long>> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colors = LocalDataColors.current
    // Window: an hour before sleep to an hour after waking, widened to local 3-hour marks.
    val hour = java.time.temporal.ChronoUnit.HOURS
    val startLocal =
        java.time.Instant.ofEpochMilli(night.sleepStart - 3_600_000L).atZone(zone).truncatedTo(hour).let { it.minusHours((it.hour % 3).toLong()) }
    val endLocal =
        java.time.Instant.ofEpochMilli(night.wakeTime + 3_600_000L).atZone(zone).truncatedTo(hour).let {
            if (it.hour % 3 == 0) it else it.plusHours((3 - it.hour % 3).toLong())
        }
    val windowStart = startLocal.toInstant().toEpochMilli()
    val windowEnd = endLocal.toInstant().toEpochMilli()
    val span = (windowEnd - windowStart).toFloat().coerceAtLeast(1f)
    val marks = generateSequence(startLocal) { it.plusHours(3) }.takeWhile { !it.isAfter(endLocal) }.map { it.hour }.toList()
    val description =
        "Asleep from ${Format.clock(night.sleepStart, zone)} to ${Format.clock(night.wakeTime, zone)}, ${Format.duration(night.durationMs)}" +
            if (night.briefWakes.isNotEmpty()) ", woke briefly ${night.briefWakes.size} times" else ""
    Column(modifier = modifier.padding(horizontal = ScreenPadding)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description }) {
            val w = size.width
            fun x(t: Long) = w * ((t - windowStart) / span).coerceIn(0f, 1f)
            drawRoundRect(colors.track, Offset.Zero, size, CornerRadius(6.dp.toPx()))
            drawRoundRect(
                colors.sleep,
                Offset(x(night.sleepStart), 0f),
                Size(x(night.wakeTime) - x(night.sleepStart), size.height),
                CornerRadius(5.dp.toPx()),
            )
            night.briefWakes.forEach { wk ->
                val gx = x(wk.start)
                drawRect(colors.track, Offset(gx, 0f), Size((x(wk.end) - gx).coerceAtLeast(2.dp.toPx()), size.height))
            }
            naps.forEach { (s, e) ->
                if (e > windowStart && s < windowEnd) {
                    drawRoundRect(colors.nap, Offset(x(s), 0f), Size(x(e) - x(s), size.height), CornerRadius(5.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AxisRow(hourAxisLabels(marks))
    }
}

/**
 * Horizontal bars with a name, a value and an optional icon, longest first.
 */
@Composable
fun BarList(
    items: List<BarItem>,
    modifier: Modifier = Modifier,
    max: Long = items.maxOfOrNull { it.value } ?: 1L,
) {
    Column(modifier = modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.leading?.let {
                    it()
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(item.valueText, style = NumberStyles.small, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(LocalDataColors.current.track)) {
                        Box(
                            Modifier
                                .fillMaxWidth((item.value.toFloat() / max.coerceAtLeast(1L)).coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(item.color),
                        )
                    }
                }
            }
        }
    }
}

data class BarItem(
    val label: String,
    val value: Long,
    val valueText: String,
    val color: Color,
    val leading: (@Composable () -> Unit)? = null,
)

/** Seven days of sleep as columns: night in indigo, naps stacked on top. */
@Composable
fun SleepWeekColumns(
    days: List<DailySleep>,
    modifier: Modifier = Modifier,
    usualMs: Long? = null,
) {
    val colors = LocalDataColors.current
    val scale = maxOf(10 * 3_600_000L, days.maxOfOrNull { it.totalMs } ?: 0L).toFloat()
    val description = days.joinToString("; ") { "${it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${Format.duration(it.totalMs)}" }
    Column(modifier = modifier.padding(horizontal = ScreenPadding)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = "Sleep per day: $description" }) {
            val n = days.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.46f
            val h = size.height
            usualMs?.let { u ->
                val yy = h - h * (u / scale)
                drawLine(colors.usual.copy(alpha = 0.6f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            days.forEachIndexed { i, d ->
                val left = i * slot + (slot - barW) / 2
                drawRoundRect(colors.track, Offset(left, 0f), Size(barW, h), CornerRadius(4.dp.toPx()))
                val nightH = h * (d.nightMs / scale)
                val napH = h * (d.napMs / scale)
                if (nightH > 0f) drawRoundRect(colors.sleep, Offset(left, h - nightH), Size(barW, nightH), CornerRadius(4.dp.toPx()))
                if (napH > 0f) drawRoundRect(colors.nap, Offset(left, h - nightH - napH - 2.dp.toPx()), Size(barW, napH), CornerRadius(4.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            days.forEach { d ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (d.totalMs > 0) Format.duration(d.totalMs) else "–",
                        style = NumberStyles.small.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 7 × 24 grid of minutes per hour; tap a square for its total and top app. */
@Composable
fun UseHeatmap(
    data: HeatmapData,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDataColors.current
    var selected by remember(data) { mutableStateOf<Pair<Int, Int>?>(null) }
    val max = data.maxMinutes.coerceAtLeast(1)
    val outline = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.padding(horizontal = ScreenPadding)) {
        Row {
            Column(modifier = Modifier.width(34.dp)) {
                data.days.forEach { day ->
                    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .height((20 * data.days.size).dp)
                        .semantics { contentDescription = "Phone use by hour for the last ${data.days.size} days" }
                        .pointerInput(data) {
                            detectTapGestures { o ->
                                val c = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                                val r = (o.y / (size.height / data.days.size.toFloat())).toInt().coerceIn(0, data.days.size - 1)
                                selected = if (selected == r to c) null else r to c
                            }
                        },
            ) {
                val cw = size.width / 24f
                val ch = size.height / data.days.size
                val g = 1.5.dp.toPx()
                data.minutes.forEachIndexed { r, row ->
                    for (c in 0 until 24) {
                        val m = row[c]
                        val col = if (m == 0) colors.track else colors.screen.copy(alpha = 0.18f + 0.82f * (m.toFloat() / max))
                        drawRoundRect(col, Offset(c * cw + g / 2, r * ch + g / 2), Size(cw - g, ch - g), CornerRadius(3.dp.toPx()))
                    }
                }
                selected?.let { (r, c) ->
                    drawRoundRect(outline, Offset(c * cw, r * ch), Size(cw, ch), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.width(34.dp))
            AxisRow(hourAxisLabels(listOf(0, 6, 12, 18, 24)), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val sel = selected
        val caption =
            if (sel != null) {
                val (r, c) = sel
                val day = data.days[r].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val m = data.minutes[r][c]
                if (m == 0) "$day, ${Format.hourRange(c)}: no phone use" else "$day, ${Format.hourRange(c)}: ${m} min" + (data.topApps[r][c]?.let { ", mostly $it" } ?: "")
            } else {
                data.busiestHour?.let { "Busiest hour this week: ${Format.hourRange(it)}. Tap a square for details." } ?: "Tap a square for details."
            }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A fraction as a labelled bar (for accuracy comparisons). */
@Composable
fun ScoreLine(
    label: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Column(modifier = modifier.padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        Row {
            Text(
                label,
                style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(Format.percent(fraction), style = NumberStyles.small, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(if (emphasized) 10.dp else 6.dp).clip(RoundedCornerShape(5.dp)).background(LocalDataColors.current.track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}

/** A small line of values, e.g. minutes per day for two weeks. */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    if (values.size < 2) return
    Canvas(modifier = modifier.height(height)) {
        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * step
            val py = size.height - size.height * (v / max) * 0.9f - 2f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val lastX = (values.size - 1) * step
        val lastY = size.height - size.height * (values.last() / max) * 0.9f - 2f
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}
