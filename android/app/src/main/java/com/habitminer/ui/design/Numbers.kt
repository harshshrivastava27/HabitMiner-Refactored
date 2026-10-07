@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.Format
import com.habitminer.ui.theme.NumberStyles

/** "2h 14m" as (hours, minutes); under an hour has no hours part. */
fun durationParts(ms: Long): List<Pair<String, String>> {
    val totalMin = (ms / 60_000L).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) listOf("$h" to "h", m.toString().padStart(2, '0') to "m") else listOf("$m" to "m")
}

/**
 * The screen's main figure: a duration in the expanded face with smaller units. Counts up from
 * zero the first time it's shown (once per screen, not on every refresh), unless animations
 * are off.
 */
@Composable
fun HeroDuration(
    ms: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    countUp: Boolean = true,
) {
    val reduced = rememberReducedMotion()
    var shown by rememberSaveable { mutableStateOf(false) }
    val anim = remember { Animatable(if (countUp && !reduced && !shown) 0f else ms.toFloat()) }
    LaunchedEffect(ms) {
        if (!countUp || reduced || shown) {
            anim.snapTo(ms.toFloat())
        } else {
            anim.animateTo(ms.toFloat(), tween(durationMillis = 900, easing = FastOutSlowInEasing))
        }
        shown = true
    }
    val parts = durationParts(anim.value.toLong())
    val unitStyle = NumberStyles.heroUnit.toSpanStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text =
            buildAnnotatedString {
                parts.forEachIndexed { i, (num, unit) ->
                    append(num)
                    withStyle(unitStyle) { append(unit) }
                    if (i < parts.lastIndex) withStyle(unitStyle) { append(" ") }
                }
            },
        style = NumberStyles.hero,
        color = color,
        modifier = modifier.semantics { contentDescription = Format.duration(ms) },
    )
}

/** One figure in a row of stats: label, number, a short comparison. */
@Composable
fun StatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NumberStyles.stat, color = valueColor, modifier = Modifier.padding(top = 2.dp))
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Two to four [StatColumn]s side by side at the screen margin. */
@Composable
fun StatRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** "1,234" with grouping. */
fun grouped(n: Long): String = "%,d".format(n)
