@file:Suppress("ktlint:standard:function-naming")

package com.habitminer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Corner radii by role, not one radius for everything: 4 for chart marks, 12 for grouped
 * rows, 20 for sheets and dialogs, 28 for the one hero container per screen.
 */
val HabitMinerShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun HabitMinerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Take the chrome colours from the wallpaper (Android 12+). Data colours never change. */
    wallpaperColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme =
        if (wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).withPineSurfaces(darkTheme)
        } else if (darkTheme) {
            PineDark
        } else {
            PineLight
        }
    CompositionLocalProvider(
        LocalDataColors provides if (darkTheme) DataColorsDark else DataColorsLight,
        LocalIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HabitMinerTypography,
            shapes = HabitMinerShapes,
            content = content,
        )
    }
}
