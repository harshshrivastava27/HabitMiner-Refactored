package com.habitminer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.habitminer.analytics.Format
import com.habitminer.ui.MainActivity
import com.habitminer.ui.theme.PineDark
import com.habitminer.ui.theme.PineLight

/**
 * Today on the home screen: phone time against your usual by now, pickups, and (when wide
 * enough) the insight of the day. Updated after readings, at most every few minutes.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val data = WidgetStore.load(context)
        provideContent {
            GlanceTheme(colors = ColorProviders(light = PineLight, dark = PineDark)) { Content(data) }
        }
    }

    @Composable
    private fun Content(data: WidgetData?) {
        val wide = LocalSize.current.width >= WIDE.width
        val open = actionStartActivity<MainActivity>(actionParametersOf(OPEN to "today"))
        Column(
            modifier =
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)
                    .padding(horizontal = 16.dp, vertical = 14.dp).clickable(open),
        ) {
            Text("Today", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            if (data == null) {
                Spacer(GlanceModifier.height(6.dp))
                Text("Open HabitMiner to start", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                return@Column
            }
            Text(Format.duration(data.screenMs), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold))
            Text(comparison(data), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
            Spacer(GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text("${data.unlocks} pickups", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                data.phoneFreeMs?.takeIf { wide && it >= 30 * 60_000L }?.let {
                    Text("  ·  ${Format.duration(it)} phone-free", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                }
            }
            if (wide) {
                Spacer(GlanceModifier.height(10.dp))
                Text(
                    data.insightTitle ?: "Nothing stands out today",
                    style = TextStyle(color = if (data.insightTitle != null) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2,
                )
            }
        }
    }

    private fun comparison(data: WidgetData): String {
        val usual = data.usualByNowMs ?: return "Your usual appears after a week"
        val diff = data.screenMs - usual
        return when {
            kotlin.math.abs(diff) < 10 * 60_000L -> "About usual for now"
            diff < 0 -> "${Format.duration(-diff)} less than usual by now"
            else -> "${Format.duration(diff)} more than usual by now"
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val OPEN = ActionParameters.Key<String>("open")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
