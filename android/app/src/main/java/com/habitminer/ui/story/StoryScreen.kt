@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.story

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitminer.analytics.Format
import com.habitminer.analytics.StoryCard
import com.habitminer.analytics.WeeklyStory
import com.habitminer.ui.design.EmptyState
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.design.Skeleton
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.NumberStyles
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StoryRoute(onBack: () -> Unit) {
    val vm: StoryViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    StoryContent(state, onBack)
}

/** The Weekly Story: swipe through the week's cards, then the month at a glance. */
@Composable
fun StoryContent(
    state: StoryState,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Weekly Story", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                state.story?.let { s ->
                    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                    Text("${s.from.format(fmt)} to ${s.to.format(fmt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when {
            state.loading -> Skeleton()
            state.story == null -> EmptyState("Not enough of a week yet", "The Weekly Story appears once there are at least three days of data in the last week.")
            else -> {
                val story = state.story
                val pager = rememberPagerState { story.cards.size }
                Spacer(Modifier.height(12.dp))
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    pageSpacing = 12.dp,
                ) { page -> StoryCardView(story.cards[page]) }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    repeat(story.cards.size) { i ->
                        Box(
                            Modifier.padding(3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                                .background(if (i == pager.currentPage) storyPalette(story.cards[i].kind).first else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                SectionHeader("Month at a glance", info = "Each square is a day, darker for more phone time. Blank days have no data.")
                MonthHeatmap(story)
            }
        }
    }
}

/** Each kind of card gets its own bold gradient and a readable text colour on it. */
private fun storyPalette(kind: StoryCard.Kind): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val white = androidx.compose.ui.graphics.Color.White
    fun c(v: Long) = androidx.compose.ui.graphics.Color(v)
    return when (kind) {
        StoryCard.Kind.WEEK -> Triple(c(0xFFD10A45), c(0xFFFF5C8A), white)
        StoryCard.Kind.PHONE_FREE -> Triple(c(0xFF0369A1), c(0xFF06B6D4), white)
        StoryCard.Kind.ROUTINE -> Triple(c(0xFF3F6212), c(0xFF84CC16), white)
        StoryCard.Kind.CHANGE -> Triple(c(0xFFC2410C), c(0xFFF59E0B), white)
        StoryCard.Kind.SLEEP -> Triple(c(0xFF3730A3), c(0xFF8B5CF6), white)
        StoryCard.Kind.MILESTONES -> Triple(c(0xFFEAB308), c(0xFFFDE047), c(0xFF1F1A00))
        StoryCard.Kind.EXPERIMENT -> Triple(c(0xFF7E22CE), c(0xFFD946EF), white)
    }
}

@Composable
private fun StoryCardView(card: StoryCard) {
    val (from, to, onColor) = storyPalette(card.kind)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 340.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(from, to)))
                .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Text(card.label, style = MaterialTheme.typography.titleSmall, color = onColor.copy(alpha = 0.85f))
        Spacer(Modifier.height(14.dp))
        val numeric = card.headline.firstOrNull()?.isDigit() == true
        Text(
            card.headline,
            style = if (numeric) NumberStyles.hero.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize) else MaterialTheme.typography.headlineMedium,
            color = onColor,
        )
        Spacer(Modifier.height(12.dp))
        if (card.body.isNotEmpty()) {
            Text(card.body, style = MaterialTheme.typography.bodyLarge, color = onColor.copy(alpha = 0.9f))
        }
        if (card.bars.isNotEmpty()) {
            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(20.dp))
            val max = card.bars.maxOf { it.second }.coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = card.bars.joinToString(", ") { "${it.first} ${Format.duration(it.second)}" } },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                card.bars.forEach { (day, ms) ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().height(86.dp)) {
                            val h = size.height * (ms.toFloat() / max)
                            drawRoundRect(onColor.copy(alpha = 0.18f), Offset.Zero, size, CornerRadius(size.width / 2))
                            if (h > 0f) drawRoundRect(onColor, Offset(0f, size.height - h), Size(size.width, h.coerceAtLeast(size.width)), CornerRadius(size.width / 2))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(day, style = MaterialTheme.typography.labelSmall, color = onColor.copy(alpha = 0.85f), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Five weeks of days, Monday first, shaded by phone time in quarters of your own range. */
@Composable
private fun MonthHeatmap(story: WeeklyStory) {
    val colors = LocalDataColors.current
    val values = story.month.mapNotNull { it.second }.filter { it > 0 }.sorted()
    fun level(ms: Long): Int {
        if (values.isEmpty() || ms <= 0) return 0
        val rank = values.count { it <= ms }.toFloat() / values.size
        return when {
            rank <= 0.25f -> 1
            rank <= 0.5f -> 2
            rank <= 0.75f -> 3
            else -> 4
        }
    }
    val desc = "Five weeks of daily phone time. Highest ${values.lastOrNull()?.let { Format.duration(it) } ?: "none"}, lowest ${values.firstOrNull()?.let { Format.duration(it) } ?: "none"}."
    Column(modifier = Modifier.padding(horizontal = ScreenPadding).semantics(mergeDescendants = true) { contentDescription = desc }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        story.month.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { (date, ms) ->
                    val inWeek = !date.isBefore(story.from) && !date.isAfter(story.to)
                    val fill =
                        when {
                            ms == null -> MaterialTheme.colorScheme.surface
                            else -> {
                                val l = level(ms)
                                if (l == 0) colors.screenHue.start.copy(alpha = colors.trackAlpha * 0.5f) else androidx.compose.ui.graphics.lerp(colors.screenHue.start.copy(alpha = 0.35f), colors.screenHue.end, l / 4f)
                            }
                        }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(fill)
                            .then(if (inWeek) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ms != null && level(ms) >= 3) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            (1..4).forEach { l ->
                Box(Modifier.padding(horizontal = 2.dp).size(12.dp).clip(RoundedCornerShape(3.dp)).background(androidx.compose.ui.graphics.lerp(colors.screenHue.start.copy(alpha = 0.35f), colors.screenHue.end, l / 4f)))
            }
            Spacer(Modifier.width(6.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
