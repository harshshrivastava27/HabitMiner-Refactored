@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.DayTimelineData
import com.habitminer.analytics.Format
import com.habitminer.analytics.HeatmapData
import com.habitminer.analytics.Light
import com.habitminer.analytics.Motion
import com.habitminer.analytics.TypicalDayCurve
import java.time.format.TextStyle
import java.util.Locale

/** One labelled horizontal bar row: name, time, bar. */
@Composable
fun UsageBars(
    items: List<Triple<String, Long, Color>>,
    modifier: Modifier = Modifier,
    maxRows: Int = 5,
) {
    if (items.isEmpty()) return
    val top = items.take(maxRows)
    val max = top.maxOf { it.second }.coerceAtLeast(1L)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        top.forEach { (name, ms, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    text = Format.duration(ms),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(58.dp).padding(end = 10.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .weight(0.5f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth((ms.toFloat() / max).coerceIn(0.02f, 1f))
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(color),
                    )
                }
            }
        }
    }
}

/** A single bar comparing a value to a 0–1 scale, with label and value text. */
@Composable
fun ScoreBar(
    label: String,
    fraction: Float,
    color: Color,
    emphasized: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Format.percent(fraction),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(if (emphasized) 12.dp else 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                        .height(if (emphasized) 12.dp else 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color),
            )
        }
    }
}

/**
 * Today's cumulative screen time (solid line) against the usual range (shaded band) and
 * the usual curve (dashed). X axis is the hour of day.
 */
@Composable
fun TypicalDayChart(
    curve: TypicalDayCurve,
    modifier: Modifier = Modifier,
) {
    val todayColor = MaterialTheme.colorScheme.primary
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val medianColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val maxMinutes = maxOf(curve.high.maxOrNull() ?: 0, curve.today.maxOfOrNull { it.second } ?: 0, 60)
    val now = curve.today.lastOrNull()
    val description =
        "Today ${Format.duration((now?.second ?: 0) * 60_000L)} so far; " +
            "usually ${Format.duration((curve.median[24]) * 60_000L)} by the end of the day"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Format.duration(maxMinutes * 60_000L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = description },
        ) {
            val w = size.width
            val h = size.height
            fun x(hour: Float) = w * hour / 24f
            fun y(min: Int) = h - h * (min.toFloat() / maxMinutes)

            for (hour in listOf(6f, 12f, 18f)) {
                drawLine(gridColor, Offset(x(hour), 0f), Offset(x(hour), h), strokeWidth = 1.dp.toPx())
            }
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())

            val band = Path()
            band.moveTo(x(0f), y(curve.high[0]))
            for (hr in 1..24) band.lineTo(x(hr.toFloat()), y(curve.high[hr]))
            for (hr in 24 downTo 0) band.lineTo(x(hr.toFloat()), y(curve.low[hr]))
            band.close()
            drawPath(band, bandColor)

            val median = Path()
            median.moveTo(x(0f), y(curve.median[0]))
            for (hr in 1..24) median.lineTo(x(hr.toFloat()), y(curve.median[hr]))
            drawPath(
                median,
                medianColor,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )

            if (curve.today.size >= 2) {
                val today = Path()
                today.moveTo(x(curve.today.first().first), y(curve.today.first().second))
                curve.today.drop(1).forEach { (hr, min) -> today.lineTo(x(hr), y(min)) }
                drawPath(today, todayColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            now?.let { drawCircle(todayColor, radius = 4.dp.toPx(), center = Offset(x(it.first), y(it.second))) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(todayColor, "Today")
            LegendDot(medianColor, "Usual")
            LegendDot(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), "Usual range")
        }
    }
}

/**
 * 7 × 24 grid of minutes per hour. Tap a cell to see the hour's total and top app.
 */
