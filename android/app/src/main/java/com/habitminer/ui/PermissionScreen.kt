@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.habitminer.R
import com.habitminer.ui.theme.LocalDataColors
import com.habitminer.ui.theme.NumberStyles

/**
 * First run: what HabitMiner needs and why, as three steps in order. Notifications are asked
 * for later, in context, once there's something worth telling you.
 */
@Composable
fun PermissionScreen(
    hasUsage: Boolean,
    hasRuntime: Boolean,
    hasNotification: Boolean,
    onRequestUsage: () -> Unit,
    onRuntimePermissionsGranted: () -> Unit,
    onRequestNotification: () -> Unit,
    onImport: (() -> Unit)? = null,
    onSkipNotification: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val runtime = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRuntimePermissionsGranted() }
    val current =
        when {
            !hasUsage -> 0
            !hasRuntime -> 1
            !hasNotification -> 2
            else -> 3
        }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF123B33)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Get to know your phone habits", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            "HabitMiner learns your routines from how you use your phone: when, which apps, how you sleep. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Step(
            1,
            "Usage access",
            "See which apps you use and when. Android lists it under Special app access.",
            done = hasUsage,
            active = current == 0,
            action = "Open settings",
            onAction = onRequestUsage,
        )
        Step(
            2,
            "Physical activity",
            "Count steps, to tell walking from sitting and to spot when you get up.",
            done = hasRuntime,
            active = current == 1,
            action = "Allow",
            onAction = {
                if (runtime.isEmpty()) {
                    onRuntimePermissionsGranted()
                } else {
                    launcher.launch(runtime)
                }
            },
        )
        Step(
            3,
            "Notification access",
            "Count notifications per app to see what makes you pick up the phone. Never their text.",
            done = hasNotification,
            active = current == 2,
            action = "Open settings",
            onAction = onRequestNotification,
        )
        if (current == 2) {
            RestrictedSettingsGuide("notification access", modifier = Modifier.padding(start = 46.dp, top = 4.dp))
            if (onSkipNotification != null) {
                TextButton(onClick = onSkipNotification, modifier = Modifier.padding(start = 34.dp)) { Text("Skip for now") }
                Text(
                    "Without it, HabitMiner can't tell which pickups came after a notification. You can turn it on later from Status.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 46.dp),
                )
            }
        }
        if (current == 1) {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }) { Text("Open app settings instead") }
        }
        if (onImport != null) {
            Spacer(Modifier.height(20.dp))
            Text("Used the original HabitMiner?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Export your data there and import it here to keep your history. You can also share the export straight to this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onImport) { Text("Import an export") }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    active: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    val colors = LocalDataColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(
                when {
                    done -> colors.good.copy(alpha = 0.18f)
                    active -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = colors.good, modifier = Modifier.size(18.dp))
            } else {
                Text("$number", style = NumberStyles.small, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (active || done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
