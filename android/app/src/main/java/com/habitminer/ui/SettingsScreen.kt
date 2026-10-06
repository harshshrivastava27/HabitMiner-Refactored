@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habitminer.engine.HabitUiState
import com.habitminer.engine.HabitViewModel
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: HabitUiState,
    viewModel: HabitViewModel,
    onOpenToday: () -> Unit = {},
) {
    val context = LocalContext.current
    var confirmClearData by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Collect the one-shot share event from the ViewModel. This uses a SharedFlow
    // with replay=0, so it fires exactly once and is NOT replayed after rotation.
    LaunchedEffect(viewModel) {
        viewModel.shareExportEvent.collect { path ->
            val file = java.io.File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Exported Data"))
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Check-ins, nudges and digest
        SettingsSection(title = "Check-ins & reminders") {
            SettingsSwitch(
                title = "Quick check-ins",
                description = "Up to 3 one-tap questions a day, 09:00–22:00: \"what are you doing?\", \"were you asleep?\" after a likely nap, " +
                    "and \"what's going on?\" when your routine changes for a few days. Answers become labels for testing the app's guesses.",
                checked = state.features.checkIns,
                onChange = viewModel::setCheckInsEnabled,
            )
            SettingsSwitch(
                title = "Gentle nudges",
                description = "A heads-up after 25 minutes of late-night scrolling or gaming, or an hour straight in the day. Shares the 3-a-day limit.",
                checked = state.features.nudges,
                onChange = viewModel::setNudgesEnabled,
            )
            SettingsSwitch(
                title = "Unusual-day summary",
                description = "Around 21:00 on days that were clearly different from usual, with Expected / Unusual buttons. Shares the 3-a-day limit.",
                checked = state.features.deviationAlerts,
                onChange = viewModel::setDeviationAlertsEnabled,
            )
            SettingsSwitch(
                title = "Weekly summary",
                description = "A short recap every Sunday evening.",
                checked = state.features.digest,
                onChange = viewModel::setDigestEnabled,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                viewModel.openCheckIn(null)
                onOpenToday()
            }) { Text("Answer a check-in now") }
            Text(
                text = "${state.checkInCount} check-ins answered · ${state.labelCount} labels in total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        PlacesSection(state, viewModel)

        Spacer(modifier = Modifier.height(24.dp))

        // Privacy & Data Section
        SettingsSection(title = "Privacy & Local Data") {
            SettingsItem(
                icon = Icons.Default.Security,
                title = "On-Device Processing",
                description =
                    "Your usage, sensor, unlock, and notification summaries stay on this device. Data is never sent to a cloud server.",
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Data Retention",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                val options = listOf(30, 90, 180)
                options.forEachIndexed { index, days ->
                    SegmentedButton(
                        selected = days == state.retentionDays,
                        onClick = { viewModel.setRetentionDays(days) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text("${days}d")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SettingsAction(
                icon = Icons.Default.DeleteOutline,
                title = "Clear collected data",
                color = MaterialTheme.colorScheme.error,
                onClick = { confirmClearData = true },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Export Section
        SettingsSection(title = "Data Portability") {
            SettingsItem(
                icon = Icons.Default.Download,
                title = "Export Data (ZIP)",
                description = "Packages all your usage, context, habits, and deviation data into a single .zip file for sharing and external analysis.",
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.exportDataToCsv() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Export Data Now")
            }

            Spacer(modifier = Modifier.height(20.dp))
            SettingsItem(
                icon = Icons.Default.Upload,
                title = "Import Data (ZIP)",
                description =
                    "Restore a ZIP made with Export, e.g. after reinstalling or on a new phone. " +
                        "Your history is merged in without duplicates, and routines are rebuilt from it.",
            )
            Spacer(modifier = Modifier.height(12.dp))
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.importData(uri)
                }
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Import a previous export")
            }

            if (state.exportMessage != null) {
                LaunchedEffect(state.exportMessage) {
                    // Import results are longer, so leave them up a little longer.
                    delay(if (state.exportMessage.startsWith("Imported")) 12_000 else 4_000)
                    viewModel.clearExportMessage()
                }
                Text(
                    text = state.exportMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (confirmClearData) {
        AlertDialog(
            onDismissRequest = { confirmClearData = false },
            title = { Text("Clear local data?") },
            text = {
                Text("This will delete all collected app usage, context snapshots, baselines, and models. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearDatabase()
                        confirmClearData = false
                    },
                ) {
                    Text("Clear Data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearData = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
    var permissionDenied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
            permissionDenied = !granted
            viewModel.setPlacesEnabled(granted)
        }

    SettingsSection(title = "Places (optional)") {
        SettingsSwitch(
            title = "Detect places from Wi-Fi",
            description =
                "Groups your phone use by place (e.g. home, campus) using the Wi-Fi network you're connected to. " +
                    "Only a scrambled ID is stored, never the network name or your location. Android asks for location access to allow this.",
            checked = state.features.places,
            onChange = { enable ->
                if (enable) {
                    launcher.launch(
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                } else {
                    viewModel.setPlacesEnabled(false)
                }
            },
        )
        if (permissionDenied) {
            Text(
                "Location access wasn't granted, so places stay off. You can allow it in Android settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.features.places) {
            Text(
                "Keep location services switched on so Android can share the Wi-Fi ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            val names = state.insights?.placeNames.orEmpty()
            if (state.places.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "No places yet. They appear after a few readings on Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            state.places.forEach { place ->
                val display = place.label ?: names[place.placeHash] ?: "Unnamed place"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(display, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Last seen ${Labels.age(place.lastSeen)}" + if (place.label == null) " · suggested name" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    TextButton(onClick = {
                        renaming = place.placeHash
                        newName = place.label ?: ""
                    }) { Text("Rename") }
                }
            }
        }
    }

    renaming?.let { hash ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this place") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(30) },
                    singleLine = true,
                    placeholder = { Text("e.g. Home, Library, Hostel") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renamePlace(hash, newName)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun SettingsAction(
    icon: ImageVector,
    title: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
