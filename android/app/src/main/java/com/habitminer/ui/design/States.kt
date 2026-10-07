@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholder blocks in the shape of the content that's loading, so the layout doesn't jump
 * when it arrives. A slow fade instead of a shimmer; still when animations are off.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    blocks: List<Dp> = listOf(56.dp, 92.dp, 20.dp, 120.dp),
) {
    val reduced = rememberReducedMotion()
    val pulse =
        if (reduced) {
            0.6f
        } else {
            val t = rememberInfiniteTransition(label = "skeleton")
            val a by t.animateFloat(0.45f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
            a
        }
    Column(
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 16.dp).semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        blocks.forEachIndexed { i, h ->
            Box(
                Modifier
                    .fillMaxWidth(if (i % 3 == 2) 0.6f else 1f)
                    .height(h)
                    .alpha(pulse)
                    .clip(RoundedCornerShape(if (h > 40.dp) 20.dp else 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

/**
 * An empty or blocked state: what this space will hold, and the one thing to do about it.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
