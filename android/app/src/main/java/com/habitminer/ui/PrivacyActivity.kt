@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.theme.AppearanceSettings
import com.habitminer.ui.theme.HabitMinerTheme
import com.habitminer.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * What HabitMiner records and what it never does. Opened from Settings, and by Android when
 * you ask why HabitMiner wants Health Connect access.
 */
@AndroidEntryPoint
class PrivacyActivity : ComponentActivity() {
    @Inject lateinit var appearanceSettings: AppearanceSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
            val dark =
                when (appearance.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            HabitMinerTheme(darkTheme = dark, wallpaperColors = appearance.wallpaperColors) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    PrivacyScreen(onBack = ::finish, modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "bar") {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(4.dp))
                Text("Privacy", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "summary") {
            Note(
                "Everything HabitMiner records stays on this phone. There's no account, no server and no analytics. " +
                    "Data leaves the phone only when you export it yourself.",
            )
        }
        item(key = "records") {
            SectionHeader("What it records")
            RowGroup {
                row { ListRow("Apps you use", supporting = "Which app, when and for how long, from Android's usage log") }
                row { ListRow("Unlocks and screen", supporting = "When the phone was unlocked, the screen turned on or off, and charging") }
                row { ListRow("Notifications", supporting = "Which app posted one and whether you opened or dismissed it. Never the text") }
                row { ListRow("Short sensor readings", supporting = "Light, motion and steps for a few seconds at a time, to tell sleep, walking and sitting apart") }
                row { ListRow("Phone settings", supporting = "Do Not Disturb, the next alarm time, headphones (as a scrambled ID) and time zone") }
                row { ListRow("Your answers", supporting = "Check-ins, nap answers, nights you fixed and goals") }
            }
        }
        item(key = "optional") {
            SectionHeader("Only if you turn it on")
            RowGroup {
                row { ListRow("Places", supporting = "A scrambled ID of the Wi-Fi network, never its name or your location") }
                row { ListRow("Health Connect", supporting = "Sleep sessions only, read-only, to check sleep estimates. Nothing is written back") }
                row { ListRow("Calendar", supporting = "Busy or free only, never titles, places or people. Read when needed, not stored") }
            }
        }
        item(key = "never") {
            SectionHeader("What it never does")
            RowGroup {
                row { ListRow("Read messages or screens", supporting = "No notification text, no screen content, no keystrokes") }
                row { ListRow("Track location", supporting = "No GPS. Places use a scrambled Wi-Fi ID, and only when you turn them on") }
                row { ListRow("Send data anywhere", supporting = "The only network use is checking GitHub for app updates") }
            }
        }
        item(key = "control") {
            SectionHeader("Your control")
            Note(
                "Pause recording from the notification or Settings. Choose how long data is kept (30, 90 or 180 days). " +
                    "Export everything as CSV files, or delete it all, from Settings.",
            )
        }
    }
}
