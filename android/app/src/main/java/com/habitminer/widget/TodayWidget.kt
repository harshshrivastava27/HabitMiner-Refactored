package com.habitminer.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.layout.width
import androidx.glance.layout.size
import androidx.glance.layout.Alignment
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import com.habitminer.ui.theme.VividDark
import com.habitminer.ui.theme.VividLight

/**
 * Today on the home screen: phone time against your usual by now, pickups and phone-free time,
 * with the same three rings as the app when there's room. Updated after readings, at most every
 * few minutes.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val data = WidgetStore.load(context)
        provideContent {
            GlanceTheme(colors = ColorProviders(light = VividLight, dark = VividDark)) { Content(data) }
        }
    }

    @Composable
    private fun Content(data: WidgetData?) {
        val wide = LocalSize.current.width >= WIDE.width
        val open = actionStartActivity<MainActivity>(actionParametersOf(OPEN to "today"))
        val pink = ColorProvider(day = Color(0xFFD5104A), night = Color(0xFFFF4D7D))
        val lime = ColorProvider(day = Color(0xFF3F8F00), night = Color(0xFFB8F53A))
        val cyan = ColorProvider(day = Color(0xFF0088B0), night = Color(0xFF3FDCFB))
        Row(
            modifier =
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp).clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (data == null) {
                Column {
                    Text("Today", style = TextStyle(color = pink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.height(6.dp))
                    Text("Open HabitMiner to start", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                }
                return@Row
            }
            if (wide) {
                val rings =
                    RingBitmap.render(
                        220,
                        listOf(
                            (data.screenGoalMs?.let { data.screenMs.toFloat() / it } ?: 0f) to RingBitmap.SCREEN,
                            (data.usualPickups?.let { data.unlocks.toFloat() / it } ?: 0f) to RingBitmap.PICKUPS,
                            ((data.phoneFreeMs ?: 0L) / (2 * 3_600_000f)) to RingBitmap.FREE,
                        ),
                    )
                Image(ImageProvider(rings), contentDescription = "Today's rings", modifier = GlanceModifier.size(86.dp))
                Spacer(GlanceModifier.width(14.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(Format.duration(data.screenMs), style = TextStyle(color = pink, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                Text(comparison(data), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
                Spacer(GlanceModifier.height(4.dp))
                Text("${data.unlocks} pickups", style = TextStyle(color = lime, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                data.phoneFreeMs?.takeIf { it >= 10 * 60_000L }?.let {
                    Text("${Format.duration(it)} phone-free", style = TextStyle(color = cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }
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
