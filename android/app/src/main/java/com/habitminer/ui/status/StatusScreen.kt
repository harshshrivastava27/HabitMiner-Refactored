@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.status

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.habitminer.analytics.Format
import com.habitminer.analytics.SensingMode
import com.habitminer.data.ContextSnapshotEntity
import com.habitminer.engine.HabitUiState
import com.habitminer.ui.Labels
import com.habitminer.ui.design.HeroContainer
import com.habitminer.ui.design.ListRow
import com.habitminer.ui.design.Note
import com.habitminer.ui.design.RowGroup
import com.habitminer.ui.design.ScreenPadding
import com.habitminer.ui.design.ScreenTitle
import com.habitminer.ui.design.SectionHeader
import com.habitminer.ui.theme.LocalDataColors
import java.util.Locale

/** Where Status can send you. */
interface StatusNavigation {
    fun openPermissions()
}

@Composable
fun StatusScreen(
    state: HabitUiState,
    nav: StatusNavigation,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalDataColors.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    var showDiagnostics by remember { mutableStateOf(false) }
    // Cheap system calls, re-read on each recomposition so changes made in system settings show up.
    val restricted = isBackgroundRestricted(context)
    val optimised = isBatteryOptimised(context)
    val xiaomi = isXiaomiFamily()
    val permissionsOk = state.hasUsagePermission && state.hasRuntimePermissions && state.hasNotificationPermission
    val hoursSoFar = java.time.LocalTime.now().hour + 1
    val hoursWithReading =
        state.todaySnapshots.map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault()).hour }.distinct().size
            .coerceAtMost(hoursSoFar)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "title") { ScreenTitle("Status", subtitle = "Is HabitMiner collecting, and what does it cost?") }

        item(key = "hero") {
            val (ok, title, body) =
                when {
                    !permissionsOk -> Triple(false, "Collection is paused", "A permission is missing. Grant it to keep recording.")
                    restricted -> Triple(false, "Android is limiting HabitMiner", "Background use is restricted, so readings stop when the app is closed.")
                    !state.isMonitoringServiceActive -> Triple(false, "Background monitoring stopped", "Your phone may have closed HabitMiner to save battery.")
                    else -> Triple(true, "Everything is working", "Usage and surroundings are being recorded on this phone.")
                }
            Spacer(Modifier.height(8.dp))
            HeroContainer(accent = if (ok) colors.goodHue.start else colors.alert) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (ok) colors.goodHue.text else colors.alert,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!ok) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        when {
                            !permissionsOk -> nav.openPermissions()
                            restricted -> openAppDetails(context)
                            else -> requestUnrestrictedBattery(context)
                        }
                    }) { Text(if (!permissionsOk) "Grant permission" else "Open settings") }
                }
            }
        }

        if (!state.hasNotificationPermission) {
            item(key = "restricted") {
                com.habitminer.ui.RestrictedSettingsGuide("notification access", modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp))
            }
        }
        item(key = "check-header") { SectionHeader("Checklist", icon = Icons.Rounded.Checklist, iconColor = colors.goodHue.start) }
        item(key = "checklist") {
            RowGroup {
                row { CheckRow("Usage access", "Which apps you use and when", state.hasUsagePermission, Icons.Rounded.BarChart, colors.screenHue.start) { nav.openPermissions() } }
                row {
                    CheckRow("Notification access", "Counts notifications per app, never their text", state.hasNotificationPermission, Icons.Rounded.Notifications, colors.notificationsHue.start) {
                        nav.openPermissions()
                    }
                }
                row { CheckRow("Physical activity", "Steps, to tell walking from sitting", state.stepPermission, Icons.Rounded.DirectionsWalk, colors.stepsHue.start) { nav.openPermissions() } }
                row {
                    CheckRow("Battery optimisation off", "Lets readings run in the background", !optimised, Icons.Rounded.BatteryChargingFull, colors.goodHue.start) {
                        requestUnrestrictedBattery(context)
                    }
                }
                row {
                    CheckRow(
                        "Background monitoring",
                        if (state.isMonitoringServiceActive) "Running" else "Stopped",
                        state.isMonitoringServiceActive,
                        Icons.Rounded.Sync,
                        colors.freeHue.start,
                    ) { requestUnrestrictedBattery(context) }
                }
                row {
                    ListRow(
                        "Last data received",
                        icon = Icons.Rounded.Schedule,
                        iconColor = colors.sleepHue.start,
                        supporting = state.latestContext?.timestamp?.let { "Reading ${Labels.age(it)}" + (state.lastUsageUpdate?.let { u -> ", app use ${Labels.age(u)}" } ?: "") } ?: "No readings yet",
                    )
                }
                row {
                    ListRow(
                        "Coverage today",
                        icon = Icons.Rounded.DonutLarge,
                        iconColor = colors.pickupsHue.start,
                        supporting = "Hours with at least one reading",
                        trailingText = "$hoursWithReading of $hoursSoFar",
                    )
                }
            }
        }

        if (optimised || restricted || xiaomi) {
            item(key = "keep") {
                SectionHeader("Keep HabitMiner running")
                Note(
                    if (xiaomi) {
                        "Xiaomi, Redmi and POCO phones often pause apps in the background. These settings keep HabitMiner collecting. Also lock it in Recents: long-press its card and tap the lock."
                    } else {
                        "Your phone can pause HabitMiner in the background to save battery."
                    },
                )
                RowGroup {
                    if (optimised) row { ListRow("Let it run in the background", supporting = "Battery optimisation", onClick = { requestUnrestrictedBattery(context) }) }
                    if (restricted) row { ListRow("Allow background use", supporting = "App settings", onClick = { openAppDetails(context) }) }
                    if (xiaomi) {
                        row { ListRow("Turn on Autostart", supporting = "MIUI security settings", onClick = { openXiaomiAutostart(context) }) }
                        row { ListRow("Battery saver: No restrictions", supporting = "App settings", onClick = { openAppDetails(context) }) }
                    }
                }
            }
        }

        item(key = "cost-header") {
            SectionHeader(
                "What it costs",
                icon = Icons.Rounded.Bolt,
                iconColor = colors.stepsHue.start,
                info =
                    "Each reading switches sensors on for about 3 seconds. Readings run every 5 minutes when you're moving with the screen on, " +
                        "every 15 when the screen is on or charging, and every 30 when the phone is idle. Between readings the phone is free to sleep.",
            )
        }
        item(key = "cost") {
            val mode = state.sensingModeName?.let { runCatching { SensingMode.valueOf(it) }.getOrNull() }
            RowGroup {
                row {
                    ListRow(
                        "Sensing mode",
                        supporting = mode?.explanation,
                        trailingText = mode?.let { "every ${it.intervalMs / 60_000} min" } ?: "starting",
                        icon = Icons.Rounded.Tune,
                        iconColor = colors.freeHue.start,
                    )
                }
                row { ListRow("Sensors switched on today", trailingText = formatSeconds(state.sensingMsToday), icon = Icons.Rounded.Sensors, iconColor = colors.stepsHue.start) }
                row { ListRow("Readings today", trailingText = "${state.todaySnapshots.size}", icon = Icons.Rounded.Timeline, iconColor = colors.pickupsHue.start) }
            }
        }
        item(key = "battery") { BatteryUse(state.selfHealth) }

        item(key = "sensors-header") { SectionHeader("Sensors", icon = Icons.Rounded.Sensors, iconColor = colors.freeHue.start) }
        item(key = "sensors") {
            val sensors = sensorStatuses(sensorManager, state.latestSensorContext, state.stepsToday, state.stepPermission)
            RowGroup {
                sensors.forEach { s ->
                    row {
                        val (status, color) =
                            when {
                                !s.present -> "Not on this phone" to colors.alert
                                s.lastReading != null -> s.lastReading to colors.good
                                else -> s.pendingText to colors.caution
                            }
                        ListRow(s.name, supporting = status, trailing = { StatusDot(color) })
                    }
                }
            }
        }

        item(key = "diag-header") {
            SectionHeader("Diagnostics", actionLabel = if (showDiagnostics) "Hide" else "Show", onAction = { showDiagnostics = !showDiagnostics })
        }
        if (showDiagnostics) {
            item(key = "diag") {
                RowGroup {
                    row { ListRow("App sessions stored", trailingText = "${state.usageRecordCount}") }
                    row { ListRow("Surroundings readings", trailingText = "${state.contextRecordCount}") }
                    row { ListRow("Days with data", trailingText = "${state.daysOfData}") }
                    row { ListRow("Routines found", trailingText = "${state.discoveredHabits.size}") }
                    row { ListRow("Labels collected", trailingText = "${state.labelCount}") }
                    row { ListRow("Database size", trailingText = databaseSize(context)) }
                    row { ListRow("Data kept for", trailingText = "${state.retentionDays} days") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    detail: String,
    ok: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onFix: () -> Unit,
) {
    val colors = LocalDataColors.current
    ListRow(
        title,
        supporting = detail,
        icon = icon,
        iconColor = iconColor,
        trailing = {
            if (ok) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Done", tint = colors.goodHue.text, modifier = Modifier.size(24.dp))
            } else {
                Text("Fix", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        onClick = if (ok) null else onFix,
    )
}

@Composable
private fun StatusDot(color: Color) {
    androidx.compose.foundation.layout.Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

private data class SensorStatus(
    val name: String,
    val present: Boolean,
    val lastReading: String?,
    val pendingText: String = "No reading yet",
)

private fun sensorStatuses(
    sm: SensorManager,
    latest: ContextSnapshotEntity?,
    stepsToday: Long,
    stepPermission: Boolean,
): List<SensorStatus> {
    fun has(type: Int) = sm.getDefaultSensor(type) != null
    val fresh = latest?.takeIf { System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000L }
    return listOf(
        SensorStatus(
            "Motion",
            has(Sensor.TYPE_ACCELEROMETER),
            fresh?.let { s -> Labels.motion(s)?.let { m -> if (Labels.motionFromSteps(s)) "${m.label}, from steps" else m.label } },
        ),
        SensorStatus("Rotation", has(Sensor.TYPE_GYROSCOPE), fresh?.gyroEnergy?.takeIf { it >= 0f }?.let { "Working" }, pendingText = "Read on scheduled readings only"),
        SensorStatus("Light", has(Sensor.TYPE_LIGHT), fresh?.lightLux?.takeIf { it >= 0f }?.let { "${it.toInt()} lux" }),
        SensorStatus("Proximity", has(Sensor.TYPE_PROXIMITY), fresh?.proximityNear?.let { "Working" }),
        SensorStatus(
            "Step counter",
            has(Sensor.TYPE_STEP_COUNTER),
            when {
                !stepPermission -> null
                stepsToday >= 0 -> "%,d steps today".format(stepsToday)
                else -> null
            },
            pendingText = if (stepPermission) "Waiting for your first steps" else "Needs the Physical activity permission",
        ),
    )
}

internal fun isBackgroundRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    return am.isBackgroundRestricted
}

internal fun isBatteryOptimised(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isXiaomiFamily(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

@SuppressLint("BatteryLife")
internal fun requestUnrestrictedBattery(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!tryStart(context, direct)) tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun openXiaomiAutostart(context: Context) {
    val autostart = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    if (!tryStart(context, autostart)) openAppDetails(context)
}

internal fun openAppDetails(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun tryStart(
    context: Context,
    intent: Intent,
): Boolean = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

private fun databaseSize(context: Context): String {
    val main = context.getDatabasePath("habitminer_database")
    val bytes = listOf(main, java.io.File(main.path + "-wal"), java.io.File(main.path + "-shm")).filter { it.exists() }.sumOf { it.length() }
    return when {
        bytes <= 0L -> "Unknown"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatSeconds(ms: Long): String =
    when {
        ms < 1000L -> "under a second"
        ms < 60_000L -> "${ms / 1000} s"
        else -> Format.duration(ms) + " ${(ms / 1000) % 60} s"
    }

/** HabitMiner's own battery use: an estimate from its sensor, processor and wake-lock time. */
@Composable
private fun BatteryUse(week: List<com.habitminer.analytics.PowerModel.DayUse>) {
    val today = week.lastOrNull() ?: return
    val colors = LocalDataColors.current
    val awakeMin = today.wakelockMs / 60_000
    val limitMin = com.habitminer.analytics.PowerModel.WAKELOCK_LIMIT_MS / 60_000
    val share = today.sharePct
    SectionHeader(
        "Its own battery use",
        icon = Icons.Rounded.BatteryStd,
        iconColor = colors.goodHue.start,
        info =
            "An estimate, worked out the way Android's battery screen does it: how long the sensors and processor ran, times the current they draw. " +
                "Android flags an app that keeps the phone awake for 2 hours or more in a day.",
    )
    RowGroup {
        row {
            ListRow(
                "Battery used today",
                supporting =
                    if (share != null) {
                        "Sensors %.0f mAh, processor %.0f mAh, awake %.0f mAh".format(today.sensorMah, today.cpuMah, today.awakeMah)
                    } else {
                        "Your phone doesn't report its battery size, so only mAh is shown"
                    },
                trailingText = if (share != null) "about %.1f%%".format(share) else "%.0f mAh".format(today.totalMah),
                trailingColor = colors.goodHue.text,
                icon = Icons.Rounded.BatteryChargingFull,
                iconColor = colors.goodHue.start,
            )
        }
        row {
            ListRow(
                "Kept your phone awake",
                supporting = "Android's line is $limitMin min a day",
                trailingText = if (awakeMin < 1) "under 1 min" else "$awakeMin min",
                trailingColor = if (today.excessiveWakelocks) colors.alert else null,
                icon = Icons.Rounded.Bedtime,
                iconColor = colors.sleepHue.start,
            )
        }
        if (week.size >= 2) {
            row {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Awake time, last 7 days", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    com.habitminer.ui.design.MiniBars(
                        values = week.map { it.wakelockMs / 60_000f },
                        labels = week.map { it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) },
                        hue = colors.sleepHue,
                        height = 64.dp,
                    )
                }
            }
        }
    }
}