@Composable
fun WeekHeatmap(
    data: HeatmapData,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val selectedOutline = MaterialTheme.colorScheme.onSurface
    var selected by remember(data) { mutableStateOf<Pair<Int, Int>?>(null) }
    val max = data.maxMinutes.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(36.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(36.dp)) {
                data.days.forEach { day ->
                    Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .height((22 * data.days.size).dp)
                        .semantics { contentDescription = "Phone use by hour for the last ${data.days.size} days" }
                        .pointerInput(data) {
                            detectTapGestures { offset ->
                                val col = (offset.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                                val row = (offset.y / (size.height / data.days.size.toFloat())).toInt().coerceIn(0, data.days.size - 1)
                                selected = if (selected == row to col) null else row to col
                            }
                        },
            ) {
                val cw = size.width / 24f
                val ch = size.height / data.days.size
                val gap = 1.5.dp.toPx()
                data.minutes.forEachIndexed { r, row ->
                    for (c in 0 until 24) {
                        val m = row[c]
                        val color = if (m == 0) empty else base.copy(alpha = 0.15f + 0.85f * (m.toFloat() / max))
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(c * cw + gap / 2, r * ch + gap / 2),
                            size = Size(cw - gap, ch - gap),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                }
                selected?.let { (r, c) ->
                    drawRoundRect(
                        color = selectedOutline,
                        topLeft = Offset(c * cw, r * ch),
                        size = Size(cw, ch),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val sel = selected
        val caption =
            if (sel != null) {
                val (r, c) = sel
                val day = data.days[r].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val minutes = data.minutes[r][c]
                val app = data.topApps[r][c]
                if (minutes == 0) {
                    "$day ${Format.hourRange(c)}: no phone use"
                } else {
                    "$day ${Format.hourRange(c)}: ${minutes}m" + (app?.let { ", mostly $it" } ?: "")
                }
            } else {
                data.busiestHour?.let { "Busiest hour this week: ${Format.hourRange(it)}. Tap a square for details." }
                    ?: "Tap a square for details."
            }
        Hint(caption)
    }
}

/**
 * The History day as a 24-hour strip: app use coloured by category on top, sleep shaded,
 * and a context lane underneath (light colour, motion ticks, charging).
 */
@Composable
fun DayTimelineStrip(
    data: DayTimelineData,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    // Sleep gets its own neutral lane so it can't be confused with an app colour or "dark".
    val sleepColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val darkColor = Color(0xFF3949AB)
    val dimColor = Color(0xFFFFB74D)
    val brightColor = Color(0xFFFFEE58)
    val movingColor = Color(0xFF66BB6A)
    val chargeColor = Color(0xFF26C6DA)
    val unknownColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .semantics { contentDescription = "Timeline of phone use, ${Format.duration(data.totalMs)} in total" },
        ) {
            val w = size.width
            fun x(minute: Int) = w * minute / (24f * 60f)
            val sleepTop = 0f
            val sleepH = 5.dp.toPx()
            val usageTop = 9.dp.toPx()
            val usageH = 22.dp.toPx()
            val ctxTop = 40.dp.toPx()
            val ctxH = 10.dp.toPx()
            val chargeTop = 54.dp.toPx()

            data.sleepBands.forEach { (s, e) ->
                drawRoundRect(sleepColor, Offset(x(s), sleepTop), Size((x(e) - x(s)).coerceAtLeast(2f), sleepH), CornerRadius(2.dp.toPx()))
            }
            drawRoundRect(track, Offset(0f, usageTop), Size(w, usageH), CornerRadius(4.dp.toPx()))
            data.segments.forEach { seg ->
                drawRect(
                    categoryColor(seg.category),
                    Offset(x(seg.startMinute), usageTop),
                    Size((x(seg.endMinute) - x(seg.startMinute)).coerceAtLeast(1.5f), usageH),
                )
            }
            for (hr in listOf(6, 12, 18)) {
                drawLine(track, Offset(x(hr * 60), 0f), Offset(x(hr * 60), size.height), strokeWidth = 1.dp.toPx())
            }

            drawRoundRect(track, Offset(0f, ctxTop), Size(w, ctxH), CornerRadius(3.dp.toPx()))
            data.marks.forEachIndexed { i, mark ->
                val next = data.marks.getOrNull(i + 1)?.minute ?: (mark.minute + 15)
                val width = (x(minOf(next, mark.minute + 30)) - x(mark.minute)).coerceAtLeast(2f)
                val c =
                    when (mark.light) {
                        Light.DARK -> darkColor
                        Light.DIM -> dimColor
                        Light.BRIGHT -> brightColor
                        null -> unknownColor
                    }
                drawRect(c, Offset(x(mark.minute), ctxTop), Size(width, ctxH))
                if (mark.motion == Motion.MOVING || mark.motion == Motion.ACTIVE) {
                    drawCircle(movingColor, radius = 2.5.dp.toPx(), center = Offset(x(mark.minute) + 2f, ctxTop - 3.dp.toPx()))
                }
                if (mark.charging) {
                    drawRect(chargeColor, Offset(x(mark.minute), chargeTop), Size(width, 3.dp.toPx()))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(sleepColor, "Sleep & naps (top line)")
            LegendDot(darkColor, "Dark")
            LegendDot(dimColor, "Dim")
            LegendDot(brightColor, "Bright")
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(movingColor, "Moving")
            LegendDot(chargeColor, "Charging")
            LegendDot(unknownColor, "Screen off (no reading)")
        }
    }
}
