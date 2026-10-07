@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App icons rendered once and kept in memory (icons are small; 120 of them is a few MB). */
private object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(120)
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun peek(key: String): ImageBitmap? = cache.get(key)

    fun isMissing(packageName: String) = packageName in missing

    fun load(
        context: Context,
        packageName: String,
        px: Int,
    ): ImageBitmap? {
        val key = "$packageName@$px"
        cache.get(key)?.let { return it }
        if (packageName in missing) return null
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (drawable == null) {
            missing += packageName
            return null
        }
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * The app's own launcher icon, or its initial in a tinted circle when it's uninstalled or
 * unknown (and in screenshots).
 */
@Composable
fun AppIcon(
    packageName: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = "$packageName@$px"
    var icon by remember(key) { mutableStateOf(packageName?.let { AppIconCache.peek(key) }) }
    if (packageName != null && icon == null && !AppIconCache.isMissing(packageName)) {
        LaunchedEffect(key) { icon = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName, px) } }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = modifier.size(size).clip(CircleShape))
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
