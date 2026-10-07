@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.HabitViewModel
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.theme.Appearance
import com.habitminer.ui.theme.ThemeMode
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    state: HabitUiState,
    viewModel: HabitViewModel,
    appearance: Appearance,
    onThemeMode: (ThemeMode) -> Unit,
    onWallpaperColors: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenToday: () -> Unit = {},
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    // The export's share sheet: a one-shot event, not replayed after rotation.
    LaunchedEffect(viewModel) {
        viewModel.shareExportEvent.collect { path ->
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(path))
            val share =
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(android.content.Intent.createChooser(share, "Share your HabitMiner data"))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importData(uri) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "bar") {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(4.dp))
                Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }
        }

        item(key = "appearance") {
            SectionHeader("Appearance")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = appearance.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            RowGroup {
                row {
                    ToggleRow(
                        "Wallpaper colours",
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            "Tint buttons and highlights with your wallpaper's colours. Charts keep their own colours."
                        } else {
                            "Needs Android 12 or later."
                        },
                        appearance.wallpaperColors,
                        enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                        onChange = onWallpaperColors,
                    )
                }
            }
        }

        item(key = "notifications") {
            SectionHeader("Questions and reminders", info = "These share one limit of three a day, so turning more on doesn't mean more interruptions.")
            RowGroup {
                row {
                    ToggleRow(
                        "Quick questions",
                        "\"What are you doing?\", \"Were you asleep?\" after a likely nap, and \"What's going on?\" when your routine changes. Between 09:00 and 22:00.",
                        state.features.checkIns,
                        onChange = viewModel::setCheckInsEnabled,
                    )
                }
                row {
                    ToggleRow(
                        "Gentle nudges",
                        "After 25 minutes of late-night scrolling or gaming, or an hour straight during the day.",
                        state.features.nudges,
                        onChange = viewModel::setNudgesEnabled,
                    )
                }
                row {
                    ToggleRow(
                        "Unusual-day summary",
                        "Around 21:00 on days that were clearly different from usual.",
                        state.features.deviationAlerts,
                        onChange = viewModel::setDeviationAlertsEnabled,
                    )
                }
                row { ToggleRow("Weekly recap", "A short summary on Sunday evening.", state.features.digest, onChange = viewModel::setDigestEnabled) }
                row {
                    ListRow(
                        "Answer a question now",
                        supporting = "${state.checkInCount} answered, ${state.labelCount} labels in total",
                        onClick = {
                            viewModel.openCheckIn(null)
                            onOpenToday()
                        },
                    )
                }
            }
        }

        item(key = "insights") { InsightsSection(state, viewModel) }

        item(key = "places") { PlacesSection(state, viewModel) }

        item(key = "sources") { SourcesSection(state, viewModel) }

        item(key = "data") {
            SectionHeader(
                "Your data",
                info =
                    "Everything HabitMiner records stays on this phone. Nothing is sent to a server.\n\n" +
                        "Export makes a ZIP of CSV files you can open in a spreadsheet or analyse in Python. Import merges an export back in " +
                        "without duplicates, including exports from the original HabitMiner app.",
            )
            Note("Keep data for")
            val options = listOf(30, 90, 180)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
                options.forEachIndexed { i, days ->
                    SegmentedButton(
                        selected = days == state.retentionDays,
                        onClick = { viewModel.setRetentionDays(days) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text("$days days") }
                }
            }
            Spacer(Modifier.height(8.dp))
            RowGroup {
                row { ListRow("Export data", supporting = "A ZIP of CSV files to share or analyse", onClick = { viewModel.exportDataToCsv() }) }
                row {
                    ListRow(
                        "Import an export",
                        supporting = "From this app or the original HabitMiner",
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    )
                }
                row { ListRow("Delete all data", supporting = "Usage, readings, routines and labels", onClick = { confirmClear = true }) }
            }
            state.exportMessage?.let { msg ->
                LaunchedEffect(msg) {
                    delay(if (msg.startsWith("Imported")) 12_000 else 5_000)
                    viewModel.clearExportMessage()
                }
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 10.dp))
            }
        }

        item(key = "about") {
            SectionHeader("About")
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
            RowGroup {
                row { ListRow("HabitMiner Extended", supporting = "Version $version") }
                row {
                    ListRow(
                        "Privacy",
                        supporting = "What's recorded, what never is, and where it stays",
                        onClick = { context.startActivity(android.content.Intent(context, PrivacyActivity::class.java)) },
                    )
                }
                row { ListRow("Typeface", supporting = "Mona Sans by GitHub, SIL Open Font License 1.1") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all data?") },
            text = { Text("This deletes your app usage, readings, routines and labels on this phone. Export first if you want to keep a copy. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearDatabase()
                    confirmClear = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListRow(
        title,
        supporting = description,
        maxSupportingLines = 4,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        onClick = if (enabled) ({ onChange(!checked) }) else null,
    )
}

/** How the insight of the day reaches you, which topics it covers, and quiet hours for everything. */
@Composable
fun InsightsSection(
    state: HabitUiState,
    viewModel: HabitViewModel,
) {
    val settings = state.insightSettings
    var showTopics by remember { mutableStateOf(false) }
    var pickingQuiet by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    SectionHeader(
        "Insights",
        info =
            "Once a day HabitMiner looks for something that clearly stands out against your usual days: a much quieter or " +
                "busier day, an app you used far more, a late night, a personal best. Most days nothing does, and then there's " +
                "no insight.\n\nUseful and Fewer like this teach it which topics you care about.",
    )
    val options = com.habitminer.analytics.InsightFrequency.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        options.forEachIndexed { i, f ->
            SegmentedButton(
                selected = settings.frequency == f,
                onClick = { viewModel.updateInsightSettings { it.copy(frequency = f) } },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(if (f == com.habitminer.analytics.InsightFrequency.STANDOUT) "Stand-out" else f.label, maxLines = 1) }
        }
    }
    Note(settings.frequency.description)
    Spacer(Modifier.height(4.dp))
    RowGroup {
        row {
            ListRow(
                "Topics",
                supporting =
                    if (settings.disabled.isEmpty()) {
                        "All topics"
                    } else {
                        "${com.habitminer.analytics.InsightFamily.entries.size - settings.disabled.size} of ${com.habitminer.analytics.InsightFamily.entries.size} on"
                    },
                onClick = { showTopics = !showTopics },
            )
        }
        if (showTopics) {
            com.habitminer.analytics.InsightFamily.entries.forEach { family ->
                row {
                    ToggleRow(
                        family.label,
                        family.description,
                        family !in settings.disabled,
                        onChange = { on -> viewModel.updateInsightSettings { s -> s.copy(disabled = if (on) s.disabled - family else s.disabled + family) } },
                    )
                }
            }
        }
        row {
            val windows = listOf(8 to 20, 9 to 21, 10 to 22)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Notification hours", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("When an insight may arrive as a notification", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    windows.forEachIndexed { i, (a, b) ->
                        SegmentedButton(
                            selected = settings.windowStartHour == a && settings.windowEndHour == b,
                            onClick = { viewModel.updateInsightSettings { it.copy(windowStartHour = a, windowEndHour = b) } },
                            shape = SegmentedButtonDefaults.itemShape(i, windows.size),
                        ) { Text(String.format(java.util.Locale.US, "%02d–%02d", a, b)) }
                    }
                }
            }
        }
        row {
            ListRow(
                "Quiet hours",
                supporting =
                    "No questions, insights or summaries from ${com.habitminer.analytics.Format.clockFromMinutes(settings.quiet.startMinute)} " +
                        "to ${com.habitminer.analytics.Format.clockFromMinutes(settings.quiet.endMinute)}. Late-night nudges still come while you're on the phone.",
                maxSupportingLines = 3,
                onClick = { pickingQuiet = true },
            )
        }
    }
    pickingQuiet?.let { start ->
        com.habitminer.ui.design.TimeDialog(
            title = if (start) "Quiet hours start" else "Quiet hours end",
            initialMinute = if (start) settings.quiet.startMinute else settings.quiet.endMinute,
            onConfirm = { m ->
                viewModel.updateInsightSettings { s -> s.copy(quiet = if (start) s.quiet.copy(startMinute = m) else s.quiet.copy(endMinute = m)) }
                // After the start, ask for the end.
                pickingQuiet = if (start) false else null
            },
            onDismiss = { pickingQuiet = null },
        )
    }
}

