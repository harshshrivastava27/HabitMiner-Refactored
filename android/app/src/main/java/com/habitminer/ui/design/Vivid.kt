@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.habitminer.ui.theme.Hue
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.LocalIsDark
import com.habitminer.ui.theme.NumberStyles
import kotlin.math.cos
import kotlin.math.sin

/** One ring: how full it is (1 = closed; more wraps round again) and its colour. */
data class RingSpec(
    val progress: Float,
    val hue: Hue,
)

/**
 * Concentric rings, outermost first, in the style of a fitness watch: a gradient that brightens
 * as it goes round, round ends, and a second lap with a shadowed tip when a ring passes 100%.
 * They sweep in once, the first time the screen opens, unless animations are off.
 */
@Composable
fun ActivityRings(
    rings: List<RingSpec>,
    modifier: Modifier = Modifier,
    size: Dp = 156.dp,
    stroke: Dp = 19.dp,
    gap: Dp = 3.dp,
    description: String = "",
) {
    val colors = LocalDataColors.current
    val reduced = rememberReducedMotion()
    var shown by rememberSaveable { mutableStateOf(false) }
    val sweep = remember { Animatable(if (reduced || shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && !shown) sweep.animateTo(1f, tween(durationMillis = 1100, easing = FastOutSlowInEasing))
        shown = true
    }
    Canvas(modifier = modifier.size(size).semantics { contentDescription = description }) {
        val w = stroke.toPx()
        val g = gap.toPx()
        rings.forEachIndexed { i, ring ->
            val radius = this.size.minDimension / 2 - w / 2 - i * (w + g)
            if (radius <= w / 2) return@forEachIndexed
            drawRing(ring.hue, (ring.progress * sweep.value).coerceAtLeast(0f), radius, w, colors.trackAlpha)
        }
    }
}

private fun DrawScope.drawRing(
    hue: Hue,
    progress: Float,
    radius: Float,
    width: Float,
    trackAlpha: Float,
) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    drawCircle(hue.start.copy(alpha = trackAlpha), radius = radius, center = center, style = Stroke(width))
    if (progress <= 0.001f) return
    val firstLap = progress.coerceAtMost(1f)
    // Sweep gradients start at 3 o'clock, so draw rotated a quarter turn back.
    rotate(-90f, center) {
        val stops =
            arrayOf(
                0f to hue.start,
                firstLap to hue.end,
                1f to hue.end,
            )
        drawArc(
            brush = Brush.sweepGradient(*stops, center = center),
            startAngle = 0f,
            sweepAngle = 360f * firstLap,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width, cap = StrokeCap.Round),
        )
        // The round start cap would otherwise pick up the end colour from the wrap-around.
        drawCircle(hue.start, radius = width / 2, center = Offset(center.x + radius, center.y))
        if (progress > 1f) {
            val extra = ((progress - 1f) % 1f).let { if (it == 0f) 1f else it }.coerceAtLeast(0.02f)
            val tipAngle = Math.toRadians(360.0 * extra)
            val tip = Offset(center.x + radius * cos(tipAngle).toFloat(), center.y + radius * sin(tipAngle).toFloat())
            // A soft shadow under the tip, so the second lap reads as lying on top of the first.
            val shadowAngle = tipAngle + 0.06
            val shadowAt = Offset(center.x + radius * cos(shadowAngle).toFloat(), center.y + radius * sin(shadowAngle).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = width * 0.55f, center = shadowAt)
            drawArc(
                color = hue.end,
                startAngle = 0f,
                sweepAngle = 360f * extra,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round),
            )
            drawCircle(hue.end, radius = width / 2, center = tip)
        }
    }
}

