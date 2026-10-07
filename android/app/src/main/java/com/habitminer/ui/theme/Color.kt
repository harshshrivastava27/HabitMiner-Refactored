package com.habitminer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * HabitMiner Extended palette: cool pine neutrals with one marigold accent for "now".
 * The pine comes from the icon's background and the marigold from its dot. Surfaces are
 * tonal steps of the same green-grey, so depth comes from tone, not shadows.
 */

internal val PineLight =
    lightColorScheme(
        primary = Color(0xFF1E5F50),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCFE8DF),
        onPrimaryContainer = Color(0xFF0B3A30),
        inversePrimary = Color(0xFF8FD3BD),
        secondary = Color(0xFF4F635C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD6E4DE),
        onSecondaryContainer = Color(0xFF0D1F19),
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDEA8),
        onTertiaryContainer = Color(0xFF2B1900),
        background = Color(0xFFF5F7F6),
        onBackground = Color(0xFF17201D),
        surface = Color(0xFFF5F7F6),
        onSurface = Color(0xFF17201D),
        surfaceVariant = Color(0xFFDCE5E0),
        onSurfaceVariant = Color(0xFF4A5752),
        surfaceTint = Color(0xFF1E5F50),
        inverseSurface = Color(0xFF2C3532),
        inverseOnSurface = Color(0xFFECF2EE),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF78857F),
        outlineVariant = Color(0xFFC5CFCA),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF5F7F6),
        surfaceDim = Color(0xFFD6DCD9),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEEF2F0),
        surfaceContainer = Color(0xFFE8EDEA),
        surfaceContainerHigh = Color(0xFFE2E8E5),
        surfaceContainerHighest = Color(0xFFDCE3DF),
    )

internal val PineDark =
    darkColorScheme(
        primary = Color(0xFF8FD3BD),
        onPrimary = Color(0xFF00382C),
        primaryContainer = Color(0xFF12503F),
        onPrimaryContainer = Color(0xFFB4F0DA),
        inversePrimary = Color(0xFF1E5F50),
        secondary = Color(0xFFB4CBC3),
        onSecondary = Color(0xFF1F352E),
        secondaryContainer = Color(0xFF354B44),
        onSecondaryContainer = Color(0xFFD0E7DF),
        tertiary = Color(0xFFF5BE6A),
        onTertiary = Color(0xFF462B00),
        tertiaryContainer = Color(0xFF654000),
        onTertiaryContainer = Color(0xFFFFDEA8),
        background = Color(0xFF0E1412),
        onBackground = Color(0xFFE1E8E4),
        surface = Color(0xFF0E1412),
        onSurface = Color(0xFFE1E8E4),
        surfaceVariant = Color(0xFF3B4642),
        onSurfaceVariant = Color(0xFFA9B6B0),
        surfaceTint = Color(0xFF8FD3BD),
        inverseSurface = Color(0xFFE1E8E4),
        inverseOnSurface = Color(0xFF2C3532),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        outline = Color(0xFF8A9791),
        outlineVariant = Color(0xFF3B4642),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF333C38),
        surfaceDim = Color(0xFF0E1412),
        surfaceContainerLowest = Color(0xFF0A0F0D),
        surfaceContainerLow = Color(0xFF151C19),
        surfaceContainer = Color(0xFF19211E),
        surfaceContainerHigh = Color(0xFF212A27),
        surfaceContainerHighest = Color(0xFF2B3531),
    )

/**
 * Fixed colours for each kind of data, the same with or without wallpaper colours, so
 * sleep is always indigo and screen time always pine wherever it appears.
 */
@Immutable
data class DataColors(
    val screen: Color,
    val sleep: Color,
    val nap: Color,
    val activity: Color,
    val pickups: Color,
    val notifications: Color,
    /** "Now" marks: the one place the marigold accent appears. */
    val now: Color,
    /** The usual-day reference line or range. */
    val usual: Color,
    val track: Color,
    val good: Color,
    val caution: Color,
    val alert: Color,
    /** Six distinct colours for app categories and day types, in a fixed order. */
    val series: List<Color>,
)

internal val DataColorsLight =
    DataColors(
        screen = Color(0xFF2E7D6B),
        sleep = Color(0xFF5560C9),
        nap = Color(0xFF9097E0),
        activity = Color(0xFFB7791F),
        pickups = Color(0xFF2F74B5),
        notifications = Color(0xFF8B4F9A),
        now = Color(0xFFE08E1B),
        usual = Color(0xFF4A5752),
        track = Color(0xFFE3E9E6),
        good = Color(0xFF2E7D4F),
        caution = Color(0xFFA86400),
        alert = Color(0xFFB3261E),
        series =
            listOf(
                Color(0xFF2E7D6B),
                Color(0xFF2F74B5),
                Color(0xFFB7791F),
                Color(0xFF8B4F9A),
                Color(0xFFC0503A),
                Color(0xFF5560C9),
            ),
    )

internal val DataColorsDark =
    DataColors(
        screen = Color(0xFF6CC9AE),
        sleep = Color(0xFF9AA3F2),
        nap = Color(0xFFC3C8F7),
        activity = Color(0xFFEFB25A),
        pickups = Color(0xFF8EC1F0),
        notifications = Color(0xFFCFA0DA),
        now = Color(0xFFF2A541),
        usual = Color(0xFFA9B6B0),
        track = Color(0xFF1E2724),
        good = Color(0xFF7BD49C),
        caution = Color(0xFFF2B866),
        alert = Color(0xFFF2B8B5),
        series =
            listOf(
                Color(0xFF6CC9AE),
                Color(0xFF8EC1F0),
                Color(0xFFEFB25A),
                Color(0xFFCFA0DA),
                Color(0xFFF09A86),
                Color(0xFF9AA3F2),
            ),
    )

val LocalDataColors = staticCompositionLocalOf { DataColorsLight }

/** Whether the current scheme is the dark one (for charts that tune opacity). */
val LocalIsDark = staticCompositionLocalOf { false }

internal fun ColorScheme.withPineSurfaces(dark: Boolean): ColorScheme {
    // Wallpaper colours tint the chrome (buttons, selection); data colours stay fixed.
    val base = if (dark) PineDark else PineLight
    return copy(error = base.error, onError = base.onError, errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer)
}

// Kept for code that hasn't moved to DataColors yet.
val StatusSuccess = Color(0xFF2E9E62)
val StatusWarning = Color(0xFFD08A1E)
val StatusError = Color(0xFFD9483B)