/** Opt-in sources that sharpen sleep and timing: Health Connect sleep and calendar busy times. */
@Composable
fun SourcesSection(
    state: HabitUiState,
    viewModel: HabitViewModel,
) {
    val context = LocalContext.current
    var healthDenied by remember { mutableStateOf(false) }
    var calendarDenied by remember { mutableStateOf(false) }
    val healthLauncher =
        rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { granted ->
            val ok = viewModel.healthConnectPermission in granted
            healthDenied = !ok
            viewModel.setHealthConnectEnabled(ok)
        }
    val calendarLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            calendarDenied = !ok
            viewModel.setCalendarEnabled(ok)
        }
    SectionHeader(
        "Other sources",
        info =
            "Both are off by default and read-only. Nothing is copied off the phone.\n\n" +
                "Health Connect: if a watch or sleep app records your sleep, those nights replace HabitMiner's guesses and teach it " +
                "how your nights usually differ from what the phone sees.\n\n" +
                "Calendar: only when you're busy, never titles, places or people. A still phone during a class or meeting isn't " +
                "taken for a nap, and questions wait until you're free.",
    )
    val status = state.features.healthConnectStatus
    RowGroup {
        row {
            ToggleRow(
                "Sleep from Health Connect",
                when {
                    status == "unavailable" -> "Health Connect isn't on this phone."
                    status == "needs_update" -> "Update Health Connect from the Play Store first."
                    healthDenied -> "Sleep access wasn't allowed, so this stays off."
                    else -> "Use nights recorded by a watch or sleep app."
                },
                state.features.healthConnect,
                enabled = status == "available",
                onChange = { enable ->
                    if (enable) {
                        runCatching { healthLauncher.launch(setOf(viewModel.healthConnectPermission)) }
                            .onFailure { healthDenied = true }
                    } else {
                        viewModel.setHealthConnectEnabled(false)
                    }
                },
            )
        }
        row {
            ToggleRow(
                "Busy times from your calendar",
                if (calendarDenied) "Calendar access wasn't allowed, so this stays off." else "Only busy or free, never what the event is.",
                state.features.calendar,
                onChange = { enable ->
                    if (enable) {
                        calendarLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                    } else {
                        viewModel.setCalendarEnabled(false)
                    }
                },
            )
        }
    }
    if (status == "needs_update") {
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp),
        ) { Text("Update Health Connect") }
    }
}

