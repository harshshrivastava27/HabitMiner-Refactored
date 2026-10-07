@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.mindful

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.habitminer.analytics.TimeUtil
import com.habitminer.collection.DeviceEvents
import com.habitminer.data.DeviceEventEntity
import com.habitminer.domain.AppIdentityResolver
import com.habitminer.repository.ContextRepository
import com.habitminer.ui.design.AppIcon
import com.habitminer.ui.theme.HabitMinerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** A few seconds to breathe before an app you want to use less. Then you decide. */
@AndroidEntryPoint
class PauseActivity : ComponentActivity() {
    @Inject lateinit var appIdentityResolver: AppIdentityResolver

    @Inject lateinit var contextRepository: ContextRepository

    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val name = appIdentityResolver.getAppName(pkg)
        val seconds = MindfulPause.seconds(this)
        // Animations off in system settings: keep the circle still.
        val reduced = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val focusUntil = MindfulPause.focusUntil(this)
        var opensToday by mutableIntStateOf(-1)
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val start = TimeUtil.startOfDay(TimeUtil.dateOf(System.currentTimeMillis(), zone), zone)
            opensToday = runCatching { contextRepository.getUsageForPackageSince(pkg, start).size }.getOrDefault(-1)
        }
        setContent {
            HabitMinerTheme(darkTheme = isSystemInDarkTheme()) {
                BackHandler { leave(pkg, focusUntil != null) }
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    PauseScreen(
                        pkg = pkg,
                        appName = name,
                        seconds = seconds,
                        reduced = reduced,
                        opensToday = opensToday,
                        focusUntil = focusUntil,
                        onOpen = { open(pkg) },
                        onLeave = { leave(pkg, focusUntil != null) },
                        onEndFocus = {
                            MindfulPause.endFocus(this)
                            open(pkg)
                        },
                    )
                }
            }
        }
    }

    private fun log(
        pkg: String,
        outcome: String,
        focus: Boolean,
    ) {
        // Outlives the activity, which finishes right after.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                contextRepository.insertDeviceEvent(DeviceEventEntity(eventType = DeviceEvents.MINDFUL_PAUSE, packageName = pkg, detail = "outcome=$outcome;focus=$focus"))
            }
        }
    }

    private fun open(pkg: String) {
        decided = true
        MindfulPauseService.allowed[pkg] = System.currentTimeMillis()
        log(pkg, "opened", focus = false)
        finish()
    }

    private fun leave(
        pkg: String,
        focus: Boolean,
    ) {
        decided = true
        log(pkg, "left", focus)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Switched away without choosing: treat it as leaving, and don't stay on top later.
        if (!decided && !isChangingConfigurations) {
            intent.getStringExtra(EXTRA_PACKAGE)?.let { log(it, "dismissed", focus = MindfulPause.focusUntil(this) != null) }
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
    }
}

@Composable
private fun PauseScreen(
    pkg: String,
    appName: String,
    seconds: Int,
    reduced: Boolean,
    opensToday: Int,
    focusUntil: Long?,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
    onEndFocus: () -> Unit,
) {
    var left by remember { mutableIntStateOf(seconds) }
    var showEnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    val breath = rememberInfiniteTransition(label = "breath")
    val scale by breath.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val ring = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(pkg, appName, size = 44.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (focusUntil != null) "You're focusing" else "Take a breath",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                focusUntil != null -> "Focus session until ${com.habitminer.analytics.Format.clock(focusUntil, ZoneId.systemDefault())}. $appName can wait."
                opensToday > 0 -> "You've opened $appName $opensToday time${if (opensToday == 1) "" else "s"} today. Is this what you want right now?"
                else -> "Is $appName what you want right now?"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * (if (reduced) 0.8f else scale)
                drawCircle(ring.copy(alpha = 0.14f), radius = r)
                drawCircle(ring.copy(alpha = 0.5f), radius = r, style = Stroke(width = 3.dp.toPx()))
            }
            Text(
                if (left > 0) "$left" else "",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(36.dp))
        Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        Spacer(Modifier.height(8.dp))
        if (focusUntil == null) {
            OutlinedButton(onClick = onOpen, enabled = left == 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (left > 0) "Open $appName in $left s" else "Open $appName")
            }
        } else if (!showEnd) {
            TextButton(onClick = { showEnd = true }) { Text("End focus to open $appName") }
        } else {
            OutlinedButton(onClick = onEndFocus, enabled = left == 0, modifier = Modifier.fillMaxWidth()) { Text("End focus and open") }
        }
    }
}