/** A ring's legend: coloured label, the figure, and what closes the ring. */
@Composable
fun RingLegend(
    label: String,
    value: String,
    caption: String?,
    hue: Hue,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = hue.text)
        Text(value, style = NumberStyles.stat.copy(fontSize = 24.sp, lineHeight = 28.sp), color = hue.text)
        caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A small rounded square in a vivid colour with a white glyph, the way a settings list marks
 * what each row is about.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val top = lerp(color, Color.White, 0.18f)
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.3f))
                .background(Brush.verticalGradient(listOf(top, color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * A card with one figure: a coloured icon and label on top, the number below in the same hue
 * family, and a short line of context.
 */
@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    hue: Hue,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = hue.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = hue.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = NumberStyles.stat, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        caption?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tiles side by side at the screen margin, equal widths. */
@Composable
fun TileRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A thick progress bar with a gradient fill and round ends, on a track of the same hue. */
@Composable
fun HueBar(
    fraction: Float,
    hue: Hue,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    val colors = LocalDataColors.current
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(hue.start.copy(alpha = colors.trackAlpha), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val w = (size.width * f).coerceAtLeast(size.height)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(hue.start, hue.end), startX = 0f, endX = w),
            size = Size(w, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
        )
    }
}

/** A colour for a name that has no icon: stable for the name, from the vivid set. */
@Composable
fun nameColor(name: String): Color {
    val dark = LocalIsDark.current
    val palette =
        if (dark) {
            listOf(0xFFFF3D71, 0xFF3FC8FF, 0xFFFF8E1F, 0xFFC968FF, 0xFF43DE7C, 0xFF8C78FF, 0xFFFFC300, 0xFF00D1B2)
        } else {
            listOf(0xFFE3104A, 0xFF0A8FD0, 0xFFEA6A00, 0xFF9A2BE8, 0xFF16A34A, 0xFF5B44F0, 0xFFD99A00, 0xFF00A890)
        }
    val i = (name.lowercase().hashCode() and 0x7fffffff) % palette.size
    return Color(palette[i])
}

/** A filled button in a measure's colour (white text), for the main answer on a card. */
@Composable
fun HueButton(
    text: String,
    hue: Hue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors =
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = lerp(hue.start, Color.Black, if (LocalIsDark.current) 0f else 0.05f),
                contentColor = Color.White,
            ),
    ) { Text(text) }
}

/** A quieter button in the same colour: tinted fill, coloured text. */
@Composable
fun HueTonalButton(
    text: String,
    hue: Hue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors =
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = hue.start.copy(alpha = if (LocalIsDark.current) 0.22f else 0.12f),
                contentColor = hue.text,
            ),
    ) { Text(text) }
}

/** The small coloured line at the top of a card saying what it's about. */
@Composable
fun Kicker(
    text: String,
    hue: Hue,
    icon: ImageVector? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = hue.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = hue.text)
    }
}

/** Segmented-button colours in a measure's hue. */
@Composable
fun hueSegmentedColors(hue: Hue): androidx.compose.material3.SegmentedButtonColors =
    androidx.compose.material3.SegmentedButtonDefaults.colors(
        activeContainerColor = hue.start.copy(alpha = if (LocalIsDark.current) 0.26f else 0.14f),
        activeContentColor = hue.text,
        activeBorderColor = MaterialTheme.colorScheme.outline,
        inactiveBorderColor = MaterialTheme.colorScheme.outline,
    )

/** A few bars in a row with a label under each, e.g. seven days of one figure. */
@Composable
fun MiniBars(
    values: List<Float>,
    labels: List<String>,
    hue: Hue,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    /** A reference line (same units as [values]), e.g. a limit. */
    line: Float? = null,
) {
    val colors = LocalDataColors.current
    val max = maxOf(values.maxOrNull() ?: 0f, line ?: 0f, 0.0001f)
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.5f
            values.forEachIndexed { i, v ->
                val left = i * slot + (slot - barW) / 2
                drawRoundRect(hue.start.copy(alpha = colors.trackAlpha * 0.7f), Offset(left, 0f), Size(barW, size.height), androidx.compose.ui.geometry.CornerRadius(barW / 2))
                val bh = size.height * (v / max)
                if (bh > 0f) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(hue.end, hue.start), startY = size.height - bh, endY = size.height),
                        Offset(left, size.height - bh),
                        Size(barW, bh.coerceAtLeast(barW)),
                        androidx.compose.ui.geometry.CornerRadius(barW / 2),
                    )
                }
            }
            line?.let { l ->
                val y = size.height - size.height * (l / max)
                drawLine(colors.usual, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