/** Opt-in Wi-Fi places: permission request, explanation and renaming. */
@Composable
fun PlacesSection(
    state: HabitUiState,
    viewModel: HabitViewModel,
) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var denied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
            denied = !granted
            viewModel.setPlacesEnabled(granted)
        }
    SectionHeader(
        "Places",
        info =
            "Groups your phone use by place (home, campus) using the Wi-Fi network you're connected to. Only a scrambled ID is stored, " +
                "never the network's name or your location. Android asks for location access to share the network ID, and location " +
                "services need to stay on.",
    )
    val names = state.insights?.placeNames.orEmpty()
    RowGroup {
        row {
            ToggleRow(
                "Detect places from Wi-Fi",
                if (denied) "Location access wasn't granted, so places stay off." else "Off by default.",
                state.features.places,
                onChange = { enable ->
                    if (enable) {
                        launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        viewModel.setPlacesEnabled(false)
                    }
                },
            )
        }
        if (state.features.places) {
            state.places.forEach { place ->
                row {
                    ListRow(
                        place.label ?: names[place.placeHash] ?: "Unnamed place",
                        supporting = "Last seen ${Labels.age(place.lastSeen)}" + if (place.label == null) ", suggested name" else "",
                        trailingText = "Rename",
                        onClick = {
                            renaming = place.placeHash
                            newName = place.label ?: ""
                        },
                    )
                }
            }
        }
    }
    renaming?.let { hash ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this place") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(30) }, singleLine = true, placeholder = { Text("Home, Library, Hostel") })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renamePlace(hash, newName)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
