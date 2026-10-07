@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.habitminer.ui.theme.NumberStyles

/**
 * Since Android 13, apps installed from a file (not a store) can't turn on notification access
 * or accessibility until you allow "restricted settings" for them. These are the steps.
 */
@Composable
fun RestrictedSettingsGuide(
    feature: String,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text("If the switch is greyed out", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Android blocks $feature for apps installed from a file until you allow it:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        listOf(
            "Open App info below.",
            "Tap ⋮ in the top corner, then Allow restricted settings, and confirm.",
            "Come back and turn on $feature again.",
        ).forEachIndexed { i, step ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("${i + 1}", style = NumberStyles.small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openAppInfo(context) }) { Text("Open App info") }
    }
}

fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
